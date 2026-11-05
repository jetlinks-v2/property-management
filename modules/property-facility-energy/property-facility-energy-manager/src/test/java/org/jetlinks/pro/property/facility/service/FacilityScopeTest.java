package org.jetlinks.pro.property.facility.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.hswebframework.web.authorization.*;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.simple.*;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.impl.CreatorAssetsHolder;
import org.jetlinks.pro.assets.scope.*;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.DeviceEntity;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** 真实CreatorAssetsHolder及TenantAssetsHolder允许/拒绝对；只替换外部资产取数。 */
class FacilityScopeTest {

  final AssetManager assets = mock(AssetManager.class);
  final FacilityScope scope = new FacilityScope(assets);

  Context access() {
    var user = new SimpleUser();
    user.setId("worker");
    var auth = new SimpleAuthentication();
    auth.setUser(user);
    auth.setDimensions(
      List.of(
        SimpleDimension.of(
          "app",
          "App",
          org.jetlinks.pro.authorize.BusinessApplicationDimension.INSTANCE,
          Map.of(AssetScopeSources.POLICY_OPTION_KEY, Map.of("*", "IGNORE"))
        )
      )
    );
    var active = new AssetScopeResolverManager()
      .resolve(AssetScope.of("business_application", "app"), auth)
      .block();
    return Context.of(
      Authentication.class,
      active.authentication(),
      AssetsHolder.class,
      new CreatorAssetsHolder(user, assets)
    );
  }

  Mono<Void> check(String tenant, String app, String owner) {
    when(assets.getAssets(any(AssetType.class), anyCollection())).thenAnswer(
      call -> {
        AssetType type = call.getArgument(0);
        Collection<?> ids = call.getArgument(1);
        return Flux.fromIterable(ids).map(id ->
          new DefaultAsset(id.toString(), id.toString(), type)
            .withOption("tenantId", "tenant")
            .withOption("creatorId", owner)
        );
      }
    );
    var row = new DeviceEntity();
    row.setId("device");
    row.setTenantId(tenant);
    row.setApplicationId(app);
    return scope
      .assertRecord(
        row,
        FacilityAssetType.facilityDevice,
        "device",
        CrudAssetPermission.read
      )
      .contextWrite(access())
      .contextWrite(TenantHolder.makeCurrent("tenant"))
      .contextWrite(
        AssetScopeContext.replace(
          AssetScopeContext.of(AssetScope.of("business_application", "app"))
        )
      );
  }

  @Test
  void allowsOwnAssetWithinTenantAndApplication() {
    StepVerifier.create(check("tenant", "app", "worker")).verifyComplete();
  }

  @Test
  void refusesOtherCreatorTenantAndApplication() {
    StepVerifier.create(check("tenant", "app", "other"))
      .expectError(AccessDenyException.class)
      .verify();
    StepVerifier.create(check("other-tenant", "app", "worker"))
      .expectError(AccessDenyException.class)
      .verify();
    StepVerifier.create(check("tenant", "other-app", "worker"))
      .expectError(AccessDenyException.class)
      .verify();
  }

  @Test
  void refusesMissingContext() {
    StepVerifier.create(scope.current())
      .expectError(AccessDenyException.class)
      .verify();
  }

  @Test
  void refusesRemovedApplicationMemberEvenWithActiveScopeAndTenantAsset() {
    when(assets.getAssets(any(AssetType.class), anyCollection())).thenAnswer(
      call -> {
        AssetType type = call.getArgument(0);
        Collection<?> ids = call.getArgument(1);
        return Flux.fromIterable(ids).map(id ->
          new DefaultAsset(id.toString(), id.toString(), type)
            .withOption("tenantId", "tenant")
            .withOption("creatorId", "worker")
        );
      }
    );
    var context = access();
    var auth = new SimpleAuthentication();
    auth.setUser(context.<Authentication>get(Authentication.class).getUser());
    auth.setDimensions(List.of());
    var synthetic = new AssetScopeResolverManager()
      .resolve(AssetScope.of("business_application", "app"), auth)
      .block();
    StepVerifier.create(
      scope
        .current()
        .contextWrite(
          context.put(Authentication.class, synthetic.authentication())
        )
        .contextWrite(TenantHolder.makeCurrent("tenant"))
        .contextWrite(AssetScopeContext.replace(synthetic.context()))
    )
      .expectError(AccessDenyException.class)
      .verify();
    StepVerifier.create(
      scope
        .current()
        .contextWrite(context.put(Authentication.class, auth))
        .contextWrite(TenantHolder.makeCurrent("tenant"))
        .contextWrite(
          AssetScopeContext.replace(
            AssetScopeContext.of(AssetScope.of("business_application", "app"))
          )
        )
    )
      .expectError(AccessDenyException.class)
      .verify();
  }
}
