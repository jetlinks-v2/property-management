package org.jetlinks.pro.patrol.integration;

import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/** 巡更业务范围仅取平台已激活的应用；项目由授权组织树约束，租户隔离由平台负责。 */
@Component
public class PatrolScope {
    public record Current(String applicationId) {
        public String key() {
            return applicationId;
        }
    }

    public Mono<Current> current() {
        // 不读取登录入口的租户上下文，避免同一应用因平台/租户入口不同而分裂业务数据。
        // 应用准入、租户隔离及资产动作权限仍由平台 Scope 和 AssetsHolder 校验。
        return AssetScopeContext.currentReactive()
                .flatMap(scope -> Mono.justOrEmpty(scope.getScope(BusinessApplicationDimension.TYPE)))
                .map(application -> new Current(application.id()))
                .switchIfEmpty(Mono.error(AccessDenyException.NoStackTrace::new));
    }
}
