package org.jetlinks.pro.property.facility.service;

import java.math.*;
import java.util.*;

/** 单一能源和标准单位聚合；无表、无有效读数与有效零用量分别表示。 */
final class FacilityReportEnergyMath {
  private FacilityReportEnergyMath() {}

  static Map<String, Object> total(List<Map<String, Object>> meters, String unit) {
    BigDecimal value = BigDecimal.ZERO;
    boolean complete = !meters.isEmpty(), unitsValid = true;
    int accepted = 0, anomalies = 0;
    for (var meter : meters) {
      var usage = (EnergyMath.Usage) meter.get("usage");
      if (!unit.equals(meter.get("unit"))) { complete = false; unitsValid = false; continue; }
      value = value.add(usage.value()); accepted += usage.accepted(); anomalies += usage.anomalies(); complete &= usage.complete();
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("value", accepted == 0 || !unitsValid ? null : value); result.put("complete", complete);
    result.put("meterCount", meters.size()); result.put("accepted", accepted); result.put("anomalies", anomalies);
    return result;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> analysis(List<Map<String, Object>> current, List<Map<String, Object>> previous,
    List<Map<String, Object>> periods, String energyType, long from, long to) {
    String unit = "electricity".equals(energyType) ? "kWh" : "m³";
    var summary = total(current, unit); var baseline = total(previous, unit);
    BigDecimal value = (BigDecimal) summary.get("value"), base = (BigDecimal) baseline.get("value");
    // 对比必须是相同仪表、指标和单位；缺表与缺历史都不能当作零。
    var keys = current.stream().map(FacilityReportEnergyMath::key).sorted().toList();
    boolean comparable = keys.equals(previous.stream().map(FacilityReportEnergyMath::key).sorted().toList()) &&
      Boolean.TRUE.equals(summary.get("complete")) && Boolean.TRUE.equals(baseline.get("complete")) && base != null && base.signum() != 0;
    summary.put("previousRate", comparable ? value.subtract(base).multiply(BigDecimal.valueOf(100)).divide(base, 2, RoundingMode.HALF_UP) : null);
    summary.put("dailyAverage", value == null ? null : value.multiply(BigDecimal.valueOf(86400000)).divide(BigDecimal.valueOf(to - from), 3, RoundingMode.HALF_UP));
    summary.put("unit", unit); summary.put("energyType", energyType);
    var trend = periods.stream().map(window -> {
      var row = total((List<Map<String, Object>>) window.get("meters"), unit); row.put("date", window.get("period"));
      row.put("from", window.get("from")); row.put("to", window.get("to")); return row;
    }).toList();
    var top = current.stream().filter(meter -> unit.equals(meter.get("unit")))
      .filter(meter -> ((EnergyMath.Usage) meter.get("usage")).accepted() > 0)
      .sorted(Comparator.comparing((Map<String, Object> meter) -> ((EnergyMath.Usage) meter.get("usage")).value()).reversed())
      .limit(5).toList();
    var result = new LinkedHashMap<String, Object>(); result.put("summary", summary); result.put("trend", trend); result.put("devices", top);
    return result;
  }

  private static String key(Map<String, Object> meter) {
    return meter.get("deviceId") + ":" + meter.get("propertyId") + ":" + meter.get("unit");
  }
}
