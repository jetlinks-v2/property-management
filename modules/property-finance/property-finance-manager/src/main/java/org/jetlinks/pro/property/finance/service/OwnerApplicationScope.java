package org.jetlinks.pro.property.finance.service;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/** 业主接口只接受平台已激活的收费应用成员身份，不以客户端传入的应用ID作为授权。 */
@Component
public class OwnerApplicationScope {
    public Mono<String> current() {
        return Authentication.currentReactive()
                .switchIfEmpty(Mono.error(UnAuthorizedException.NoStackTrace::new))
                .flatMap(
                        auth ->
                                AssetScopeContext.currentReactive()
                                        .flatMap(
                                                scope ->
                                                        Mono.justOrEmpty(
                                                                scope.getScope(
                                                                        BusinessApplicationDimension
                                                                                .TYPE)))
                                        .filter(
                                                application ->
                                                        auth
                                                                .getDimensions(
                                                                        BusinessApplicationDimension
                                                                                .TYPE)
                                                                .stream()
                                                                .anyMatch(
                                                                        member ->
                                                                                application
                                                                                        .id()
                                                                                        .equals(
                                                                                                member
                                                                                                        .getId())))
                                        // 沿用真实生效的收费查询权限；组织、房屋与本人关系继续由业务边界校验。
                                        .filter(
                                                application ->
                                                        auth.getPermissions().stream()
                                                                .anyMatch(
                                                                        permission ->
                                                                                permission
                                                                                                .getId()
                                                                                                .startsWith(
                                                                                                        "property-finance-")
                                                                                        && auth
                                                                                                .hasPermission(
                                                                                                        permission
                                                                                                                .getId(),
                                                                                                        "query")))
                                        .map(application -> application.id())
                                        .switchIfEmpty(
                                                Mono.error(AccessDenyException.NoStackTrace::new)));
    }
}
