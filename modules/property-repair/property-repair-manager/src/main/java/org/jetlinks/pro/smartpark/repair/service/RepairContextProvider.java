package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.property.service.TenantAssetScope;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 校验应用确实属于当前请求租户，客户端不能用其他租户头复用原应用身份。 */
@Component
@AllArgsConstructor
public class RepairContextProvider {
    private final TenantAssetScope tenantScope;

    public Mono<RepairContext> current() {
        return Mono.zip(
                TenantHolder.currentId(),
                AssetScopeContext.currentReactive()
                    .flatMap(scope -> Mono.justOrEmpty(scope.getScope(BusinessApplicationDimension.TYPE))),
                Authentication.currentReactive())
            .map(tuple -> new RepairContext(tuple.getT1(), tuple.getT2().id(),
                tuple.getT3().getUser().getId(), tuple.getT3().getUser().getName()))
            .switchIfEmpty(Mono.error(new AccessDenyException()))
            .flatMap(context -> tenantScope
                .assertOwned(AssetType.of("business_application"), context.applicationId())
                .thenReturn(context));
    }
}
