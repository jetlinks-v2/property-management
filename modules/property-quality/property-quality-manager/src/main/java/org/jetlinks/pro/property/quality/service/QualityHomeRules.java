package org.jetlinks.pro.property.quality.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 保留旧首页的日分时、近七日、月内四段及百分比空分母规则；不执行查询。 */
final class QualityHomeRules {
    record Window(String label, long start, long end) {}

    static long at(LocalDateTime time) {
        return time.atZone(QualityScheduleRules.ZONE).toInstant().toEpochMilli();
    }

    static Double percent(long numerator, long denominator) {
        return denominator == 0 ? null : Math.round(numerator * 10000.0 / denominator) / 100.0;
    }

    static List<Window> windows(String mode, LocalDate today) {
        var windows = new ArrayList<Window>();
        if ("day".equals(mode)) {
            for (int hour = 8; hour < 20; hour += 2) {
                windows.add(new Window(String.format("%02d:00-%02d:00", hour, hour + 2),
                    at(today.atTime(hour, 0)), at(today.atTime(hour + 2, 0))));
            }
            windows.add(new Window("20:00-08:00", at(today.atTime(20, 0)), at(today.plusDays(1).atTime(8, 0))));
        } else if ("week".equals(mode)) {
            for (int i = 6; i >= 0; i--) {
                var day = today.minusDays(i);
                windows.add(new Window(day.toString(), at(day.atStartOfDay()), at(day.plusDays(1).atStartOfDay())));
            }
        } else {
            var first = today.withDayOfMonth(1);
            for (int i = 0; i < 4; i++) {
                var start = first.plusDays(i * 7L);
                var last = i == 3 ? first.plusMonths(1).minusDays(1) : start.plusDays(6);
                // 月内未来区间保留横轴，查询空窗口，不把尚未到期任务算入已发生趋势。
                var end = last.isAfter(today) ? today.plusDays(1) : last.plusDays(1);
                windows.add(new Window(start + "~" + last, at(start.atStartOfDay()), at(end.atStartOfDay())));
            }
        }
        return windows;
    }
}
