package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.DimensionInfo;
import org.jetlinks.pro.assets.scope.AssetScope;
import org.jetlinks.pro.assets.scope.AssetScopeResolverManager;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.command.CommandServices;
import org.jetlinks.pro.command.auth.GetAuthenticationCommand;
import org.jetlinks.sdk.server.SdkServices;
import org.jetlinks.pro.assets.impl.supports.OrgAssetsAccessSupport;
import org.jetlinks.pro.assets.impl.terms.DimensionAssetsTerm;
import org.jetlinks.pro.assets.impl.terms.DimensionAssetsTermBuilder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.List;
import org.jetlinks.pro.smartpark.repair.entity.RepairExportEntity;

/**
 * 导出记录查询与下载元数据的唯一范围入口，关联创建人的平台用户资产。
 * 使用实时组织绑定和维度闭包，取同组织及下级员工与用户资产读取范围的交集；
 * 保留个人记录入口，并强制当前租户、应用。文件内工单的下载授权另行逐批复核。
 */
@Component @AllArgsConstructor
public class RepairExportScope {
    private final AssetScopeResolverManager scopeResolver;
    private final RepairExportRepository exports;

    private record Access(Authentication authentication,AssetsHolder holder,org.jetlinks.pro.assets.scope.AssetScopeContext context) {}

    private Mono<Access> current(RepairContext context) {
        // 定向调用认证服务，避免集群认证supplier在RPC失败时退回旧快照；错误直接传播。
        GetAuthenticationCommand command=new GetAuthenticationCommand().setUserId(context.userId());
        return CommandServices.getReactive(SdkServices.authService)
            // 此旧命令声明同步响应类型，使用官方异步桥接重用其认证反序列化规则。
            .flatMap(support -> support.executeToMono(command)).cast(Authentication.class)
            .flatMap(authentication -> scopeResolver.resolve(AssetScope.of(BusinessApplicationDimension.TYPE,context.applicationId()),authentication))
            .filter(activation -> activation.authentication().hasPermission("repair-order","export"))
            .flatMap(activation -> AssetsHolder.fromAuth(activation.authentication(),activation.context())
                .map(holder -> new Access(activation.authentication(),holder,activation.context())))
            .switchIfEmpty(Mono.error(new AccessDenyException()));
    }

    public Mono<AssetsHolder> holder(RepairContext context) {
        return current(context).map(Access::holder);
    }

    public Mono<QueryParamEntity> inject(QueryParamEntity query,RepairContext context) {
        return current(context).flatMap(accessScope -> {
            List<DimensionInfo> organizations=accessScope.authentication().getDimensions("org").stream()
                .filter(dimension -> dimension.getOption(OrgAssetsAccessSupport.IS_DIRECT)
                    .map(value -> Boolean.TRUE.equals(value) || "true".equals(value.toString())).orElse(false))
                .map(dimension -> DimensionInfo.of("org",dimension.getId())).toList();
            // s_user 和用户资产属于认证库。只读取当前应用导出创建人，再按200个候选批次远程核验。
            return exports.createQuery().where("applicationId",context.applicationId())
                .and("tenantId",context.tenantId()).fetch()
                .map(RepairExportEntity::getCreatorId).distinct().take(100001).collectList()
                .flatMap(creators -> {
                    RepairFlowRules.require(creators.size()<=100000,"export_limit");
                    return reactor.core.publisher.Flux.fromIterable(creators).buffer(200)
                        .concatMap(batch -> readableCreators(batch,organizations,context,accessScope)).distinct().collectList();
                }).map(ids -> {
                    QueryParamEntity scoped=query.clone().toNestQuery().and("applicationId",context.applicationId())
                        .and("tenantId",context.tenantId()).getParam();
                    // 远程空授权必须为空范围，不能把空IN省略成全量导出记录。
                    scoped.toNestQuery(nest -> {
                        if(ids.isEmpty())nest.isNull("creatorId").notNull("creatorId");
                        else nest.in("creatorId",ids);
                    });
                    return scoped;
                });
        });
    }
    /** 保留当前身份与应用scope，组织闭包和user/read均由认证服务在所属数据库解析。 */
    private reactor.core.publisher.Flux<String> readableCreators(List<String> batch,List<DimensionInfo> organizations,
                                                                  RepairContext context,Access active) {
        var access=QueryParamEntity.newQuery().where("id",context.userId());
        Mono<?> prepare=Mono.empty();
        if(!organizations.isEmpty()) {
            DimensionAssetsTerm term=DimensionAssetsTerm.of(organizations,"user");term.setClosure(true);
            access.or("id",DimensionAssetsTermBuilder.termType,term);prepare=term.applyContext();
        }
        var query=access.getParam().toNestQuery().in("id",batch).getParam();
        query.setPaging(false);query.setIncludes(java.util.Set.of("id"));
        var command=org.jetlinks.sdk.server.commons.cmd.QueryListCommand.of(org.jetlinks.sdk.server.auth.UserInfo.class);
        command.withQueryParam(query);
        return prepare.thenMany(org.jetlinks.pro.command.CommandSupportManagerProviders
            .getCommandSupport("authService:user-directory")
            .switchIfEmpty(Mono.error(new org.hswebframework.web.exception.BusinessException.NoStackTrace("error.repair.directory_unavailable",503)))
            .flatMapMany(support -> support.execute(command)))
            .map(org.jetlinks.sdk.server.auth.UserInfo::getId).filter(batch::contains)
            .timeout(java.time.Duration.ofSeconds(10))
            .contextWrite(c -> c.put(Authentication.class,active.authentication()).put(AssetsHolder.class,active.holder()))
            .contextWrite(org.jetlinks.pro.assets.scope.AssetScopeContext.replace(active.context()));
    }

}
