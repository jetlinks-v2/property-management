package org.jetlinks.pro.property.decoration.service;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleDimension;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.jetlinks.pro.assets.scope.AssetScopeActivation;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 消费平台已激活的应用范围；平台应用无租户仍有效，缺少应用或身份必须拒绝。 */
class DecorationScopeTest {
    private final DecorationScope scope = new DecorationScope();

    private SimpleAuthentication authentication() {
        var auth = new SimpleAuthentication();
        var user = new SimpleUser();
        user.setId("user"); user.setUsername("user"); user.setName("测试用户");
        auth.setUser(user); auth.setDimensions(List.of()); auth.setPermissions(List.of());
        return auth;
    }

    private AssetScopeActivation application(String id) {
        return AssetScopeActivation.activate(authentication(),
            SimpleDimension.of(id, id, BusinessApplicationDimension.INSTANCE, Map.of()), Map.of());
    }

    @Test void platformApplicationDoesNotRequireTenantAndKeepsExactApplication() {
        StepVerifier.create(scope.current().contextWrite(application("app-a").write()))
            .assertNext(current -> {
                assertEquals("", current.tenantId());
                assertEquals("app-a", current.applicationId());
                assertEquals("user", current.userId());
            }).verifyComplete();
        StepVerifier.create(scope.current().contextWrite(application("app-b").write())
                .contextWrite(TenantHolder.makeCurrent("tenant-b")))
            .assertNext(current -> {
                assertEquals("tenant-b", current.tenantId());
                assertEquals("app-b", current.applicationId());
            }).verifyComplete();
    }

    @Test void missingApplicationOrAuthenticationIsDenied() {
        StepVerifier.create(scope.current().contextWrite(ctx -> ctx.put(Authentication.class, authentication())))
            .expectError(AccessDenyException.class).verify();
        StepVerifier.create(scope.current().contextWrite(ctx ->
                application("app-a").write().apply(ctx).delete(Authentication.class)))
            .expectError(AccessDenyException.class).verify();
    }
}
