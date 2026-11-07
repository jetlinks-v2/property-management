package org.jetlinks.pro.property.facility.service;

import static org.mockito.Mockito.*;

import java.util.List;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.jetlinks.pro.assets.scope.AssetScopeResolverManager;
import org.jetlinks.pro.property.facility.entity.DeviceEntity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FacilityExecutionTest {

  @Test
  void revokedMemberCannotStartJobOrSubscriptionFromStoredConfiguration() {
    var user = new SimpleUser();
    user.setId("creator");
    var auth = new SimpleAuthentication();
    auth.setUser(user);
    auth.setDimensions(List.of());
    var row = new DeviceEntity();
    row.setCreatorId("creator");
    row.setTenantId("tenant");
    row.setApplicationId("app");
    var resolver = mock(AssetScopeResolverManager.class);
    var execution = new FacilityExecution(resolver);
    try (var lookup = mockStatic(ReactiveAuthenticationHolder.class)) {
      lookup
        .when(() -> ReactiveAuthenticationHolder.get("creator"))
        .thenReturn(Mono.just(auth));
      StepVerifier.create(
        execution.asOwner(row, () ->
          Mono.error(new AssertionError("job executed"))
        )
      )
        .expectError(AccessDenyException.class)
        .verify();
      StepVerifier.create(
        execution.asOwnerFlux(row, () ->
          Flux.error(new AssertionError("subscription started"))
        )
      )
        .expectError(AccessDenyException.class)
        .verify();
      verifyNoInteractions(resolver);
    }
  }
}
