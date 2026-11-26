package org.jetlinks.pro.smartpark.handover.export;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.scope.AssetScope;
import org.jetlinks.pro.assets.scope.AssetScopeActivation;
import org.jetlinks.pro.assets.scope.AssetScopeResolverManager;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.command.CommandServices;
import org.jetlinks.pro.command.auth.GetAuthenticationCommand;
import org.jetlinks.pro.smartpark.handover.service.HandoverContext;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.jetlinks.sdk.server.SdkServices;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.function.Supplier;

/** Rebuild the creator's live application scope; never persist authentication or credentials. */
@Component
@RequiredArgsConstructor
public class HandoverExportScope {
    static final Duration REFRESH_INTERVAL = Duration.ofSeconds(15);
    private static final Duration AUTHORIZATION_TIMEOUT = Duration.ofSeconds(10);
    private final AssetScopeResolverManager resolver;

    public <T> Mono<T> execute(HandoverContext context, Supplier<Mono<T>> action) {
        return new Session(context, liveActivation(context)).execute(action);
    }

    /** 每个任务阶段或下载请求单独复用授权，过期重新获取，失败时拒绝使用旧授权。 */
    Session session(HandoverContext context) {
        return new Session(
                context,
                liveActivation(context)
                        .cache(active -> REFRESH_INTERVAL, error -> Duration.ZERO, () -> Duration.ZERO));
    }

    private Mono<AssetScopeActivation> liveActivation(HandoverContext context) {
        return Mono.defer(() -> resolve(context)).timeout(AUTHORIZATION_TIMEOUT);
    }

    Mono<AssetScopeActivation> resolve(HandoverContext context) {
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
                                        .thenReturn(active)
                                        .contextWrite(active.replace())
                                        .contextWrite(
                                                TenantHolder.makeCurrent(context.tenantId())));
    }

    record Session(HandoverContext context, Mono<AssetScopeActivation> activation) {
        <T> Mono<T> execute(Supplier<Mono<T>> action) {
            return activation.flatMap(
                    active -> Mono.defer(action)
                            .contextWrite(active.replace())
                            .contextWrite(TenantHolder.makeCurrent(context.tenantId())));
        }
    }
}
