package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.scope.AssetScope;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.assets.scope.AssetScopeResolverManager;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.property.entity.PropertyPersonEntity;
import org.jetlinks.pro.smartpark.repair.asset.RepairAssetType;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.List;

/** 维修候选人与派单提交共用的实时资格判定，按目标人员授权而不是其所属组织判断。 */
@Service
@AllArgsConstructor
public class RepairAssigneeService {
    private final AssetScopeResolverManager scopeResolver;

    public Mono<Boolean> canHandle(PropertyPersonEntity person, RepairOrderEntity order, RepairContext context) {
        return canHandle(person, order, context, null);
    }

    public Mono<Boolean> canHandle(PropertyPersonEntity person, RepairOrderEntity order, RepairContext context, String roleId) {
        if (!context.tenantId().equals(person.getTenantId()) || !Boolean.TRUE.equals(person.getEmployee())
            || person.getSourceStatus() == null || person.getSourceStatus() != 1) {
            return Mono.just(false);
        }
        AssetScope scope = AssetScope.of(BusinessApplicationDimension.TYPE, context.applicationId());
        // 原始认证尚未激活应用授权；复用平台 resolver 才能获得该候选人的真实应用权限。
        return ReactiveAuthenticationHolder.get(person.getId())
            .flatMap(auth -> scopeResolver.resolve(scope, auth))
            .filter(activation -> roleId == null || activation.authentication().getDimensions("role").stream()
                .anyMatch(role -> roleId.equals(role.getId())))
            .filter(activation -> List.of("accept", "follow", "finish").stream()
                .allMatch(action -> activation.authentication().hasPermission("repair-order", action)))
            .flatMap(activation -> AssetsHolder.fromAuth(activation.authentication(), activation.context()))
            .flatMap(holder -> holder.hasPermission(RepairAssetType.repairOrder,
                List.of(order.getId()), CrudAssetPermission.save))
            .defaultIfEmpty(false)
            // 未加入应用或没有目标资产授权属于候选资格不满足；基础设施异常仍正常上报。
            .onErrorReturn(AccessDenyException.class, false);
    }

    public Mono<PropertyPersonEntity> assertCanHandle(PropertyPersonEntity person, RepairOrderEntity order,
                                                     RepairContext context) {
        return canHandle(person, order, context)
            .flatMap(allowed -> allowed ? Mono.just(person) : Mono.error(new AccessDenyException()));
    }
}
