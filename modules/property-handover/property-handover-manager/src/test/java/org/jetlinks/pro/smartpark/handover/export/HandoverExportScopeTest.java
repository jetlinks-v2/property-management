package org.jetlinks.pro.smartpark.handover.export;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.assets.scope.*;
import org.jetlinks.pro.command.CommandServices;
import org.jetlinks.pro.command.auth.GetAuthenticationCommand;
import org.jetlinks.pro.smartpark.handover.service.HandoverContext;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.jetlinks.sdk.server.SdkServices;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;

class HandoverExportScopeTest {
    @Test
    void eachAttemptUsesAuthoritativeIdentityAndActivatesApplicationAndTenant() {
        var context = new HandoverContext("tenant", "application", "employee", "员工");
        var resolver = mock(AssetScopeResolverManager.class);
        var support = mock(CommandSupport.class);
        var authentication = mock(Authentication.class);
        var scope = AssetScope.of("business_application", "application");
        var active = mock(AssetScopeActivation.class);
        when(active.authentication()).thenReturn(authentication);
        when(active.replace()).thenReturn(ctx -> ctx.put("live-application", "application"));
        when(authentication.hasPermission("handover-export", "export")).thenReturn(true);
        when(resolver.resolve(scope, authentication)).thenReturn(Mono.just(active));
        AtomicInteger reads = new AtomicInteger();
        doAnswer(
                        call -> {
                            GetAuthenticationCommand command = call.getArgument(0);
                            assertEquals("employee", command.getUserId());
                            assertNull(command.getToken());
                            reads.incrementAndGet();
                            return Mono.just(authentication);
                        })
                .when(support)
                .executeToMono(any(GetAuthenticationCommand.class));
        try (var commands = mockStatic(CommandServices.class);
                var tenants = mockStatic(TenantHolder.class, CALLS_REAL_METHODS)) {
            tenants.when(() -> TenantHolder.currentSelectedReactive(authentication))
                    .thenReturn(Mono.just(mock(org.jetlinks.pro.tenant.Tenant.class)));
            commands.when(() -> CommandServices.getReactive(SdkServices.authService))
                    .thenReturn(Mono.just(support));
            var service = new HandoverExportScope(resolver);
            StepVerifier.create(
                            service.execute(
                                    context,
                                    () ->
                                            Mono.deferContextual(
                                                    ctx -> {
                                                        assertEquals(
                                                                "application",
                                                                ctx.get("live-application"));
                                                        return TenantHolder.currentId();
                                                    })))
                    .expectNext("tenant")
                    .verifyComplete();
            when(authentication.hasPermission("handover-export", "export")).thenReturn(false);
            StepVerifier.create(service.execute(context, () -> Mono.just("must never execute")))
                    .expectError(AccessDenyException.class)
                    .verify();
            assertEquals(2, reads.get());
        }
    }

    @Test
    void authFailureNeverFallsBackToOldSession() {
        try (var commands = mockStatic(CommandServices.class)) {
            commands.when(() -> CommandServices.getReactive(SdkServices.authService))
                    .thenReturn(Mono.error(new IllegalStateException("auth unavailable")));
            StepVerifier.create(
                            new HandoverExportScope(mock(AssetScopeResolverManager.class))
                                    .execute(
                                            new HandoverContext("t", "a", "u", "name"),
                                            () -> Mono.just("forbidden")))
                    .expectErrorMessage("auth unavailable")
                    .verify();
        }
    }
}
