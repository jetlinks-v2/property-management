package org.jetlinks.pro.patrol.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 巡更频次契约。日期覆盖即使为空也覆盖星期规则；时间采用业务时区，不接受客户端时区。 */
public record PatrolRule(
        Map<Integer, List<Window>> weekly,
        Map<LocalDate, List<Window>> dates,
        int cooldownMinutes) {
    /** 一个配置时段。单位只保留录入表达，计算统一用分钟；未配置独立间隔的历史规则沿用全局值。 */
    public record Window(
            int startMinute, int endMinute, int intervalMinutes, int requiredCount,
            Integer cooldownMinutes, String frequencyUnit, String cooldownUnit) {
        public Window(int startMinute, int endMinute, int intervalMinutes, int requiredCount) {
            this(startMinute, endMinute, intervalMinutes, requiredCount, null, null, null);
        }

        public int cooldown(PatrolRule rule) {
            return cooldownMinutes == null ? rule.cooldownMinutes() : cooldownMinutes;
        }
    }
}
