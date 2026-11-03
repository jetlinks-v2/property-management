package org.jetlinks.pro.patrol.integration;

import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/** 巡更以当前 SaaS 应用为业务范围；租户仅保留平台已有技术上下文，不等同于园区业务项目。 */
@Component
public class PatrolScope {
    public record Current(String tenantId, String applicationId) {
        public String key() {
            return tenantId + "|" + applicationId;
        }
    }

    public Mono<Current> current() {
        // 平台创建的应用可以不属于租户；应用 Scope 仍须由平台激活，资产授权另由 AssetsHolder 负责。
        return AssetScopeContext.currentReactive()
                .flatMap(scope -> Mono.justOrEmpty(scope.getScope(BusinessApplicationDimension.TYPE)))
                .zipWith(TenantHolder.currentId().defaultIfEmpty(""))
                .map(pair -> new Current(pair.getT2(), pair.getT1().id()))
                .switchIfEmpty(Mono.error(AccessDenyException.NoStackTrace::new));
    }
}
