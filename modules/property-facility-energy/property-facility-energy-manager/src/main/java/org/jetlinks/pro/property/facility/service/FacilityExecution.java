package org.jetlinks.pro.property.facility.service;

import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.*;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.scope.*;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.property.facility.entity.FacilityEntity;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 持久化后台作业恢复原操作人的当前授权；撤权后不会继续使用旧权限快照。 */
@Component
@RequiredArgsConstructor
public class FacilityExecution {

  private final AssetScopeResolverManager resolver;

  public <T> Mono<T> asOwner(FacilityEntity row, Supplier<Mono<T>> work) {
    return ReactiveAuthenticationHolder.get(row.getCreatorId())
      // Revoke background work even if an old scope grant can still be resolved.
      .filter(auth ->
        FacilityScope.hasApplicationMembership(auth, row.getApplicationId())
      )
      .flatMap(auth ->
        resolver.resolve(
          AssetScope.of(
            BusinessApplicationDimension.TYPE,
            row.getApplicationId()
          ),
          auth
        )
      )
      .switchIfEmpty(Mono.error(new AccessDenyException()))
      .flatMap(active ->
        AssetsHolder.fromAuth(
          active.authentication(),
          active.context()
        ).flatMap(holder ->
          Mono.defer(work)
            .contextWrite(c ->
              c
                .put(Authentication.class, active.authentication())
                .put(AssetsHolder.class, holder)
            )
            .contextWrite(AssetScopeContext.replace(active.context()))
        )
      )
      .contextWrite(
        TenantHolder.makeCurrent(Objects.toString(row.getTenantId()))
      );
  }

  public <T> reactor.core.publisher.Flux<T> asOwnerFlux(
    FacilityEntity row,
    java.util.function.Supplier<reactor.core.publisher.Flux<T>> work
  ) {
    return ReactiveAuthenticationHolder.get(row.getCreatorId())
      // Revoke background work even if an old scope grant can still be resolved.
      .filter(auth ->
        FacilityScope.hasApplicationMembership(auth, row.getApplicationId())
      )
      .flatMap(auth ->
        resolver.resolve(
          AssetScope.of(
            BusinessApplicationDimension.TYPE,
            row.getApplicationId()
          ),
          auth
        )
      )
      .switchIfEmpty(Mono.error(new AccessDenyException()))
      .flatMapMany(active ->
        AssetsHolder.fromAuth(
          active.authentication(),
          active.context()
        ).flatMapMany(holder ->
          reactor.core.publisher.Flux.defer(work)
            .contextWrite(c ->
              c
                .put(Authentication.class, active.authentication())
                .put(AssetsHolder.class, holder)
            )
            .contextWrite(AssetScopeContext.replace(active.context()))
        )
      )
      .contextWrite(
        TenantHolder.makeCurrent(Objects.toString(row.getTenantId()))
      );
  }
}
