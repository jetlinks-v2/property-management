package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.jetlinks.core.event.EventBus;
import org.jetlinks.pro.plugin.context.ClusterPluginScheduler;
import org.jetlinks.pro.property.facility.entity.DeviceEntity;
import org.jetlinks.pro.property.facility.model.CloudAlarmRecord;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;

class FacilitySaasAlarmsTest {
  @Test @SuppressWarnings("unchecked") void topicSignalsOnlyQueryAffectedDevicesThroughCurrentBindingIntersection() {
    var store = mock(DeviceStore.class);
    ReactiveQuery<DeviceEntity> query = mock(ReactiveQuery.class, RETURNS_SELF);
    when(store.createQuery()).thenReturn(query);
    var devices = mock(FacilityDevices.class);
    var cloud = spy(new FacilityCloudAlarms());
    var writer = mock(FacilitySaasAlarmWriter.class);
    var row = device("a"); row.setCloudDeviceId("meter");
    var record = signal("meter"); record.setId("authoritative-record"); record.setState("warning");
    when(devices.currentDeviceQuery(any(), eq(Set.of("meter")))).thenAnswer(call -> Mono.just(call.getArgument(0)));
    when(query.fetch()).thenReturn(Flux.just(row));
    var support = mock(org.jetlinks.core.command.CommandSupport.class);
    doReturn(Mono.just(support)).when(cloud).support();
    doAnswer(call -> {
      var command = (org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand<?>) call.getArgument(0);
      var filter = command.asQueryParam();
      assertEquals(1, filter.getPageSize());
      assertEquals("authoritative-record", filter.getTerms().stream()
        .filter(term -> "id".equals(term.getColumn())).findFirst().orElseThrow().getValue());
      return Mono.just(new org.hswebframework.web.api.crud.entity.PagerResult<>(1, List.of(record)));
    }).when(support).execute(any(org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand.class));
    when(writer.accept(row, record)).thenReturn(Mono.empty());
    var service = new FacilitySaasAlarms(store, devices, mock(FacilityExecution.class), cloud, writer, mock(FacilityAlarmTopics.class));
    var event = org.hswebframework.web.bean.FastBeanCopier.copy(Map.of(
      "id", "alarm-history-id", "alarmRecordId", "authoritative-record",
      "targetType", "device", "targetId", "meter"), new CloudAlarmRecord());
    StepVerifier.create(service.refresh(event)).expectNext(1).verifyComplete();
    var param = org.mockito.ArgumentCaptor.forClass(org.hswebframework.web.api.crud.entity.QueryParamEntity.class);
    verify(devices).currentDeviceQuery(param.capture(), eq(Set.of("meter")));
    var terms = new HashMap<String, Object>();
    var pending = new ArrayDeque<>(param.getValue().getTerms());
    while (!pending.isEmpty()) {
      var term = pending.remove(); if (term.getColumn() != null) terms.put(term.getColumn(), term.getValue());
      if (term.getTerms() != null) pending.addAll(term.getTerms());
    }
    assertEquals(Set.of("meter"), terms.get("cloudDeviceId"));
    verify(writer).accept(row, record);
    // 解绑后当前目录不再返回设备；历史保留，不再写入新记录。
    when(query.fetch()).thenReturn(Flux.empty());
    StepVerifier.create(service.refresh(event)).expectNext(0).verifyComplete();
    verify(support, times(1)).execute(any(org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand.class));
    verifyNoMoreInteractions(writer);
    StepVerifier.create(service.refresh(new CloudAlarmRecord())).expectNext(0).verifyComplete();
    verify(devices, times(2)).currentDeviceQuery(any(), eq(Set.of("meter")));
  }

  @Test void catchupAdvancesOnlyAfterWritingAndStartsAtTheLastSuccessfulWindow() {
    var store = mock(DeviceStore.class);
    var cloud = mock(FacilityCloudAlarms.class);
    var writer = mock(FacilitySaasAlarmWriter.class);
    var row = device("a"); row.setCloudDeviceId("meter");
    long cursor = System.currentTimeMillis() - Duration.ofHours(3).toMillis();
    row.setSaasAlarmSyncedThrough(cursor);
    long from = cursor - Duration.ofMinutes(10).toMillis();
    long until = cursor + Duration.ofHours(1).toMillis();
    var cleared = signal("meter"); cleared.setAlarmTime(1L); cleared.setHandleTime(cursor + 1);
    cleared.setState("normal");
    when(cloud.changes("meter", from, until, false)).thenReturn(Mono.just(List.of(cleared)));
    when(writer.accept(row, cleared)).thenReturn(Mono.error(new IllegalStateException("write failed")), Mono.empty());
    when(store.advanceAlarmCursor(row, until)).thenReturn(Mono.just(1));
    var service = new FacilitySaasAlarms(store, mock(FacilityDevices.class), execution(), cloud, writer,
      mock(FacilityAlarmTopics.class));

    StepVerifier.create(service.reconcileDevice(row, true)).expectError(IllegalStateException.class).verify();
    verifyNoInteractions(store);
    StepVerifier.create(service.reconcileDevice(row, true)).expectNext(1).verifyComplete();
    verify(cloud, times(2)).changes("meter", from, until, false);
    verify(store).advanceAlarmCursor(row, until);
  }

  @Test void aFailedEventDoesNotStopLaterSignalsAndEachRestoresCurrentPermissions() {
    var execution = execution(); var cloud = mock(FacilityCloudAlarms.class); var topics = mock(FacilityAlarmTopics.class);
    var signals = Sinks.many().unicast().<CloudAlarmRecord>onBackpressureBuffer();
    when(topics.watch(anySet())).thenReturn(signals.asFlux());
    var service = spy(new FacilitySaasAlarms(mock(DeviceStore.class), mock(FacilityDevices.class), execution, cloud,
      mock(FacilitySaasAlarmWriter.class), topics));
    doReturn(Mono.just(0)).when(service).reconcile(any());
    var denied = signal("revoked"); var permitted = signal("permitted");
    doReturn(Mono.error(new org.hswebframework.web.authorization.exception.AccessDenyException())).when(service).refresh(denied);
    doReturn(Mono.just(1)).when(service).refresh(permitted);
    var row = device("a");
    StepVerifier.create(service.listen(row, Set.of("meter"))).expectNext(0)
      .then(() -> signals.tryEmitNext(denied))
      .then(() -> signals.tryEmitNext(permitted)).expectNext(1).thenCancel().verify();
    verify(execution, times(3)).asOwner(eq(row), any());
    assertEquals(1, service.getFailureCount());
    assertEquals(0, signals.currentSubscriberCount());
  }

  @Test void disconnectReestablishesTransportAndReconcilesBeforeContinuing() {
    var execution = execution(); var cloud = mock(FacilityCloudAlarms.class); var topics = mock(FacilityAlarmTopics.class);
    var streams = new AtomicInteger();
    var first = Sinks.many().unicast().<CloudAlarmRecord>onBackpressureBuffer();
    var second = Sinks.many().unicast().<CloudAlarmRecord>onBackpressureBuffer();
    when(topics.watch(anySet())).thenAnswer(call -> streams.incrementAndGet() == 1 ? first.asFlux() : second.asFlux());
    var service = spy(new FacilitySaasAlarms(mock(DeviceStore.class), mock(FacilityDevices.class), execution, cloud,
      mock(FacilitySaasAlarmWriter.class), topics));
    doReturn(Mono.just(0)).when(service).reconcile(any());
    StepVerifier.withVirtualTime(() -> service.listen(device("a"), Set.of("meter"))).expectNext(0)
      .then(() -> first.tryEmitError(new IllegalStateException("connection lost")))
      .thenAwait(Duration.ofSeconds(5)).expectNext(0).thenCancel().verify();
    assertEquals(2, streams.get()); verify(service, times(2)).reconcile(any());
    assertEquals(0, second.currentSubscriberCount());
  }

  @Test @SuppressWarnings("unchecked") void identityMaintenanceReusesStreamsRemovesOldGroupsAndStopCancelsAll() {
    var store = mock(DeviceStore.class);
    ReactiveQuery<DeviceEntity> query = mock(ReactiveQuery.class, RETURNS_SELF);
    when(store.createQuery()).thenReturn(query);
    var a = device("a"); var b = device("b");
    when(query.fetch()).thenReturn(Flux.just(a, b));
    var service = spy(new FacilitySaasAlarms(store, mock(FacilityDevices.class), execution(),
      mock(FacilityCloudAlarms.class), mock(FacilitySaasAlarmWriter.class), mock(FacilityAlarmTopics.class)));
    doReturn(Mono.just(Set.of("meter"))).when(service).subscriptionDevices();
    var aClosed = new AtomicInteger(); var bClosed = new AtomicInteger();
    doReturn(Flux.<Integer>never().doOnCancel(aClosed::incrementAndGet)).when(service).listen(a, Set.of("meter"));
    doReturn(Flux.<Integer>never().doOnCancel(bClosed::incrementAndGet)).when(service).listen(b, Set.of("meter"));
    org.springframework.test.util.ReflectionTestUtils.setField(service, "running", true);
    StepVerifier.create(service.maintain()).verifyComplete();
    StepVerifier.create(service.maintain()).verifyComplete();
    assertEquals(2, service.getSubscriptionCount()); verify(service, times(1)).listen(a, Set.of("meter"));
    when(query.fetch()).thenReturn(Flux.just(b));
    StepVerifier.create(service.maintain()).verifyComplete();
    assertEquals(1, aClosed.get()); assertEquals(1, service.getSubscriptionCount());
    service.stop(); assertEquals(1, bClosed.get()); assertEquals(0, service.getSubscriptionCount());
    StepVerifier.create(service.maintain()).verifyComplete(); assertEquals(0, service.getSubscriptionCount());
  }

  @SuppressWarnings("unchecked") static FacilityExecution execution() {
    var execution = mock(FacilityExecution.class);
    when(execution.asOwner(any(), any())).thenAnswer(call -> Mono.defer((Supplier<Mono<Object>>) call.getArgument(1)));
    return execution;
  }

  @Test @SuppressWarnings("unchecked") void bindingChangesReplaceExactTopicsAndPermissionRevocationClosesStream() {
    var store = mock(DeviceStore.class);
    ReactiveQuery<DeviceEntity> query = mock(ReactiveQuery.class, RETURNS_SELF);
    when(store.createQuery()).thenReturn(query);
    var row = device("a"); when(query.fetch()).thenReturn(Flux.just(row));
    var service = spy(new FacilitySaasAlarms(store, mock(FacilityDevices.class), execution(),
      mock(FacilityCloudAlarms.class), mock(FacilitySaasAlarmWriter.class), mock(FacilityAlarmTopics.class)));
    var closed = new AtomicInteger();
    doReturn(Flux.<Integer>never().doOnCancel(closed::incrementAndGet)).when(service).listen(eq(row), anySet());
    org.springframework.test.util.ReflectionTestUtils.setField(service, "running", true);
    try {
      doReturn(Mono.just(Set.of("old"))).when(service).subscriptionDevices();
      StepVerifier.create(service.maintain()).verifyComplete();
      doReturn(Mono.just(Set.of("new"))).when(service).subscriptionDevices();
      StepVerifier.create(service.maintain()).verifyComplete();
      verify(service).listen(row, Set.of("old")); verify(service).listen(row, Set.of("new"));
      assertEquals(1, closed.get()); assertEquals(1, service.getSubscriptionCount());
      doReturn(Mono.error(new org.hswebframework.web.authorization.exception.AccessDenyException()))
        .when(service).subscriptionDevices();
      StepVerifier.create(service.maintain()).verifyComplete();
      assertEquals(2, closed.get()); assertEquals(0, service.getSubscriptionCount());
      assertEquals(1, service.getFailureCount());
    } finally { service.stop(); }
  }

  static CloudAlarmRecord signal(String deviceId) {
    var signal = new CloudAlarmRecord(); signal.setTargetType("device"); signal.setTargetId(deviceId); return signal;
  }

  @Test @SuppressWarnings("unchecked") void differentEventNamespacesKeepOnlyTheExistingPollingJob() {
    try (var schedulers = mockConstruction(ClusterPluginScheduler.class, (mock, context) ->
      when(mock.interval(anyString(), any(), any(Duration.class), anyBoolean()))
        .thenAnswer(call -> reactor.core.Disposables.single()))) {
      var bus = mock(EventBus.class);
      var topics = new FacilityAlarmTopics(bus, "_default", "jetlinks-smart-park");
      var service = spy(new FacilitySaasAlarms(mock(DeviceStore.class), mock(FacilityDevices.class), execution(),
        mock(FacilityCloudAlarms.class), mock(FacilitySaasAlarmWriter.class), topics));
      doReturn(Mono.empty()).when(service).poll();
      try {
        service.start();
        org.mockito.ArgumentCaptor<Mono<Void>> pollTask = org.mockito.ArgumentCaptor.forClass(Mono.class);
        var scheduler = schedulers.constructed().get(0);
        verify(scheduler).interval(eq("facility-saas-alarms"), pollTask.capture(), eq(Duration.ofMinutes(5)), eq(true));
        verifyNoMoreInteractions(scheduler);
        StepVerifier.create(pollTask.getValue()).verifyComplete();
        verify(service).poll();
        verifyNoInteractions(bus);
      } finally {
        service.stop();
      }
    }
  }

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
      mock(FacilityCloudAlarms.class), mock(FacilitySaasAlarmWriter.class), mock(FacilityAlarmTopics.class));
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
      mock(FacilityCloudAlarms.class), mock(FacilitySaasAlarmWriter.class), mock(FacilityAlarmTopics.class));
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
