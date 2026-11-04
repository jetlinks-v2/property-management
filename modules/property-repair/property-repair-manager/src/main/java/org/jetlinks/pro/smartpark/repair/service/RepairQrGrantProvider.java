package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.scope.AssetScope;
import org.jetlinks.pro.assets.scope.AssetScopeResolverManager;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.property.entity.PropertyOrganizationEntity;
import org.jetlinks.pro.property.entity.PropertySpaceEntity;
import org.jetlinks.pro.property.service.PropertyOrganizationService;
import org.jetlinks.pro.property.service.PropertySpaceService;
import org.jetlinks.pro.property.service.TenantAssetScope;
import org.jetlinks.pro.smartpark.repair.asset.RepairAssetType;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 公开二维码唯一授权owner。以发布人的实时AssetsHolder收窄已发布范围，不建立员工会话。
 * 客户端不能提交tenant/application/project/query；公开查询仅返回这里裁剪的分类与空间投影。
 */
@Service @AllArgsConstructor @Slf4j
public class RepairQrGrantProvider {
    private final RepairConfigurationService configurations;
    private final PropertyOrganizationService organizations;
    private final PropertySpaceService spaces;
    private final AssetScopeResolverManager scopeResolver;
    private final TenantAssetScope tenantScope;

    public Mono<Grant> resolve(String code) {
        return configurations.createQuery().where(RepairConfigurationEntity::getQrCode,code)
            .and(RepairConfigurationEntity::getKind,"qr").and(RepairConfigurationEntity::getEnabled,true).fetchOne()
            .switchIfEmpty(Mono.error(new NotFoundException()))
            .flatMap(qr -> {
                String tenant = Objects.toString(qr.getExtension("tenantId"),"");
                String publisher = Objects.toString(qr.getQrPublisherId(),"");
                return ReactiveAuthenticationHolder.get(publisher)
                    .doOnSuccess(auth->{if(auth==null)log.warn("QR publisher authentication unavailable");})
                    .flatMap(auth -> scopeResolver.resolve(AssetScope.of(BusinessApplicationDimension.TYPE,qr.getApplicationId()),auth)
                        .doOnError(error->log.warn("QR application activation failed: {}",error.getMessage())))
                    .filter(activation -> {boolean allowed=activation.authentication().hasPermission("repair-configuration","save");if(!allowed)log.warn("QR publisher has no configuration save action");return allowed;})
                    .flatMap(activation -> AssetsHolder.fromAuth(activation.authentication(),activation.context()))
                    .flatMap(holder -> load(qr,holder,tenant).doOnError(error->log.warn("QR grant scope rejected",error)))
                    .switchIfEmpty(Mono.error(new AccessDenyException()))
                    // 租户仅来自唯一二维码存储记录；请求头不能扩张或切换这个授权。
                    .contextWrite(TenantHolder.makeCurrent(tenant));
            });
    }

    private Mono<Grant> load(RepairConfigurationEntity qr, AssetsHolder holder, String tenant) {
        Mono<PropertyOrganizationEntity> project = holder.assertPermission0(RepairAssetType.repairConfiguration,List.of(qr.getId()),CrudAssetPermission.save).checkpoint("qr/configuration-save")
            .then(tenantScope.assertOwned(AssetType.of("business_application"),qr.getApplicationId()).checkpoint("qr/tenant-application"))
            .then(tenantScope.assertOwned(AssetType.of("organization"),qr.getBusinessProjectId()).checkpoint("qr/tenant-project"))
            .then(holder.assertPermission0(AssetType.of("organization"),List.of(qr.getBusinessProjectId()),CrudAssetPermission.read).checkpoint("qr/project-read"))
            .then(organizations.findById(qr.getBusinessProjectId()))
            .filter(row -> Boolean.TRUE.equals(row.getBusinessProject()) && Boolean.TRUE.equals(row.getSourceEnabled())
                && tenant.equals(row.getTenantId())).switchIfEmpty(Mono.error(new NotFoundException()));
        QueryParamEntity configurationQuery = QueryParamEntity.newQuery().where("applicationId",qr.getApplicationId())
            .and("businessProjectId",qr.getBusinessProjectId()).and("enabled",true)
            .in("kind",List.of("category","label","description","channel")).doPaging(0,20_001).getParam();
        Mono<List<RepairConfigurationEntity>> dictionaries = holder.injectQuery(configurationQuery,"repairConfiguration","id",CrudAssetPermission.read)
            .flatMap(query -> tenantScope.injectQuery(query,RepairAssetType.repairConfiguration,"id"))
            .flatMapMany(query -> configurations.createQuery().setParam(query).fetch()).collectList();
        QueryParamEntity spaceQuery = QueryParamEntity.newQuery().where("tenantId",tenant)
            .and("businessProjectId",qr.getBusinessProjectId()).and("sourceEnabled",true).doPaging(0,20_001).getParam();
        Mono<List<PropertySpaceEntity>> authorizedSpaces = holder.injectQuery(spaceQuery,"space","id",CrudAssetPermission.read)
            .flatMap(query -> tenantScope.injectQuery(query,AssetType.of("space"),"id"))
            .flatMapMany(query -> spaces.createQuery().setParam(query).fetch()).collectList();
        return project.flatMap(row -> Mono.zip(dictionaries,authorizedSpaces).map(tuple -> {
            RepairFlowRules.require(tuple.getT1().size()<=20_000 && tuple.getT2().size()<=20_000,"qr_scope_too_large");
            return new Grant(qr,row,tuple.getT1(),tuple.getT2());
        }));
    }

    /** 仅在已验证的能力范围内解析选项；所有后续公开操作都必须先重新resolve。 */
    public static final class Grant {
        final RepairConfigurationEntity qr;
        final PropertyOrganizationEntity project;
        final Map<String,RepairConfigurationEntity> configurations;
        final Map<String,PropertySpaceEntity> spaces;
        final Map<String,Object> settings;
        final java.util.Set<String> nonLeafSpaces;
        Grant(RepairConfigurationEntity qr,PropertyOrganizationEntity project,List<RepairConfigurationEntity> configurations,List<PropertySpaceEntity> spaces) {
            this.qr=qr;this.project=project;this.settings=qr.getConfiguration();
            this.configurations=configurations.stream().collect(Collectors.toMap(RepairConfigurationEntity::getId,Function.identity()));
            this.spaces=spaces.stream().collect(Collectors.toMap(PropertySpaceEntity::getId,Function.identity()));
            this.nonLeafSpaces=spaces.stream().filter(row->spaceAllowed(row.getId())).map(PropertySpaceEntity::getParentId).filter(Objects::nonNull).collect(Collectors.toSet());
        }
        RepairContext context() { return new RepairContext(project.getTenantId(),qr.getApplicationId(),"anonymous-qr:"+qr.getId(),"二维码匿名报事"); }
        RepairConfigurationEntity channel() { return configuration(Objects.toString(settings.get("channelId")),"channel"); }
        RepairConfigurationEntity configuration(String id,String kind) {
            RepairConfigurationEntity row=configurations.get(id);
            RepairFlowRules.require(row!=null && kind.equals(row.getKind()),"configuration_invalid");
            return row;
        }
        boolean categoryAllowed(String id) {
            int depth=0;boolean allowed=false;
            for(String current=id;current!=null;){
                RepairConfigurationEntity row=configurations.get(current);
                if(row==null||!"category".equals(row.getKind())||++depth>3)return false;
                allowed|=RepairQrRules.ids(settings,"categoryIds").contains(current);
                current=row.getParentId();
            }
            return allowed&&depth==3;
        }
        boolean within(String id,List<String> roots) {
            for(int depth=0;id!=null&&depth<64;depth++) {
                PropertySpaceEntity row=spaces.get(id);
                if(row==null)return false;
                if(roots.contains(id))return true;
                id=row.getParentId();
            }
            return false;
        }
        boolean spaceAllowed(String id) {
            if(!within(id,RepairQrRules.ids(settings,"spaceRootIds")))return false;
            return !Boolean.TRUE.equals(settings.get("enableCampus")) || RepairQrRules.areas(settings).stream()
                .anyMatch(area -> within(id,RepairQrRules.ids(area,"spaceRootIds")));
        }
        boolean selectable(String id) {
            if(!spaceAllowed(id))return false;
            if(within(id,RepairQrRules.ids(settings,"publicAreaRootIds")))return true;
            if(Boolean.FALSE.equals(settings.get("enableRoom")))return RepairQrRules.ids(settings,"spaceRootIds").contains(id)
                || RepairQrRules.areas(settings).stream().anyMatch(area -> RepairQrRules.ids(area,"spaceRootIds").contains(id));
            return !nonLeafSpaces.contains(id);
        }
        int positionType(String id) { return within(id,RepairQrRules.ids(settings,"publicAreaRootIds"))?1:2; }
    }
}
