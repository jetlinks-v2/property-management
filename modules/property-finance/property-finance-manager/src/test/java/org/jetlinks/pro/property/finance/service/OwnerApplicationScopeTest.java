package org.jetlinks.pro.property.finance.service;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.hswebframework.web.authorization.simple.*;
import org.jetlinks.pro.assets.scope.AssetScope;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.junit.jupiter.api.Test;

import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.List;
import java.util.Map;
import java.util.Set;

class OwnerApplicationScopeTest {
    final OwnerApplicationScope scope = new OwnerApplicationScope();

    private SimpleAuthentication auth(String application, String permission, Set<String> actions) {
        var user = new SimpleUser();
        user.setId("owner");
        var auth = new SimpleAuthentication();
        auth.setUser(user);
        auth.setDimensions(
                List.of(
                        SimpleDimension.of(
                                application,
                                application,
                                SimpleDimensionType.of("business_application"),
                                Map.of())));
        auth.setPermissions(
                List.of(SimplePermission.builder().id(permission).actions(actions).build()));
        return auth;
    }

    private Context context(SimpleAuthentication auth, String app) {
        return Context.of(Authentication.class, auth)
                .put(
                        AssetScopeContext.class,
                        AssetScopeContext.of(AssetScope.of("business_application", app)));
    }

    @Test
    void genuineMemberWithFinanceQueryCanEnterWithoutEmployeeMaintenancePermissions() {
        StepVerifier.create(
                        scope.current()
                                .contextWrite(
                                        context(
                                                auth(
                                                        "charge-a",
                                                        "property-finance-owner",
                                                        Set.of("query")),
                                                "charge-a")))
                .expectNext("charge-a")
                .verifyComplete();
    }

    @Test
    void headerCannotSelectAnApplicationOutsideMembership() {
        StepVerifier.create(
                        scope.current()
                                .contextWrite(
                                        context(
                                                auth(
                                                        "charge-a",
                                                        "property-finance-owner",
                                                        Set.of("query")),
                                                "charge-b")))
                .expectError(AccessDenyException.class)
                .verify();
    }

    @Test
    void missingApplicationDoesNotFallBackToTenantOrAccount() {
        StepVerifier.create(
                        scope.current()
                                .contextWrite(
                                        Context.of(
                                                Authentication.class,
                                                auth(
                                                        "charge-a",
                                                        "property-finance-owner",
                                                        Set.of("query")))))
                .expectError(AccessDenyException.class)
                .verify();
    }

    @Test
    void unrelatedApplicationPermissionsCannotEnterFinance() {
        StepVerifier.create(
                        scope.current()
                                .contextWrite(
                                        context(
                                                auth("patrol-a", "patrol-task", Set.of("query")),
                                                "patrol-a")))
                .expectError(AccessDenyException.class)
                .verify();
    }

    @Test
    void RevokedQueryPermissionCannotEnter() {
        StepVerifier.create(
                        scope.current()
                                .contextWrite(
                                        context(
                                                auth(
                                                        "charge-a",
                                                        "property-finance-owner",
                                                        Set.of("save")),
                                                "charge-a")))
                .expectError(AccessDenyException.class)
                .verify();
    }

    @Test
    void anonymousIsRejectedBeforeApplicationLookup() {
        StepVerifier.create(scope.current()).expectError(UnAuthorizedException.class).verify();
    }
}
