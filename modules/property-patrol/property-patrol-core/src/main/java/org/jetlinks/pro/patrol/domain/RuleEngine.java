package org.jetlinks.pro.patrol.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 无 I/O 的规则展开器；窗口左闭右开，不生成不足一个频率周期的尾段。 */
public final class RuleEngine {
    private RuleEngine() {}

    public record Occurrence(
            LocalDate businessDate, Instant start, Instant end, int requiredCount) {}

    public static List<Occurrence> expand(PatrolRule rule, LocalDate day, ZoneId zone) {
        validate(rule);
        List<Occurrence> result = new ArrayList<>();
        List<PatrolRule.Window> windows = windows(rule, day);
        List<long[]> spans = new ArrayList<>();
        for (var window : windows) {
            int end = endMinute(window);
            spans.add(new long[] {window.startMinute(), end});
            for (int minute = window.startMinute();
                    minute + window.intervalMinutes() <= end;
                    minute += window.intervalMinutes()) {
                result.add(
                        new Occurrence(
                                day,
                                day.atStartOfDay(zone).plusMinutes(minute).toInstant(),
                                day.atStartOfDay(zone)
                                        .plusMinutes(minute + window.intervalMinutes())
                                        .toInstant(),
                                window.requiredCount()));
            }
        }
        // 前日跨天尾部与当日窗口也不得重叠；指定日期按各自逻辑日期优先。
        for (var previous : windows(rule, day.minusDays(1))) {
            if (endMinute(previous) > 1440)
                spans.add(new long[] {previous.startMinute() - 1440, endMinute(previous) - 1440});
        }
        assertNoOverlap(spans);
        result.sort(Comparator.comparing(Occurrence::start));
        return List.copyOf(result);
    }

    public static void validate(PatrolRule rule) {
        if (rule == null
                || rule.weekly() == null
                || rule.dates() == null
                || rule.cooldownMinutes() < 0
                || rule.cooldownMinutes() > 1440
                || rule.weekly().size() > 7
                || rule.dates().size() > 366)
            throw new IllegalArgumentException("error.patrol.rule.invalid");
        rule.weekly()
                .forEach(
                        (weekday, windows) -> {
                            if (weekday == null || weekday < 1 || weekday > 7)
                                throw new IllegalArgumentException("error.patrol.rule.invalid");
                            validateWindows(windows);
                        });
        rule.dates()
                .forEach(
                        (date, windows) -> {
                            if (date == null)
                                throw new IllegalArgumentException("error.patrol.rule.invalid");
                            validateWindows(windows);
                        });
    }

    private static void validateWindows(List<PatrolRule.Window> windows) {
        if (windows == null || windows.size() > 24)
            throw new IllegalArgumentException("error.patrol.rule.invalid");
        List<long[]> spans = new ArrayList<>();
        for (var w : windows) {
            if (w == null
                    || w.startMinute() < 0
                    || w.startMinute() > 1439
                    || w.endMinute() < 1
                    || w.endMinute() > 1440
                    || w.startMinute() == w.endMinute()
                    || w.intervalMinutes() < 1
                    || w.intervalMinutes() > 1440
                    || w.requiredCount() < 1
                    || w.requiredCount() > 100)
                throw new IllegalArgumentException("error.patrol.rule.invalid");
            spans.add(new long[] {w.startMinute(), endMinute(w)});
        }
        assertNoOverlap(spans);
    }

    private static int endMinute(PatrolRule.Window w) {
        return w.endMinute() < w.startMinute() ? w.endMinute() + 1440 : w.endMinute();
    }

    private static List<PatrolRule.Window> windows(PatrolRule rule, LocalDate day) {
        return rule.dates().containsKey(day)
                ? rule.dates().get(day)
                : rule.weekly().getOrDefault(day.getDayOfWeek().getValue(), List.of());
    }

    private static void assertNoOverlap(List<long[]> spans) {
        spans.sort(Comparator.comparingLong(span -> span[0]));
        for (int i = 1; i < spans.size(); i++) {
            if (spans.get(i)[0] < spans.get(i - 1)[1])
                throw new IllegalArgumentException("error.patrol.rule.overlap");
        }
    }
}
