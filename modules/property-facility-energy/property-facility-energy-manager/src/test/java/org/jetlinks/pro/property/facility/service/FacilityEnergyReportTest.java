package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.property.facility.model.MetricMapping;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

class FacilityEnergyReportTest {
  final MappingStore mappings = mock(MappingStore.class);
  final ReadingStore store = mock(ReadingStore.class);
  final FacilityScope scope = mock(FacilityScope.class);
  final FacilityRecords records = mock(FacilityRecords.class);
  final FacilityReadings readings = new FacilityReadings(null, store, mappings, null, null, null, scope, records);
  final FacilityReports reports = new FacilityReports(null, null, null, null, null, mappings, null, scope, readings, records, null);
  final FacilityContext context = new FacilityContext("tenant", "app", "worker", "员工");

  @Test
  void slowerFirstMeterDoesNotReorderReportRows() {
    var first = mapping(0); var second = mapping(1);
    bind(List.of(first, second));
    var reader = mock(FacilityReadings.class);
    var usage = new EnergyMath.Usage(BigDecimal.ONE, true, 2, 0, 1000L, 2000L);
    when(reader.usageForVisibleMapping(first, "total", 1000, 2000))
      .thenAnswer(call -> Mono.delay(Duration.ofMillis(100)).thenReturn(usage));
    when(reader.usageForVisibleMapping(second, "total", 1000, 2000))
      .thenReturn(Mono.just(usage));
    var report = new FacilityReports(null, null, null, null, null, mappings, null, scope, reader, records, null);
    StepVerifier.withVirtualTime(() -> report.energy("project", 1000, 2000, "master"))
      .thenAwait(Duration.ofMillis(100))
      .assertNext(rows -> assertEquals(List.of("meter-0", "meter-1"), rows.stream().map(row -> row.get("deviceId")).toList()))
      .verifyComplete();
  }

  @Test
  void callerCancellationStopsAllInFlightMetersAndDoesNotReadRemainingOnes() {
    bind(java.util.stream.IntStream.range(0, 8).mapToObj(this::mapping).toList());
    var reader = mock(FacilityReadings.class);
    var started = new AtomicInteger(); var cancelled = new AtomicInteger();
    when(reader.usageForVisibleMapping(any(), eq("total"), eq(1000L), eq(2000L)))
      .thenAnswer(call -> Mono.<EnergyMath.Usage>never()
        .doOnSubscribe(ignored -> started.incrementAndGet()).doOnCancel(cancelled::incrementAndGet));
    var report = new FacilityReports(null, null, null, null, null, mappings, null, scope, reader, records, null);
    StepVerifier.create(report.energy("project", 1000, 2000, "master"))
      .then(() -> assertEquals(4, started.get())).thenCancel().verify();
    assertEquals(4, cancelled.get());
    verify(reader, times(4)).usageForVisibleMapping(any(), eq("total"), eq(1000L), eq(2000L));
  }

  @Test
  void emptyOrUnmatchedMetersDoNotReadHistory() {
    bind(List.of());
    StepVerifier.create(reports.energy("project", 1000, 2000, "master"))
      .assertNext(rows -> assertTrue(rows.isEmpty())).verifyComplete();
    var submeter = mapping(0); submeter.setMeterLevel("submeter");
    bind(List.of(submeter));
    StepVerifier.create(reports.energy("project", 1000, 2000, "master"))
      .assertNext(rows -> assertTrue(rows.isEmpty())).verifyComplete();
    verifyNoInteractions(store);
  }

  @Test
  void authorizationFailureDoesNotReadHistory() {
    bind(List.of(mapping(0)));
    when(scope.query(any(), eq(FacilityAssetType.facilityMapping), eq("id")))
      .thenReturn(Mono.error(new AccessDenyException()));
    StepVerifier.create(reports.energy("project", 1000, 2000, "master"))
      .expectError(AccessDenyException.class).verify();
    verifyNoInteractions(store);
  }

  @Test
  void independentReadingEntryStillRequiresRecordPermission() {
    var mapping = mapping(0);
    when(records.read(mappings, mapping.getId(), FacilityAssetType.facilityMapping, CrudAssetPermission.read))
      .thenReturn(Mono.error(new AccessDenyException()));
    when(scope.current()).thenReturn(Mono.just(context));
    StepVerifier.create(readings.usageFor(mapping, "total", 1000, 2000))
      .expectError(AccessDenyException.class).verify();
    verifyNoInteractions(store);
  }

  @Test
  void historyFailureAndOverflowAreNotConvertedToZeroOrPartialTotals() {
    bind(List.of(mapping(0)));
    @SuppressWarnings("unchecked")
    ReactiveQuery<ReadingEntity> query = mock(ReactiveQuery.class, RETURNS_SELF);
    when(store.createQuery()).thenReturn(query);
    when(query.fetchOne()).thenReturn(Mono.empty());
    when(query.fetch()).thenReturn(Flux.error(new IllegalStateException("history unavailable")));
    StepVerifier.create(reports.energy("project", 1000, 2000, "master"))
      .expectErrorMessage("history unavailable").verify();
    when(query.fetch()).thenReturn(Flux.fromIterable(Collections.nCopies(50001, reading(1000, 1))));
    StepVerifier.create(reports.energy("project", 1000, 2000, "master"))
      .expectErrorMessage("error.facility.too_many").verify();
  }

  @Test
  void tooManyMappingsFailsBeforeReadingHistory() {
    bind(Collections.nCopies(20001, mapping(0)));
    StepVerifier.create(reports.energy("project", 1000, 2000, "master"))
      .expectErrorMessage("error.facility.too_many").verify();
    verifyNoInteractions(store);
  }

  @Test
  void boundedParallelReadsKeepOrderContextAndUsageWithoutReloadingEachMapping() {
    var meters = java.util.stream.IntStream.range(0, 8).mapToObj(this::mapping).toList();
    bind(meters);
    var active = new AtomicInteger();
    var maximum = new AtomicInteger();
    when(store.createQuery()).thenAnswer(call -> {
      @SuppressWarnings("unchecked")
      ReactiveQuery<ReadingEntity> query = mock(ReactiveQuery.class, RETURNS_SELF);
      when(query.fetch()).thenReturn(delayed(List.of(reading(1000, 10), reading(2000, 12)), active, maximum).flatMapMany(Flux::fromIterable));
      when(query.fetchOne()).thenReturn(delayed(Optional.<ReadingEntity>empty(), active, maximum).flatMap(Mono::justOrEmpty));
      return query;
    });
    var elapsed = new AtomicLong();
    StepVerifier.withVirtualTime(() -> reports.energy("project", 1000, 2000, "master")
        .doOnNext(rows -> elapsed.set(VirtualTimeScheduler.get().now(TimeUnit.MILLISECONDS)))
        .contextWrite(c -> c.put(FacilityContext.class, context)))
      .thenAwait(Duration.ofSeconds(1))
      .assertNext(rows -> {
        assertEquals(8, rows.size());
        for (int i = 0; i < rows.size(); i++) {
          assertEquals("meter-" + i, rows.get(i).get("deviceId"));
          assertEquals("total", rows.get(i).get("propertyId"));
          assertEquals("kWh", rows.get(i).get("unit"));
          var usage = (EnergyMath.Usage) rows.get(i).get("usage");
          assertEquals(0, BigDecimal.valueOf(2).compareTo(usage.value()));
          assertTrue(usage.complete());
        }
      }).verifyComplete();
    // Eight meters, two reads per meter: at most four meters run together, in two 100ms batches.
    assertEquals(200, elapsed.get());
    assertEquals(8, maximum.get());
    assertEquals(0, active.get());
    verifyNoInteractions(records);
    verify(mappings, times(1)).createQuery();
  }

  private <T> Mono<T> delayed(T value, AtomicInteger active, AtomicInteger maximum) {
    return Mono.deferContextual(c -> {
      assertEquals(context, c.get(FacilityContext.class));
      maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
      return Mono.delay(Duration.ofMillis(100)).map(ignored -> value)
        .doOnSuccess(ignored -> active.decrementAndGet());
    });
  }

  void bind(List<MappingEntity> meters) {
    @SuppressWarnings("unchecked")
    ReactiveQuery<MappingEntity> query = mock(ReactiveQuery.class, RETURNS_SELF);
    when(mappings.createQuery()).thenReturn(query);
    when(query.fetch()).thenReturn(Flux.fromIterable(meters));
    when(scope.current()).thenReturn(Mono.deferContextual(c -> Mono.just(c.get(FacilityContext.class))));
    when(scope.query(any(), any(), eq("id"))).thenAnswer(call -> Mono.just(call.getArgument(0)));
    when(records.read(eq(mappings), anyString(), any(), any())).thenAnswer(call ->
      Mono.just(meters.stream().filter(m -> m.getId().equals(call.getArgument(1))).findFirst().orElseThrow()));
  }

  MappingEntity mapping(int i) {
    var mapping = FacilityScope.initialize(new MappingEntity(), context);
    mapping.setId("mapping-" + i); mapping.setDeviceId("meter-" + i); mapping.setProjectId("project");
    mapping.setName("电表" + i); mapping.setMeterLevel("master"); mapping.setEnergyType("electricity");
    mapping.setUsagePropertyId("total"); mapping.setStaleAfterSeconds(60L);
    var metric = new MetricMapping();
    metric.setPropertyId("total"); metric.setKind("counter"); metric.setUnit("kWh");
    var secondary = new MetricMapping();
    secondary.setPropertyId("secondary"); secondary.setKind("counter"); secondary.setUnit("kWh");
    mapping.setMetrics(List.of(metric, secondary));
    return mapping;
  }

  ReadingEntity reading(long time, int value) {
    var reading = new ReadingEntity();
    reading.setDeviceId("meter"); reading.setPropertyId("total"); reading.setMetricKind("counter");
    reading.setStatus("accepted"); reading.setValue(BigDecimal.valueOf(value));
    reading.setObservedTime(time); reading.setCreateTime(time); reading.setMappingVersion(0L); reading.setMeterEpoch(0L);
    return reading;
  }
}
