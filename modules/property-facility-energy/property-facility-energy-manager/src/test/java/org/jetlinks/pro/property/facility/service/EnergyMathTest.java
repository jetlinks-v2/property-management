package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.*;
import org.junit.jupiter.api.Test;

class EnergyMathTest {

  @Test
  void stateCodesIgnoreNumericScaleButKeepTextSemantics() {
    assertTrue(EnergyMath.sameState("1", "1.0000"));
    assertTrue(EnergyMath.sameState("0.000", "0"));
    assertTrue(EnergyMath.sameState("-2", "-2.0000"));
    assertTrue(EnergyMath.sameState("fault", "fault"));
    assertTrue(EnergyMath.sameState("true", "true"));
    assertFalse(EnergyMath.sameState("1", "10.0000"));
    assertFalse(EnergyMath.sameState("fault", "Fault"));
    assertFalse(EnergyMath.sameState("true", "1"));
    assertFalse(EnergyMath.sameState(null, null));
  }

  private ReadingEntity row(long time, String value, String kind) {
    var r = new ReadingEntity();
    r.setId("r" + time);
    r.setDeviceId("meter");
    r.setPropertyId("reading");
    r.setObservedTime(time);
    r.setCreateTime(time);
    r.setValue(new BigDecimal(value));
    r.setStatus("accepted");
    r.setMetricKind(kind);
    r.setMappingVersion(0L);
    r.setMeterEpoch(0L);
    return r;
  }

  @Test
  void counterSortsDeduplicatesAndKeepsBoundaryCompleteness() {
    var first = row(1000, "10", "counter");
    var last = row(3000, "19", "counter");
    var usage = EnergyMath.usage(
      List.of(last, first, row(2000, "14", "counter"), last),
      1000,
      3000,
      10
    );
    assertEquals(0, new BigDecimal("9").compareTo(usage.value()));
    assertTrue(usage.complete());
    assertEquals(3, usage.accepted());
  }

  @Test
  void doesNotChargePrePeriodDelta() {
    var usage = EnergyMath.usage(
      List.of(
        row(0, "0", "counter"),
        row(2000, "12", "counter"),
        row(3000, "15", "counter")
      ),
      1000,
      3000,
      10
    );
    assertEquals(0, new BigDecimal("3").compareTo(usage.value()));
    assertFalse(usage.complete());
  }

  @Test
  void resetAndRollbackDoNotProduceConsumption() {
    var reset = row(3000, "1", "counter");
    reset.setMeterEpoch(3000L);
    var after = row(4000, "4", "counter");
    after.setMeterEpoch(3000L);
    var usage = EnergyMath.usage(
      List.of(
        row(1000, "100", "counter"),
        row(2000, "90", "counter"),
        reset,
        after
      ),
      1000,
      4000,
      10
    );
    assertEquals(0, new BigDecimal("3").compareTo(usage.value()));
    assertEquals(1, usage.anomalies());
    assertFalse(usage.complete());
  }

  @Test
  void rollbackDoesNotCreateInflatedRecoveryConsumption() {
    var usage = EnergyMath.usage(
      List.of(
        row(1000, "100", "counter"),
        row(2000, "10", "counter"),
        row(3000, "110", "counter")
      ),
      1000,
      3000,
      10
    );
    assertEquals(0, new BigDecimal("10").compareTo(usage.value()));
    assertFalse(usage.complete());
    assertEquals(1, usage.anomalies());
  }

  @Test
  void incrementIsNotCumulativeAndEndIsExclusive() {
    var usage = EnergyMath.usage(
      List.of(
        row(1000, "3", "increment"),
        row(2000, "7", "increment"),
        row(3000, "99", "increment")
      ),
      1000,
      3000,
      10
    );
    assertEquals(0, new BigDecimal("10").compareTo(usage.value()));
    assertTrue(usage.complete());
  }

  @Test
  void instantBalanceAndStateNeverBecomeUsage() {
    for (String kind : List.of("instant", "balance", "state")) {
      var usage = EnergyMath.usage(
        List.of(row(1000, "20", kind), row(2000, "30", kind)),
        1000,
        2000,
        10
      );
      assertEquals(0, usage.value().signum());
      assertFalse(usage.complete());
    }
  }

  @Test
  void missingBoundaryOrLargeGapIsNotZeroOrComplete() {
    var usage = EnergyMath.usage(
      List.of(row(1000, "4", "counter"), row(20000, "8", "counter")),
      1000,
      21000,
      1
    );
    assertEquals(0, new BigDecimal("4").compareTo(usage.value()));
    assertFalse(usage.complete());
    assertFalse(EnergyMath.usage(List.of(), 1000, 2000, 10).complete());
  }

  @Test
  void appliesMultiplierExactlyOnce() {
    var metric = new MetricMapping();
    metric.setMultiplier(new BigDecimal("40"));
    metric.setScale(2);
    assertEquals(
      new BigDecimal("49.20"),
      EnergyMath.normalize(new BigDecimal("1.23"), metric)
    );
  }

  @Test
  void realBalanceIsOppositeOfUsageThreshold() {
    assertEquals(
      "critical",
      EnergyMath.level(
        "balance",
        new BigDecimal("5"),
        new BigDecimal("20"),
        new BigDecimal("10"),
        null
      )
    );
    assertNull(
      EnergyMath.level(
        "balance",
        new BigDecimal("25"),
        new BigDecimal("20"),
        null,
        null
      )
    );
    assertEquals(
      "near",
      EnergyMath.level(
        "usage",
        new BigDecimal("80"),
        new BigDecimal("100"),
        new BigDecimal("150"),
        new BigDecimal("0.8")
      )
    );
    assertEquals(
      "warning",
      EnergyMath.level(
        "usage",
        new BigDecimal("100"),
        new BigDecimal("100"),
        null,
        null
      )
    );
  }
}
