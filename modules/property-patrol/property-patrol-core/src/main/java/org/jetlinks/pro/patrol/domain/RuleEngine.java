package org.jetlinks.pro.patrol.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** 一配置时段一任务，完整周期计次，保留整个时段的打卡窗口。 */
public final class RuleEngine {
    private RuleEngine() {}

    public record Occurrence(
            LocalDate businessDate, Instant start, Instant end, int requiredCount, int cooldownMinutes) {}

    public static List<Occurrence> expand(PatrolRule rule, LocalDate day, ZoneId zone) {
        validate(rule);
        return windows(rule, day).stream()
                .map(window -> new Occurrence(
                        day,
                        day.atStartOfDay(zone).plusMinutes(window.startMinute()).toInstant(),
                        day.atStartOfDay(zone).plusMinutes(endMinute(window)).toInstant(),
                        requiredCount(window), window.cooldown(rule)))
                .sorted(Comparator.comparing(Occurrence::start))
                .toList();
    }

    public static void validate(PatrolRule rule) {
        if (rule == null || rule.weekly() == null || rule.dates() == null
                || rule.cooldownMinutes() < 0 || rule.cooldownMinutes() > 1440
                || rule.weekly().size() > 7 || rule.dates().size() > 366
                || (rule.weekly().isEmpty() && rule.dates().isEmpty()))
            throw new IllegalArgumentException("error.patrol.rule.invalid");
        rule.weekly().forEach((weekday, windows) -> {
            if (weekday == null || weekday < 1 || weekday > 7)
                throw new IllegalArgumentException("error.patrol.rule.invalid");
            validateWindows(rule, windows);
        });
        rule.dates().forEach((date, windows) -> {
            if (date == null) throw new IllegalArgumentException("error.patrol.rule.invalid");
            // 已持久化的空日期覆盖表示停巡，继续保留其含义。
            validateWindows(rule, windows);
        });
        // 保存时即检查整周及日期边界，而非等到对应日期生成才拒绝重叠。
        for (int day = 1; day <= 7; day++) {
            assertDayNoOverlap(rule.weekly().getOrDefault(day, List.of()),
                    rule.weekly().getOrDefault(day == 1 ? 7 : day - 1, List.of()));
        }
        var boundaryDates = new HashSet<LocalDate>();
        rule.dates().keySet().forEach(date -> {
            boundaryDates.add(date);
            boundaryDates.add(date.plusDays(1));
        });
        boundaryDates.forEach(date -> assertDayNoOverlap(
                windows(rule, date), windows(rule, date.minusDays(1))));
    }

    private static void validateWindows(PatrolRule rule, List<PatrolRule.Window> windows) {
        if (windows == null || windows.size() > 24)
            throw new IllegalArgumentException("error.patrol.rule.invalid");
        for (var w : windows) {
            if (w == null || w.startMinute() < 0 || w.startMinute() > 1439
                    || w.endMinute() < 0 || w.endMinute() > 1440
                    || w.intervalMinutes() < 1 || w.intervalMinutes() > 144000
                    || w.requiredCount() < 1 || w.requiredCount() > 100
                    || w.cooldown(rule) < 0 || w.cooldown(rule) > 144000
                    || !validUnit(w.intervalMinutes(), w.frequencyUnit())
                    || !validUnit(w.cooldown(rule), w.cooldownUnit()))
                throw new IllegalArgumentException("error.patrol.rule.invalid");
            int count = requiredCount(w);
            int duration = endMinute(w) - w.startMinute();
            if (count > 10000 || (count > 1 && (count - 1L) * w.cooldown(rule) > duration))
                throw new IllegalArgumentException("error.patrol.rule.capacity");
        }
    }

    private static boolean validUnit(int minutes, String unit) {
        if (unit == null) return true;
        int multiplier = switch (unit) {
            case "1" -> 1;
            case "2" -> 60;
            case "3" -> 1440;
            default -> 0;
        };
        return multiplier > 0 && minutes % multiplier == 0
                && minutes / multiplier >= 1 && minutes / multiplier <= 100;
    }

    private static int requiredCount(PatrolRule.Window window) {
        return (endMinute(window) - window.startMinute()) / window.intervalMinutes() * window.requiredCount();
    }

    /** 等值是从开始时刻起的24小时；1440仍兼容既有00:00–24:00数据。 */
    private static int endMinute(PatrolRule.Window w) {
        return w.endMinute() <= w.startMinute() ? w.endMinute() + 1440 : w.endMinute();
    }

    private static List<PatrolRule.Window> windows(PatrolRule rule, LocalDate day) {
        return rule.dates().containsKey(day) ? rule.dates().get(day)
                : rule.weekly().getOrDefault(day.getDayOfWeek().getValue(), List.of());
    }

    private static void assertDayNoOverlap(List<PatrolRule.Window> today, List<PatrolRule.Window> previous) {
        List<int[]> spans = new ArrayList<>();
        today.forEach(w -> spans.add(new int[] {w.startMinute(), Math.min(1440, endMinute(w))}));
        previous.stream().filter(w -> endMinute(w) > 1440)
                .forEach(w -> spans.add(new int[] {0, endMinute(w) - 1440}));
        spans.sort(Comparator.comparingInt(span -> span[0]));
        for (int i = 1; i < spans.size(); i++) {
            if (spans.get(i)[0] < spans.get(i - 1)[1])
                throw new IllegalArgumentException("error.patrol.rule.overlap");
        }
    }
}
