package org.jetlinks.pro.patrol.integration;

import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/** 只从可信平台上下文取项目和应用；业务唯一键使用它，不用它替代 AssetsHolder 查询。 */
@Component
public class PatrolScope {
    public record Current(String projectId, String applicationId) {
        public String key() {
            return projectId + "|" + applicationId;
        }
    }

    public Mono<Current> current() {
        return TenantHolder.currentId()
                .zipWith(
                        AssetScopeContext.currentReactive()
                                .flatMap(
                                        scope ->
                                                Mono.justOrEmpty(
                                                        scope.getScope(
                                                                BusinessApplicationDimension
                                                                        .TYPE))))
                .map(pair -> new Current(pair.getT1(), pair.getT2().id()))
                .switchIfEmpty(Mono.error(AccessDenyException.NoStackTrace::new));
    }
}
