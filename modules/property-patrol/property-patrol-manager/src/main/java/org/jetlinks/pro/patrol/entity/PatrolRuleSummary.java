package org.jetlinks.pro.patrol.entity;

import org.hswebframework.web.i18n.LocaleUtils;
import org.jetlinks.pro.patrol.domain.PatrolRule;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 点位等级只读频次说明，供列表字段和 Excel 共用；不参与规则计算或持久化。 */
final class PatrolRuleSummary {
    private PatrolRuleSummary() {}

    static List<String> rows(PatrolRule rule) {
        if (rule == null) return List.of(message("empty", "未配置巡更时段"));
        var groups = new LinkedHashMap<List<PatrolRule.Window>, List<Integer>>();
        if (rule.weekly() != null) for (int day = 1; day <= 7; day++) {
            var windows = rule.weekly().get(day);
            if (windows != null && !windows.isEmpty()) groups.computeIfAbsent(windows, ignored -> new ArrayList<>()).add(day);
        }
        List<String> rows = new ArrayList<>();
        groups.forEach((windows, days) -> rows.add(row(days.stream().map(PatrolRuleSummary::weekday)
                .collect(Collectors.joining("、")), windows, rule)));
        if (rule.dates() != null) new TreeMap<>(rule.dates()).forEach((date, windows) -> rows.add(row(date.toString(), windows, rule)));
        return rows.isEmpty() ? List.of(message("empty", "未配置巡更时段")) : List.copyOf(rows);
    }

    private static String row(String label, List<PatrolRule.Window> windows, PatrolRule rule) {
        return label + "：" + (windows.isEmpty() ? message("rest", "不巡更") : windows.stream()
                .map(window -> window(window, rule)).collect(Collectors.joining("；")));
    }

    private static String window(PatrolRule.Window window, PatrolRule rule) {
        String suffix = window.endMinute() < window.startMinute() ? "（" + message("nextDay", "次日") + "）"
                : window.endMinute() == window.startMinute() ? "（" + message("allDay", "全天") + "）" : "";
        return clock(window.startMinute()) + "–" + clock(window.endMinute()) + suffix + " "
                + message("window", "每 {0} 巡更 {1} 次，每次至少间隔 {2}",
                quantity(window.intervalMinutes(), window.frequencyUnit()), Integer.toString(window.requiredCount()),
                quantity(window.cooldown(rule), window.cooldownUnit()));
    }

    /** 保留录入单位；旧规则未保存单位时，和原列表一样优先使用可整除的天、小时。 */
    private static String quantity(int minutes, String unit) {
        String selected = unit != null ? unit : minutes > 0 && minutes % 1440 == 0 ? "3"
                : minutes > 0 && minutes % 60 == 0 ? "2" : "1";
        int divisor = "3".equals(selected) ? 1440 : "2".equals(selected) ? 60 : 1;
        String value = BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(divisor), 10, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        return value + " " + message("unit" + selected, switch (selected) { case "3" -> "天"; case "2" -> "小时"; default -> "分钟"; });
    }
    private static String weekday(int day) { return message("week" + day, "周" + "一二三四五六日".charAt(day - 1)); }
    private static String clock(int minute) { return String.format("%02d:%02d", minute / 60, minute % 60); }
    private static String message(String key, String fallback, Object... args) {
        String pattern = LocaleUtils.resolveMessage("message.patrol.rule_summary." + key, fallback);
        return args.length == 0 ? pattern : java.text.MessageFormat.format(pattern, args);
    }
}
