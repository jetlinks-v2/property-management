package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import org.jetlinks.pro.property.facility.entity.*;
import org.junit.jupiter.api.Test;

class FacilityReportMathTest {
  private static final long FROM = 1793462400000L, TO = FROM + 86400000, NOW = FROM + 43200000;

  @Test
  void workCountsMainTasksAndDoesNotDiluteDueRateWithFutureCancelledOrClosedTasks() {
    var onTime = task("completed", NOW - 1000, NOW - 2000);
    var late = task("completed", NOW - 3000, NOW - 1000);
    var pending = task("review", NOW - 1000, null);
    var future = task("open", NOW + 1000, null);
    var child = task("completed", NOW - 1000, NOW - 2000); child.setParentTaskId("parent");
    var result = FacilityReportMath.work(List.of(onTime, late, pending, future, child,
      task("closed", NOW - 1000, null), task("cancelled", NOW - 1000, null)), List.of(), FROM, TO, NOW);
    assertEquals(6, result.get("total")); assertEquals(2L, result.get("completed"));
    assertEquals(3, result.get("due")); assertEquals(new BigDecimal("33.33"), result.get("onTimeRate"));
    assertEquals(1L, result.get("overdue")); assertEquals(1L, result.get("closed")); assertEquals(1L, result.get("cancelled"));
    assertNull(FacilityReportMath.work(List.of(future), List.of(), FROM, TO, NOW).get("onTimeRate"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void faultClosureTrendIncludesPriorPeriodButCohortRateExcludesFalseReports() {
    var prior = fault("closed", FROM - 7200000, FROM + 3600000);
    var current = fault("closed", FROM + 3600000, FROM + 7200000);
    var pending = fault("review", FROM + 1000, null);
    var falseReport = fault("cancelled", FROM + 1000, FROM + 2000);
    var cancelled = fault("cancelled", FROM + 1000, null);
    var result = FacilityReportMath.faults(List.of(prior, current, pending, falseReport, cancelled), FROM, TO, NOW);
    assertEquals(2, result.get("total")); assertEquals(1L, result.get("completed")); assertEquals(1, result.get("outstanding"));
    assertEquals(new BigDecimal("50.00"), result.get("completionRate"));
    assertEquals(new BigDecimal("2.00"), result.get("averageClosureHours"));
    var trend = (List<Map<String, Object>>) result.get("trend");
    assertEquals(2L, trend.get(0).get("created")); assertEquals(2L, trend.get(0).get("completed"));
  }

  @Test
  void alertsCountEpisodesNotOccurrencesAndRecoveryIsSeparateFromProcessing() {
    var first = event("old", "critical", "pending", true); first.setOccurrences(100L);
    var second = event("new", "warning", "confirmed", false); second.setOccurrences(50L);
    var result = FacilityReportMath.alerts(List.of(first, second), FROM, TO, NOW);
    assertEquals(2, result.get("total")); assertEquals(1L, result.get("critical"));
    assertEquals(1L, result.get("pending")); assertEquals(1L, result.get("recovered"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void energyDistinguishesMissingDataZeroBaselineAndUnitMismatch() {
    var current = meter("one", "kWh", "12", true, 2);
    var zero = meter("one", "kWh", "0", true, 2);
    var historical = meter("one", "kWh", "10", true, 2);
    var result = FacilityReportEnergyMath.analysis(List.of(current), List.of(historical), List.of(), "electricity", FROM, TO);
    var summary = (Map<String, Object>) result.get("summary");
    assertEquals(new BigDecimal("20.00"), summary.get("previousRate"));
    assertEquals(new BigDecimal("12.000"), summary.get("dailyAverage"));
    result = FacilityReportEnergyMath.analysis(List.of(current), List.of(zero), List.of(), "electricity", FROM, TO);
    assertNull(((Map<String, Object>) result.get("summary")).get("previousRate"));
    assertNull(FacilityReportEnergyMath.total(List.of(meter("one", "m³", "12", true, 2)), "kWh").get("value"));
    assertNull(FacilityReportEnergyMath.total(List.of(meter("one", "kWh", "0", false, 0)), "kWh").get("value"));
    assertEquals(BigDecimal.ZERO, FacilityReportEnergyMath.total(List.of(zero), "kWh").get("value"));
  }

  private static TaskEntity task(String state, long deadline, Long completed) {
    var task = new TaskEntity(); task.setState(state); task.setScheduledTime(FROM); task.setDeadline(deadline); task.setCompletedTime(completed); return task;
  }
  private static FaultEntity fault(String state, long created, Long completed) {
    var fault = new FaultEntity(); fault.setState(state); fault.setCreateTime(created); fault.setCompletedTime(completed); fault.setDeviceId("device"); fault.setDeviceName("Pump"); return fault;
  }
  private static AlertEpisodeEntity event(String id, String level, String processing, boolean recovered) {
    var event = new AlertEpisodeEntity(); event.setId(id); event.setFirstTime(FROM); event.setLevel(level); event.setProcessingState(processing);
    event.setState(recovered ? "recovered" : "pending"); if (recovered) event.setRecoveredTime(NOW); event.setRuleType("threshold"); return event;
  }
  private static Map<String, Object> meter(String id, String unit, String value, boolean complete, int accepted) {
    return Map.of("deviceId", id, "propertyId", "usage", "unit", unit, "usage", new EnergyMath.Usage(new BigDecimal(value), complete, accepted, 0, FROM, TO));
  }
}
