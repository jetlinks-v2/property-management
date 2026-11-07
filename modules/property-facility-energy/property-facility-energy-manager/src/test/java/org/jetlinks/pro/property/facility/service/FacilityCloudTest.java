package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.sdk.server.device.DeviceProperty;
import org.jetlinks.sdk.server.device.cmd.GetMetadataCommand;
import org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand;
import org.jetlinks.pro.assets.impl.terms.DimensionAssetsTerm;
import org.jetlinks.core.command.CommandSupport;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Source adapter pagination is validated before ingestion and cursor advancement. */
class FacilityCloudTest {

  @Test
  void devicePageKeepsProjectConstraintAndOwningProviderDenial() {
    var scope = mock(FacilityScope.class);
    when(scope.current()).thenReturn(Mono.just(new FacilityContext("tenant", "app", "user", "User")));
    var cloud = spy(new FacilityCloud(scope));
    var provider = mock(CommandSupport.class);
    doReturn(Mono.just(provider)).when(cloud).support();
    when(provider.execute(any(QueryPagerCommand.class))).thenAnswer(invocation -> {
      QueryPagerCommand<?> command = invocation.getArgument(0);
      var query = command.asQueryParam();
      assertEquals(2, query.getPageIndex());
      assertEquals(50, query.getPageSize());
      assertEquals(1, query.getTerms().size());
      var constraint = assertInstanceOf(DimensionAssetsTerm.class, query.getTerms().get(0).getValue());
      assertEquals("device", constraint.getAssetType());
      assertEquals("org", constraint.getTargets().iterator().next().getType());
      assertEquals("project", constraint.getTargets().iterator().next().getId());
      assertEquals(Boolean.TRUE, constraint.getClosure());
      return Mono.error(new AccessDenyException());
    });
    StepVerifier.create(cloud.page("project", 2, 50)).expectError(AccessDenyException.class).verify();
    verify(provider).execute(any(QueryPagerCommand.class));
  }

  @Test
  void historyUsesPublicCommandAndRestoresRemoteRows() {
    var command = FacilityCloud.historyCommand("meter", "total", 10, 100, 2);
    assertEquals("QueryFacilityPropertyPage", command.getCommandId());
    assertEquals(2, command.asMap().get("pageIndex"));
    assertEquals(200, command.asMap().get("pageSize"));
    assertNotNull(command.asMap().get("terms"));
    assertEquals("meter", command.getDeviceId());
    assertEquals("total", command.getProperty());
    var raw = new PagerResult<>(1, List.of(Map.of("property", "total", "value", 12, "timestamp", 20)));
    var response = assertInstanceOf(PagerResult.class, command.createResponseData(raw));
    var reading = assertInstanceOf(DeviceProperty.class, response.getData().get(0));
    assertEquals(20, reading.getTimestamp());
  }

  @Test
  void metadataUsesOwningCommandAndPropagatesDenial() {
    var cloud = spy(new FacilityCloud(mock(FacilityScope.class)));
    var provider = mock(CommandSupport.class);
    doReturn(Mono.just(provider)).when(cloud).support();
    when(provider.execute(any(GetMetadataCommand.class))).thenAnswer(invocation -> {
      assertEquals("meter", ((GetMetadataCommand) invocation.getArgument(0)).getId());
      return Mono.error(new AccessDenyException());
    });
    StepVerifier.create(cloud.metadata("meter")).expectError(AccessDenyException.class).verify();
    verify(provider).execute(any(GetMetadataCommand.class));
  }

  @Test
  void historicalProviderDenialDoesNotEmitOrFallback() {
    var cloud = spy(new FacilityCloud(mock(FacilityScope.class)));
    doReturn(Mono.error(new AccessDenyException())).when(cloud).historyPage("meter", "total", 1, 1000, 0);
    StepVerifier.create(cloud.history("meter", "total", 1, 1000)).expectError(AccessDenyException.class).verify();
  }

  @Test
  void incompleteHistoryNeverEmitsPartialData() {
    var cloud = spy(
      new FacilityCloud(mock(FacilityScope.class))
    );
    var page = new PagerResult<DeviceProperty>();
    page.setTotal(2);
    page.setData(List.of(mock(DeviceProperty.class)));
    doReturn(Mono.just(page))
      .when(cloud)
      .historyPage("meter", "total", 1, 1000, 0);
    StepVerifier.create(cloud.history("meter", "total", 1, 1000))
      .expectError(BusinessException.class)
      .verify();
  }

  @Test
  void completeHistoryEmitsEverySourceSample() {
    var cloud = spy(
      new FacilityCloud(mock(FacilityScope.class))
    );
    var rows = List.of(mock(DeviceProperty.class), mock(DeviceProperty.class));
    var page = new PagerResult<DeviceProperty>();
    page.setTotal(2);
    page.setData(rows);
    doReturn(Mono.just(page))
      .when(cloud)
      .historyPage("meter", "total", 1, 1000, 0);
    assertEquals(
      rows,
      cloud.history("meter", "total", 1, 1000).collectList().block()
    );
  }
}
