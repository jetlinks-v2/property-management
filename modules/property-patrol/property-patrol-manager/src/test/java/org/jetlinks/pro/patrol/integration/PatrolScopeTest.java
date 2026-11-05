package org.jetlinks.pro.patrol.integration;

import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.scope.AssetScope;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PatrolScopeTest {
    private final PatrolScope scope = new PatrolScope();

    @Test
    void sameApplicationHasSameBusinessScopeRegardlessOfTenantEntry() {
        for (Context entry : new Context[]{Context.empty(), TenantHolder.makeCurrent("tenant-a"),
                TenantHolder.makeCurrent("tenant-b")}) {
            StepVerifier.create(scope.current().contextWrite(entry.put(AssetScopeContext.class,
                    AssetScopeContext.of(AssetScope.of("business_application", "patrol-app")))))
                .assertNext(current -> {
                    assertEquals("patrol-app", current.applicationId());
                    assertEquals("patrol-app", current.key());
                }).verifyComplete();
        }
    }

    @Test
    void applicationBoundariesRemainDistinctAndTenantCannotReplaceApplication() {
        StepVerifier.create(scope.current().contextWrite(AssetScopeContext.write(
                AssetScope.of("business_application", "other-app"))))
            .assertNext(current -> assertEquals("other-app", current.key())).verifyComplete();
        StepVerifier.create(scope.current().contextWrite(TenantHolder.makeCurrent("tenant-a")))
            .expectError(AccessDenyException.class).verify();
    }
}
