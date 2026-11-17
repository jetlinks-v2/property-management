package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.jetlinks.pro.property.facility.entity.DeviceEntity;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;

class FacilitySaasAlarmsTest {
  @Test @SuppressWarnings("unchecked") void pollingRestoresEachApplicationIdentityAndContinuesAfterFailure() {
    var store = mock(DeviceStore.class);
    ReactiveQuery<DeviceEntity> query = mock(ReactiveQuery.class, RETURNS_SELF);
    when(store.createQuery()).thenReturn(query);
    var first = device("a"); var second = device("b");
    when(query.fetch()).thenReturn(Flux.just(first, second));
    var execution = mock(FacilityExecution.class);
    when(execution.asOwner(eq(first), any())).thenReturn(Mono.error(new IllegalStateException("unavailable")));
    when(execution.asOwner(eq(second), any())).thenReturn(Mono.just(0));
    var service = new FacilitySaasAlarms(store, mock(FacilityDevices.class), execution,
      mock(FacilityCloudAlarms.class), mock(FacilitySaasAlarmWriter.class));
    StepVerifier.create(service.poll()).verifyComplete();
    verify(execution).asOwner(eq(first), any()); verify(execution).asOwner(eq(second), any());
    assertEquals(1, service.getFailureCount()); assertFalse(service.isPolling());
    assertEquals("IllegalStateException", service.getLastError());
  }

  @Test @SuppressWarnings("unchecked") void cancellationAndScannerErrorsReleasePollingGate() {
    var store = mock(DeviceStore.class);
    ReactiveQuery<DeviceEntity> query = mock(ReactiveQuery.class, RETURNS_SELF);
    when(store.createQuery()).thenReturn(query); when(query.fetch()).thenReturn(Flux.never());
    var service = new FacilitySaasAlarms(store, mock(FacilityDevices.class), mock(FacilityExecution.class),
      mock(FacilityCloudAlarms.class), mock(FacilitySaasAlarmWriter.class));
    var subscription = service.poll().subscribe(); assertTrue(service.isPolling());
    StepVerifier.create(service.poll()).verifyComplete(); verify(store, times(1)).createQuery();
    subscription.dispose(); assertFalse(service.isPolling());
    when(query.fetch()).thenReturn(Flux.error(new IllegalStateException("database unavailable")));
    StepVerifier.create(service.poll()).expectError(IllegalStateException.class).verify();
    assertEquals(1, service.getFailureCount()); assertFalse(service.isPolling());
  }

  static DeviceEntity device(String app) {
    var row = new DeviceEntity(); row.setId(app); row.setTenantId("tenant");
    row.setApplicationId(app); row.setCreatorId("admin"); row.setProjectId("project"); return row;
  }
}
