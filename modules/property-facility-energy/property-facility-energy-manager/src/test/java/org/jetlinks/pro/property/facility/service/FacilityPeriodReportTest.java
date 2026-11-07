package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.MetricMapping;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;

class FacilityPeriodReportTest {
  @Test
  void readsAuthorizedSeriesOnceAndPreservesPeriodBoundaries() {
    var mappings = mock(MappingStore.class);
    @SuppressWarnings("unchecked")
    var query = (org.hswebframework.ezorm.rdb.mapping.ReactiveQuery<MappingEntity>) mock(org.hswebframework.ezorm.rdb.mapping.ReactiveQuery.class, RETURNS_SELF);
    when(mappings.createQuery()).thenReturn(query);
    var scope = mock(FacilityScope.class);
    var readings = mock(FacilityReadings.class);
    var reports = new FacilityReports(null, null, null, null, null, mappings, null, scope, readings, null, null);
    var metric = new MetricMapping();
    metric.setPropertyId("total"); metric.setKind("counter"); metric.setUnit("kWh");
    var mapping = new MappingEntity();
    mapping.setId("mapping"); mapping.setDeviceId("meter"); mapping.setName("Meter");
    mapping.setProjectId("project"); mapping.setMeterLevel("master"); mapping.setEnergyType("electricity");
    mapping.setUsagePropertyId("total"); mapping.setStaleAfterSeconds(90000L); mapping.setMetrics(List.of(metric));
    when(scope.query(any(), any(), anyString())).thenAnswer(call -> Mono.just(call.getArgument(0)));
    when(query.fetch()).thenReturn(Flux.just(mapping));
    long first = LocalDate.of(2026, 11, 1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    var source = new ArrayList<ReadingEntity>();
    for (int i = 0; i <= 30; i++) {
      var row = new ReadingEntity();
      row.setDeviceId("meter"); row.setPropertyId("total"); row.setMetricKind("counter");
      row.setStatus("accepted"); row.setObservedTime(first + i * 86400000L); row.setCreateTime(row.getObservedTime());
      row.setValue(BigDecimal.valueOf(i * 2)); row.setMeterEpoch(0L); row.setMappingVersion(0L); source.add(row);
    }
    when(readings.seriesFor(eq(mapping), eq("total"), anyLong(), anyLong())).thenReturn(Mono.just(source));
    var result = reports.periods("project", "day", 2026, 11, "master").block();
    assertEquals(30, result.size());
    for (var period : result) {
      @SuppressWarnings("unchecked")
      var meters = (List<Map<String, Object>>) period.get("meters");
      var usage = (EnergyMath.Usage) meters.get(0).get("usage");
      assertEquals(0, BigDecimal.valueOf(2).compareTo(usage.value())); assertTrue(usage.complete());
    }
    verify(readings, times(1)).seriesFor(mapping, "total", first, first + 30 * 86400000L);
    // Full-range overflow must retain the existing per-period capacity instead of rejecting a valid month.
    when(readings.seriesFor(eq(mapping), eq("total"), anyLong(), anyLong()))
      .thenReturn(Mono.error(new BusinessException("error.facility.too_many", 400)));
    when(readings.usageFor(eq(mapping), eq("total"), anyLong(), anyLong())).thenAnswer(call ->
      Mono.just(EnergyMath.usage(source, call.getArgument(2), call.getArgument(3), 90000)));
    var fallback = reports.periods("project", "day", 2026, 11, "master").block();
    assertEquals(result, fallback);
    verify(readings, times(30)).usageFor(eq(mapping), eq("total"), anyLong(), anyLong());
  }
}
