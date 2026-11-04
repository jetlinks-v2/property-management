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

/**
 * 导出记录查询与下载元数据的唯一范围入口，关联创建人的平台用户资产。
 * 使用实时组织绑定和维度闭包，取同组织及下级员工与用户资产读取范围的交集；
 * 保留个人记录入口，并强制当前租户、应用。文件内工单的下载授权另行逐批复核。
 */
@Component @AllArgsConstructor
public class RepairExportScope {
    private final AssetScopeResolverManager scopeResolver;

    private record Access(Authentication authentication,AssetsHolder holder) {}

    private Mono<Access> current(RepairContext context) {
        // 定向调用认证服务，避免集群认证supplier在RPC失败时退回旧快照；错误直接传播。
        GetAuthenticationCommand command=new GetAuthenticationCommand().setUserId(context.userId());
        return CommandServices.getReactive(SdkServices.authService)
            // 此旧命令声明同步响应类型，使用官方异步桥接重用其认证反序列化规则。
            .flatMap(support -> support.executeToMono(command)).cast(Authentication.class)
            .flatMap(authentication -> scopeResolver.resolve(AssetScope.of(BusinessApplicationDimension.TYPE,context.applicationId()),authentication))
            .filter(activation -> activation.authentication().hasPermission("repair-order","export"))
            .flatMap(activation -> AssetsHolder.fromAuth(activation.authentication(),activation.context())
                .map(holder -> new Access(activation.authentication(),holder)))
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
            var access=QueryParamEntity.newQuery().where("creatorId",context.userId());
            Mono<?> prepare=Mono.empty();
            if(!organizations.isEmpty()) {
                DimensionAssetsTerm term=DimensionAssetsTerm.of(organizations,"user");term.setClosure(true);
                access.or("creatorId",DimensionAssetsTermBuilder.termType,term);
                prepare=term.applyContext();
            }
            // 先嵌套用户条件再追加AND；客户端OR不能越过应用、租户或创建人组织范围。
            QueryParamEntity scoped=query.clone().toNestQuery().and("applicationId",context.applicationId())
                .and("tenantId",context.tenantId()).getParam();
            Term visibility=new Term();visibility.setTerms(access.getParam().getTerms());scoped.getTerms().add(visibility);
            return prepare.then(accessScope.holder().injectQuery(scoped,"user","creatorId",CrudAssetPermission.read));
        });
    }
}
