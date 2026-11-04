package org.jetlinks.pro.patrol.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 巡更频次契约。日期覆盖即使为空也覆盖星期规则；时间采用业务时区，不接受客户端时区。 */
public record PatrolRule(
        Map<Integer, List<Window>> weekly,
        Map<LocalDate, List<Window>> dates,
        int cooldownMinutes) {
    /** 分钟值范围 start=[0,1439], end=[1,1440]；end 小于 start 表示跨天，等值不表示全天。 */
    public record Window(int startMinute, int endMinute, int intervalMinutes, int requiredCount) {}
}
