package org.jetlinks.pro.property.facility.model;

import java.util.List;
import java.util.Map;

/** 周期计划的星期规则及日期覆盖；分钟均以Asia/Shanghai的逻辑日期起点计算。 */
public record CycleRule(Map<Integer, List<Window>> weekly, Map<String, List<Window>> dates) {
  /** 相等起止表示24小时；frequencyUnit/cooldownUnit仅保留用户录入单位。 */
  public record Window(int startMinute, int endMinute, int intervalMinutes, int requiredCount,
                       int cooldownMinutes, String frequencyUnit, String cooldownUnit,
                       Double warningMinutes, String warningUnit) {
    public Window(int start, int end, int interval, int count, int cooldown, String frequencyUnit, String cooldownUnit) {
      this(start, end, interval, count, cooldown, frequencyUnit, cooldownUnit, null, null);
    }
  }
}
