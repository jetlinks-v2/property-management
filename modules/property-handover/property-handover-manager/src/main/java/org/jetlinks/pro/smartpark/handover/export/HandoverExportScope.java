package org.jetlinks.pro.smartpark.handover.export;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.scope.AssetScope;
import org.jetlinks.pro.assets.scope.AssetScopeResolverManager;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.command.CommandServices;
import org.jetlinks.pro.command.auth.GetAuthenticationCommand;
import org.jetlinks.pro.smartpark.handover.service.HandoverContext;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.jetlinks.sdk.server.SdkServices;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.function.Supplier;

/** Rebuild the creator's live application scope; never persist authentication or credentials. */
@Component
@RequiredArgsConstructor
public class HandoverExportScope {
    private final AssetScopeResolverManager resolver;

    public <T> Mono<T> execute(HandoverContext context, Supplier<Mono<T>> action) {
        GetAuthenticationCommand command =
                new GetAuthenticationCommand().setUserId(context.userId());
        // This legacy command declares a synchronous response; the official bridge retains its
        // codec.
        return CommandServices.getReactive(SdkServices.authService)
                .flatMap(service -> service.executeToMono(command))
                .cast(Authentication.class)
                .flatMap(
                        auth ->
                                resolver.resolve(
                                        AssetScope.of(
                                                BusinessApplicationDimension.TYPE,
                                                context.applicationId()),
                                        auth))
                .filter(
                        active ->
                                active.authentication().hasPermission("handover-export", "export"))
                .switchIfEmpty(Mono.error(new AccessDenyException()))
                .flatMap(
                        active ->
                                TenantHolder.currentSelectedReactive(active.authentication())
                                        .switchIfEmpty(Mono.error(new AccessDenyException()))
                                        .then(Mono.defer(action))
                                        .contextWrite(active.replace())
                                        .contextWrite(
                                                TenantHolder.makeCurrent(context.tenantId())));
    }
}
