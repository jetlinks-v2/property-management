package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import java.math.*;
import java.util.*;
import org.jetlinks.pro.property.facility.entity.ReadingEntity;
import org.jetlinks.pro.property.facility.model.MetricMapping;

/** 能源数值语义：倍率仅在标准化时应用；统计不累加瞬时量、状态或余额。 */
public final class EnergyMath {

  private EnergyMath() {}

  /** 数字状态码忽略 Cloud 历史存储的小数位格式；文字状态仍区分大小写精确匹配。 */
  public static boolean sameState(String expected, String actual) {
    if (expected == null || actual == null) return false;
    if (expected.equals(actual)) return true;
    try {
      return new BigDecimal(expected).compareTo(new BigDecimal(actual)) == 0;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  public static BigDecimal normalize(BigDecimal raw, MetricMapping metric) {
    require(
      metric.getMultiplier() != null && metric.getMultiplier().signum() > 0,
      "invalid_mapping"
    );
    int scale = metric.getScale() == null ? 6 : metric.getScale();
    require(scale >= 0 && scale <= 12, "invalid_mapping");
    return raw
      .multiply(metric.getMultiplier())
      .setScale(scale, RoundingMode.HALF_UP);
  }

  public record Usage(
    BigDecimal value,
    boolean complete,
    int accepted,
    int anomalies,
    Long firstTime,
    Long lastTime
  ) {}

  public static Usage usage(
    List<ReadingEntity> source,
    long from,
    long to,
    long staleSeconds
  ) {
    // Count only complete counter intervals inside the requested period. A sample before
    // the boundary cannot allocate its entire delta to this period.
    var sorted = source
      .stream()
      .filter(
        r ->
          "accepted".equals(r.getStatus()) &&
          r.getValue() != null &&
          r.getObservedTime() <= to
      )
      .sorted(
        Comparator.comparing(ReadingEntity::getObservedTime).thenComparing(
          ReadingEntity::getCreateTime
        )
      )
      .toList();
    var unique = new LinkedHashMap<String, ReadingEntity>();
    for (var row : sorted)
      unique.put(
        row.getDeviceId() +
          ":" +
          row.getPropertyId() +
          ":" +
          row.getObservedTime(),
        row
      );
    BigDecimal sum = BigDecimal.ZERO;
    int accepted = 0,
      bad = 0;
    boolean complete = true;
    ReadingEntity previous = null;
    Long first = null,
      last = null;
    for (var row : unique.values()) {
      if (
        !Set.of("counter", "increment").contains(row.getMetricKind())
      ) continue;
      if (row.getObservedTime() < from) {
        previous = row;
        continue;
      }
      if (
        "increment".equals(row.getMetricKind()) && row.getObservedTime() == to
      ) continue;
      if (first == null) first = row.getObservedTime();
      last = row.getObservedTime();
      accepted++;
      if ("increment".equals(row.getMetricKind())) {
        if (row.getValue().signum() >= 0) sum = sum.add(row.getValue());
        else {
          bad++;
          complete = false;
        }
        if (
          previous == null
            ? row.getObservedTime() - from > staleSeconds * 1000
            : row.getObservedTime() - previous.getObservedTime() >
                staleSeconds * 1000
        ) complete = false;
      } else if (
        previous == null ||
        previous.getObservedTime() < from ||
        !Objects.equals(previous.getMeterEpoch(), row.getMeterEpoch()) ||
        !Objects.equals(previous.getMappingVersion(), row.getMappingVersion())
      ) {
        // A baseline exactly at the start is valid; every other missing/changed baseline is a gap.
        if (row.getObservedTime() != from) complete = false;
      } else {
        var difference = row.getValue().subtract(previous.getValue());
        if (difference.signum() < 0) {
          bad++;
          complete = false;
          // A rollback is not a new baseline. Wait for recovery above the last valid
          // reading or an explicitly approved replacement epoch.
          continue;
        } else sum = sum.add(difference);
        if (
          row.getObservedTime() - previous.getObservedTime() >
          staleSeconds * 1000
        ) complete = false;
      }
      previous = row;
    }
    if (first == null || last == null) complete = false;
    else if (
      previous != null && "counter".equals(previous.getMetricKind())
    ) complete &= first == from && last == to;
    else if (to - last > staleSeconds * 1000) complete = false;
    return new Usage(sum, complete, accepted, bad, first, last);
  }

  public static void mapping(List<MetricMapping> metrics) {
    require(
      metrics != null && !metrics.isEmpty() && metrics.size() <= 50,
      "invalid_mapping"
    );
    var keys = new HashSet<String>();
    for (var m : metrics) {
      require(
        m.getPropertyId() != null &&
          keys.add(m.getPropertyId()) &&
          Set.of(
            "counter",
            "increment",
            "instant",
            "balance",
            "state"
          ).contains(m.getKind()) &&
          m.getUnit() != null &&
          !m.getUnit().isBlank(),
        "invalid_mapping"
      );
      normalize(BigDecimal.ONE, m);
    }
  }

  public static String level(
    String type,
    BigDecimal value,
    BigDecimal threshold,
    BigDecimal critical,
    BigDecimal near
  ) {
    if ("balance".equals(type)) {
      if (critical != null && value.compareTo(critical) <= 0) return "critical";
      return value.compareTo(threshold) <= 0 ? "warning" : null;
    }
    if (critical != null && value.compareTo(critical) >= 0) return "critical";
    if (value.compareTo(threshold) >= 0) return "warning";
    return near != null && value.compareTo(threshold.multiply(near)) >= 0
      ? "near"
      : null;
  }
}
