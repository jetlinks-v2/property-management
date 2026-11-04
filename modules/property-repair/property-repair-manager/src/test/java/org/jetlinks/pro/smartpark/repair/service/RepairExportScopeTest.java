package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.scope.AssetScope;
import org.jetlinks.pro.assets.scope.AssetScopeActivation;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.assets.scope.AssetScopeResolverManager;
import org.jetlinks.pro.command.CommandServices;
import org.jetlinks.pro.command.auth.GetAuthenticationCommand;
import org.jetlinks.sdk.server.SdkServices;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepairExportScopeTest {
    private final RepairContext context=new RepairContext("tenant","application","employee","员工");

    @Test void downloadActivatesAuthoritativeUserAndRejectsRevokedExportAction() {
        var resolver=mock(AssetScopeResolverManager.class);var support=mock(CommandSupport.class);
        var authentication=mock(Authentication.class);var holder=mock(AssetsHolder.class);
        var scope=AssetScope.of("business_application","application");
        var active=mock(AssetScopeActivation.class);
        when(active.authentication()).thenReturn(authentication);
        when(active.context()).thenReturn(AssetScopeContext.of(scope));
        when(authentication.hasPermission("repair-order","export")).thenReturn(true);
        when(resolver.resolve(scope,authentication)).thenReturn(Mono.just(active));
        doAnswer(call -> {
            GetAuthenticationCommand command=call.getArgument(0);
            assertEquals("employee",command.getUserId());assertNull(command.getToken());
            return Mono.just(authentication);
        }).when(support).executeToMono(any(GetAuthenticationCommand.class));
        try(var commands=mockStatic(CommandServices.class);var assets=mockStatic(AssetsHolder.class)) {
            commands.when(() -> CommandServices.getReactive(SdkServices.authService)).thenReturn(Mono.just(support));
            assets.when(() -> AssetsHolder.fromAuth(authentication,active.context())).thenReturn(Mono.just(holder));
            var service=new RepairExportScope(resolver);
            StepVerifier.create(service.holder(context)).expectNext(holder).verifyComplete();
            when(authentication.hasPermission("repair-order","export")).thenReturn(false);
            StepVerifier.create(service.holder(context)).expectError(AccessDenyException.class).verify();
            assets.verify(() -> AssetsHolder.fromAuth(authentication,active.context()),times(1));
        }
    }

    @Test void authoritativeServiceFailureCannotFallBackToCachedSession() {
        var resolver=mock(AssetScopeResolverManager.class);
        try(var commands=mockStatic(CommandServices.class)) {
            commands.when(() -> CommandServices.getReactive(SdkServices.authService))
                .thenReturn(Mono.error(new IllegalStateException("auth unavailable")));
            StepVerifier.create(new RepairExportScope(resolver).holder(context)).expectErrorMessage("auth unavailable").verify();
            verifyNoInteractions(resolver);
        }
    }
}
