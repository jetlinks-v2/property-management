package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.core.event.EventBus;
import org.jetlinks.pro.plugin.context.ClusterPluginScheduler;
import org.jetlinks.pro.property.facility.entity.AlertEntity;
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
    var service = new FacilitySaasAlarms(store, devices, mock(FacilityExecution.class), cloud, writer, mock(FacilityAlarmTopics.class), mock(AlertStore.class));
    var event = org.hswebframework.web.bean.FastBeanCopier.copy(Map.of(
      "id", "alarm-history-id", "alarmRecordId", "authoritative-record",
      "targetType", "device", "targetId", "meter", "sourceType", "device", "sourceId", "other"), new CloudAlarmRecord());
    StepVerifier.create(service.refresh(event, Set.of("meter"))).expectNext(1).verifyComplete();
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
    StepVerifier.create(service.refresh(event, Set.of("meter"))).expectNext(0).verifyComplete();
    verify(support, times(1)).execute(any(org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand.class));
    verifyNoMoreInteractions(writer);
    StepVerifier.create(service.refresh(event, Set.of("unrelated"))).expectNext(0).verifyComplete();
    StepVerifier.create(service.refresh(new CloudAlarmRecord(), Set.of("meter"))).expectNext(0).verifyComplete();
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
      mock(FacilityAlarmTopics.class), mock(AlertStore.class));

    StepVerifier.create(service.reconcileDevice(row, true)).expectError(IllegalStateException.class).verify();
    verifyNoInteractions(store);
    StepVerifier.create(service.reconcileDevice(row, true)).expectNext(1).verifyComplete();
    verify(cloud, times(2)).changes("meter", from, until, false);
    verify(store).advanceAlarmCursor(row, until);
  }

  @Test @SuppressWarnings("unchecked")
  void initialCatchupChecksOldOpenRecordsAndPreservesCursorOnSourceFailure() {
    var store = mock(DeviceStore.class);
    var cloud = mock(FacilityCloudAlarms.class);
    var writer = mock(FacilitySaasAlarmWriter.class);
    var alerts = mock(AlertStore.class);
    ReactiveQuery<AlertEntity> query = mock(ReactiveQuery.class, RETURNS_SELF);
    when(alerts.createQuery()).thenReturn(query);
    var row = device("a"); row.setCloudDeviceId("meter");
    var open = new AlertEntity(); open.setSourceRecordId("old-record");
    when(query.fetch()).thenReturn(Flux.just(open));
    var cleared = signal("meter");
    cleared.setId("old-record"); cleared.setState("normal");
    cleared.setAlarmTime(1L); cleared.setHandleTime(2L);
    when(cloud.changes(eq("meter"), anyLong(), anyLong(), eq(true))).thenReturn(Mono.just(List.of()));
    when(cloud.records("meter", List.of("old-record")))
      .thenReturn(Mono.error(new IllegalStateException("source unavailable")), Mono.just(List.of(cleared)));
    when(writer.accept(row, cleared)).thenReturn(Mono.empty());
    when(store.advanceAlarmCursor(eq(row), anyLong())).thenReturn(Mono.just(1));
    var service = new FacilitySaasAlarms(store, mock(FacilityDevices.class), execution(), cloud, writer,
      mock(FacilityAlarmTopics.class), alerts);

    StepVerifier.create(service.reconcileDevice(row, true)).expectError(IllegalStateException.class).verify();
    verifyNoInteractions(writer, store);
    StepVerifier.create(service.reconcileDevice(row, true)).expectNext(1).verifyComplete();
    var ordered = inOrder(writer, store);
    ordered.verify(writer).accept(row, cleared);
    ordered.verify(store).advanceAlarmCursor(eq(row), anyLong());
    verify(query, times(2)).and("state", "open");
  }

  @Test void aFailedEventDoesNotStopLaterSignalsAndEachRestoresCurrentPermissions() {
    var execution = execution(); var cloud = mock(FacilityCloudAlarms.class); var topics = mock(FacilityAlarmTopics.class);
    var signals = Sinks.many().unicast().<CloudAlarmRecord>onBackpressureBuffer();
    when(topics.watch(anySet())).thenReturn(signals.asFlux());
    var service = spy(new FacilitySaasAlarms(mock(DeviceStore.class), mock(FacilityDevices.class), execution, cloud,
      mock(FacilitySaasAlarmWriter.class), topics, mock(AlertStore.class)));
    doReturn(Mono.just(0)).when(service).reconcile(any(), anySet());
    var denied = signal("revoked"); var permitted = signal("permitted");
    doReturn(Mono.error(new org.hswebframework.web.authorization.exception.AccessDenyException())).when(service).refresh(denied, Set.of("meter"));
    doReturn(Mono.just(1)).when(service).refresh(permitted, Set.of("meter"));
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
      mock(FacilitySaasAlarmWriter.class), topics, mock(AlertStore.class)));
    doReturn(Mono.just(0)).when(service).reconcile(any(), anySet());
    StepVerifier.withVirtualTime(() -> service.listen(device("a"), Set.of("meter"))).expectNext(0)
      .then(() -> first.tryEmitError(new IllegalStateException("connection lost")))
      .thenAwait(Duration.ofSeconds(5)).expectNext(0).thenCancel().verify();
    assertEquals(2, streams.get()); verify(service, times(2)).reconcile(any(), eq(Set.of("meter")));
    assertEquals(0, second.currentSubscriberCount());
  }

  @Test @SuppressWarnings("unchecked") void identityMaintenanceReusesStreamsRemovesOldGroupsAndStopCancelsAll() {
    var store = mock(DeviceStore.class);
    ReactiveQuery<DeviceEntity> query = mock(ReactiveQuery.class, RETURNS_SELF);
    when(store.createQuery()).thenReturn(query);
    var a = device("a"); var b = device("b");
    when(query.fetch()).thenReturn(Flux.just(a, b));
    var devices = mock(FacilityDevices.class);
    when(devices.currentIds(any())).thenAnswer(call -> Mono.just(candidateIds(call.getArgument(0))));
    var service = spy(new FacilitySaasAlarms(store, devices, execution(),
      mock(FacilityCloudAlarms.class), mock(FacilitySaasAlarmWriter.class), mock(FacilityAlarmTopics.class), mock(AlertStore.class)));
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

  @Test @SuppressWarnings("unchecked") void overlappingIdentitiesUseOneSubscriptionPerDevice() {
    var store = mock(DeviceStore.class);
    ReactiveQuery<DeviceEntity> query = mock(ReactiveQuery.class, RETURNS_SELF);
    when(store.createQuery()).thenReturn(query);
    var first = device("app"); first.setCloudDeviceId("meter-1"); first.setCreatorId("owner-a");
    var second = device("app"); second.setId("second"); second.setCreatorId("owner-b"); second.setCloudDeviceId("meter-2");
    var third = device("app"); third.setId("third"); third.setCreatorId("owner-c"); third.setCloudDeviceId("meter-3");
    when(query.fetch()).thenReturn(Flux.just(first, second, third));
    var topics = mock(FacilityAlarmTopics.class);
    when(topics.watch(anySet())).thenReturn(Flux.never());
    var devices = mock(FacilityDevices.class);
    var requested = new ArrayList<Set<String>>();
    when(devices.currentIds(any())).thenAnswer(call -> {
      var ids = candidateIds(call.getArgument(0));
      requested.add(ids);
      return Mono.just(ids);
    });
    var service = spy(new FacilitySaasAlarms(store, devices, execution(),
      mock(FacilityCloudAlarms.class), mock(FacilitySaasAlarmWriter.class), topics, mock(AlertStore.class)));
    doReturn(Mono.just(0)).when(service).reconcile(any(), anySet());
    org.springframework.test.util.ReflectionTestUtils.setField(service, "running", true);
    try {
      StepVerifier.create(service.maintain()).verifyComplete();
      assertEquals(List.of(Set.of("app"), Set.of("second"), Set.of("third")), requested);
      verify(topics).watch(Set.of("meter-1"));
      verify(topics).watch(Set.of("meter-2"));
      verify(topics).watch(Set.of("meter-3"));
      verifyNoMoreInteractions(topics);
    } finally { service.stop(); }
  }

  @Test @SuppressWarnings("unchecked") void oneThousandDevicesDoNotRepeatBindingChecksForTenOwners() {
    var rows = java.util.stream.IntStream.range(0, 1000).mapToObj(index -> {
      var row = device("app");
      row.setId("local-" + index);
      row.setCloudDeviceId("meter-" + index);
      row.setCreatorId("owner-" + index / 100);
      return row;
    }).toList();
    var scan = mock(DeviceStore.class);
    ReactiveQuery<DeviceEntity> scanQuery = mock(ReactiveQuery.class, RETURNS_SELF);
    when(scan.createQuery()).thenReturn(scanQuery);
    when(scanQuery.fetch()).thenReturn(Flux.fromIterable(rows));
    var local = mock(DeviceStore.class);
    ReactiveQuery<DeviceEntity> candidateQuery = mock(ReactiveQuery.class, RETURNS_SELF);
    when(local.createQuery()).thenReturn(candidateQuery);
    when(candidateQuery.setParam(any())).thenAnswer(call -> {
      var ids = candidateIds(call.getArgument(0));
      doReturn(Flux.fromIterable(rows).filter(row -> ids.contains(row.getId()))).when(candidateQuery).fetch();
      return candidateQuery;
    });
    var scope = mock(FacilityScope.class);
    when(scope.query(any(), any(), anyString())).thenAnswer(call -> Mono.just(call.getArgument(0)));
    var cloud = mock(FacilityCloud.class);
    when(cloud.boundDeviceIds(anySet())).thenAnswer(call -> Mono.just(call.getArgument(0)));
    var devices = new FacilityDevices(null, null, null, null, local, scope, null, null, cloud);
    var topics = mock(FacilityAlarmTopics.class);
    when(topics.watch(anySet())).thenReturn(Flux.never());
    var service = spy(new FacilitySaasAlarms(scan, devices, execution(), mock(FacilityCloudAlarms.class),
      mock(FacilitySaasAlarmWriter.class), topics, mock(AlertStore.class)));
    doReturn(Mono.just(0)).when(service).reconcile(any(), anySet());
    org.springframework.test.util.ReflectionTestUtils.setField(service, "running", true);
    try {
      StepVerifier.create(service.maintain()).verifyComplete();
      var requests = org.mockito.ArgumentCaptor.forClass(Set.class);
      verify(cloud, times(10)).boundDeviceIds(requests.capture());
      assertTrue(requests.getAllValues().stream().allMatch(ids -> ids.size() == 100));
      var checked = new HashSet<String>();
      requests.getAllValues().forEach(checked::addAll);
      assertEquals(1000, checked.size());
    } finally { service.stop(); }
  }

  @Test @SuppressWarnings("unchecked") void revokedCreatorHandsOffOnlyUncoveredDevicesToAnAuthorizedIdentity() {
    var store = mock(DeviceStore.class);
    ReactiveQuery<DeviceEntity> query = mock(ReactiveQuery.class, RETURNS_SELF);
    when(store.createQuery()).thenReturn(query);
    var a = device("app"); a.setCloudDeviceId("one"); a.setCreatorId("a");
    var b = device("app"); b.setId("b"); b.setCloudDeviceId("two"); b.setCreatorId("b");
    when(query.fetch()).thenReturn(Flux.just(a, b));
    var devices = mock(FacilityDevices.class);
    var execution = mock(FacilityExecution.class);
    when(execution.asOwner(any(), any())).thenAnswer(call -> {
      DeviceEntity owner = call.getArgument(0);
      Supplier<Mono<Set<String>>> work = call.getArgument(1);
      return Mono.defer(work).contextWrite(ctx -> ctx.put("user", owner.getCreatorId()));
    });
    var requests = new ArrayList<String>();
    var revoked = new java.util.concurrent.atomic.AtomicBoolean();
    when(devices.currentIds(any())).thenAnswer(call -> {
      var ids = candidateIds(call.getArgument(0));
      return Mono.deferContextual(ctx -> {
        String user = ctx.get("user");
        requests.add(user + ":" + String.join(",", new TreeSet<>(ids)));
        return Mono.just(revoked.get() && "a".equals(user) ? Set.of() : ids);
      });
    });
    var service = spy(new FacilitySaasAlarms(store, devices, execution, mock(FacilityCloudAlarms.class),
      mock(FacilitySaasAlarmWriter.class), mock(FacilityAlarmTopics.class), mock(AlertStore.class)));
    var firstClosed = new AtomicInteger();
    doReturn(Flux.<Integer>never().doOnCancel(firstClosed::incrementAndGet)).when(service).listen(eq(a), anySet());
    doReturn(Flux.never()).when(service).listen(eq(b), anySet());
    org.springframework.test.util.ReflectionTestUtils.setField(service, "running", true);
    try {
      StepVerifier.create(service.maintain()).verifyComplete();
      assertEquals(List.of("a:app", "b:b"), requests);
      requests.clear(); revoked.set(true);
      StepVerifier.create(service.maintain()).verifyComplete();
      assertEquals(List.of("a:app", "b:b", "b:app"), requests);
      assertEquals(1, firstClosed.get());
      verify(service).listen(b, Set.of("one", "two"));
      assertEquals(1, service.getSubscriptionCount());
      requests.clear();
      StepVerifier.create(service.maintain()).verifyComplete();
      assertEquals(List.of("b:app,b"), requests);
      verify(service, times(1)).listen(b, Set.of("one", "two"));
    } finally { service.stop(); }
  }

  @SuppressWarnings("unchecked")
  private static Set<String> candidateIds(QueryParamEntity query) {
    var terms = new ArrayDeque<>(query.getTerms());
    while (!terms.isEmpty()) {
      var term = terms.removeFirst();
      if ("id".equals(term.getColumn())) return (Set<String>) term.getValue();
      if (term.getTerms() != null) terms.addAll(term.getTerms());
    }
    throw new AssertionError("Device ID condition missing");
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
    var devices = mock(FacilityDevices.class);
    var service = spy(new FacilitySaasAlarms(store, devices, execution(),
      mock(FacilityCloudAlarms.class), mock(FacilitySaasAlarmWriter.class), mock(FacilityAlarmTopics.class), mock(AlertStore.class)));
    var closed = new AtomicInteger();
    doReturn(Flux.<Integer>never().doOnCancel(closed::incrementAndGet)).when(service).listen(eq(row), anySet());
    org.springframework.test.util.ReflectionTestUtils.setField(service, "running", true);
    try {
      row.setCloudDeviceId("old");
      when(devices.currentIds(any())).thenReturn(Mono.just(Set.of(row.getId())));
      StepVerifier.create(service.maintain()).verifyComplete();
      row.setCloudDeviceId("new");
      StepVerifier.create(service.maintain()).verifyComplete();
      verify(service).listen(row, Set.of("old")); verify(service).listen(row, Set.of("new"));
      assertEquals(1, closed.get()); assertEquals(1, service.getSubscriptionCount());
      when(devices.currentIds(any())).thenReturn(Mono.error(new org.hswebframework.web.authorization.exception.AccessDenyException()));
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
        mock(FacilityCloudAlarms.class), mock(FacilitySaasAlarmWriter.class), topics, mock(AlertStore.class)));
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
    when(execution.asOwner(eq(second), any())).thenAnswer(call -> Mono.defer((Supplier<Mono<Object>>) call.getArgument(1)));
    var devices = mock(FacilityDevices.class);
    when(devices.currentIds(any())).thenReturn(Mono.just(Set.of("b")));
    when(devices.currentQuery(any(), any(), anyString())).thenReturn(Mono.just(new QueryParamEntity()));
    when(query.fetch()).thenReturn(Flux.just(first, second), Flux.empty());
    var service = new FacilitySaasAlarms(store, devices, execution,
      mock(FacilityCloudAlarms.class), mock(FacilitySaasAlarmWriter.class), mock(FacilityAlarmTopics.class), mock(AlertStore.class));
    StepVerifier.create(service.poll()).verifyComplete();
    verify(execution).asOwner(eq(first), any()); verify(execution, times(2)).asOwner(eq(second), any());
    assertEquals(1, service.getFailureCount()); assertFalse(service.isPolling());
    assertEquals("IllegalStateException", service.getLastError());
  }

  @Test @SuppressWarnings("unchecked") void cancellationAndScannerErrorsReleasePollingGate() {
    var store = mock(DeviceStore.class);
    ReactiveQuery<DeviceEntity> query = mock(ReactiveQuery.class, RETURNS_SELF);
    when(store.createQuery()).thenReturn(query); when(query.fetch()).thenReturn(Flux.never());
    var service = new FacilitySaasAlarms(store, mock(FacilityDevices.class), mock(FacilityExecution.class),
      mock(FacilityCloudAlarms.class), mock(FacilitySaasAlarmWriter.class), mock(FacilityAlarmTopics.class), mock(AlertStore.class));
    var subscription = service.poll().subscribe(); assertTrue(service.isPolling());
    StepVerifier.create(service.poll()).verifyComplete(); verify(store, times(1)).createQuery();
    subscription.dispose(); assertFalse(service.isPolling());
    when(query.fetch()).thenReturn(Flux.error(new IllegalStateException("database unavailable")));
    StepVerifier.create(service.poll()).expectError(IllegalStateException.class).verify();
    assertEquals(1, service.getFailureCount()); assertFalse(service.isPolling());
  }

  static DeviceEntity device(String app) {
    var row = new DeviceEntity(); row.setId(app); row.setTenantId("tenant");
    row.setApplicationId(app); row.setCreatorId("admin"); row.setProjectId("project"); row.setCloudDeviceId("meter"); return row;
  }
}
