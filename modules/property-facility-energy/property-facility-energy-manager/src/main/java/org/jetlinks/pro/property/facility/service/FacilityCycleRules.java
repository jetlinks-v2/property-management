package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;
import java.time.*;
import java.util.*;
import org.jetlinks.pro.property.facility.entity.CycleEntity;
import org.jetlinks.pro.property.facility.model.CycleRule;

/** 纯日历排程：每次执行独立时隙；有界搜索下一时隙，不提前展开整年设备工单。 */
public final class FacilityCycleRules {
  private FacilityCycleRules() {}
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  public record Slot(long start, long end, Long warningTime) {
    public Slot(long start, long end) { this(start, end, null); }
  }
  /** 历史规则可继续生单；新增、编辑必须显式填写预警，不替用户设置时间。 */
  public static void requireWarnings(CycleEntity cycle) {
    validate(cycle);
    java.util.stream.Stream.concat(cycle.getRule().weekly().values().stream(), cycle.getRule().dates().values().stream())
      .flatMap(List::stream).forEach(w -> require(w.warningMinutes() != null, "warning_required"));
  }

  public static void validate(CycleEntity cycle) {
    var rule = cycle.getRule();
    require(rule != null && rule.weekly() != null && rule.dates() != null &&
      rule.weekly().size() <= 7 && rule.dates().size() <= 366 &&
      (!rule.weekly().isEmpty() || !rule.dates().isEmpty()), "invalid_cycle");
    rule.weekly().forEach((day, windows) -> {
      require(day != null && day >= 1 && day <= 7 && windows != null && !windows.isEmpty(), "invalid_cycle");
      validateWindows(windows);
    });
    rule.dates().forEach((date, windows) -> {
      parseDate(date);
      validateWindows(windows);
    });
    for (int day = 1; day <= 7; day++) overlap(rule.weekly().getOrDefault(day, List.of()),
      rule.weekly().getOrDefault(day == 1 ? 7 : day - 1, List.of()));
    var dates = new HashSet<LocalDate>();
    rule.dates().keySet().forEach(value -> { var day = parseDate(value); dates.add(day); dates.add(day.plusDays(1)); });
    dates.forEach(day -> overlap(windows(rule, day), windows(rule, day.minusDays(1))));
  }

  private static void validateWindows(List<CycleRule.Window> windows) {
    require(windows != null && windows.size() <= 24, "invalid_cycle");
    int total = 0;
    for (var w : windows) {
      require(w != null && w.startMinute() >= 0 && w.startMinute() < 1440 &&
        w.endMinute() >= 0 && w.endMinute() <= 1440 && w.intervalMinutes() > 0 &&
        w.intervalMinutes() <= 144000 && w.requiredCount() > 0 && w.requiredCount() <= 100 &&
        w.cooldownMinutes() > 0 && w.cooldownMinutes() <= 144000 &&
        validUnit(w.intervalMinutes(), w.frequencyUnit()) && validUnit(w.cooldownMinutes(), w.cooldownUnit()), "invalid_cycle");
      if (w.warningMinutes() != null) {
        require(w.warningUnit() == null || Set.of("1", "2", "3").contains(w.warningUnit()), "warning_range");
        double duration = (double) w.intervalMinutes() / w.requiredCount();
        require(Double.isFinite(w.warningMinutes()) && w.warningMinutes() >= duration / 2 &&
          w.warningMinutes() < duration, "warning_range");
      }
      // Separate work orders need enough room inside each frequency period, not just across the whole day.
      require(w.requiredCount() == 1 || (long) w.cooldownMinutes() * w.requiredCount() <= w.intervalMinutes(), "cycle_capacity");
      int count = (end(w) - w.startMinute()) / w.intervalMinutes() * w.requiredCount();
      total += count;
      require(count > 0 && total <= 10000, "cycle_capacity");
      require(count <= 1 || w.cooldownMinutes() <= (double) w.intervalMinutes() / w.requiredCount(), "cycle_capacity");
    }
  }

  private static LocalDate parseDate(String value) {
    require(value != null && value.matches("\\d{4}-\\d{2}-\\d{2}"), "invalid_cycle");
    try { return LocalDate.parse(value); }
    catch (java.time.format.DateTimeParseException error) { throw new org.hswebframework.web.exception.BusinessException("error.facility.invalid_cycle"); }
  }
  private static boolean validUnit(int minutes, String unit) {
    if (unit == null) return true;
    int multiple = switch (unit) { case "1" -> 1; case "2" -> 60; case "3" -> 1440; default -> 0; };
    return multiple > 0 && minutes % multiple == 0 && minutes / multiple >= 1 && minutes / multiple <= 100;
  }
  private static int end(CycleRule.Window w) {
    return w.endMinute() <= w.startMinute() ? w.endMinute() + 1440 : w.endMinute();
  }
  private static List<CycleRule.Window> windows(CycleRule rule, LocalDate day) {
    return rule.dates().getOrDefault(day.toString(), rule.weekly().getOrDefault(day.getDayOfWeek().getValue(), List.of()));
  }
  private static void overlap(List<CycleRule.Window> today, List<CycleRule.Window> previous) {
    var spans = new ArrayList<int[]>();
    today.forEach(w -> spans.add(new int[]{w.startMinute(), Math.min(1440, end(w))}));
    previous.stream().filter(w -> end(w) > 1440).forEach(w -> spans.add(new int[]{0, end(w) - 1440}));
    spans.sort(Comparator.comparingInt(s -> s[0]));
    for (int i = 1; i < spans.size(); i++) require(spans.get(i)[0] >= spans.get(i - 1)[1], "cycle_overlap");
  }

  /** Inclusive lower bound; the preceding logical day is considered for overnight windows. */
  public static Slot next(CycleEntity cycle, long from, long startTime, long endTime) {
    require(startTime >= 0 && endTime > startTime && endTime - startTime <= 3660L * 86400000, "invalid_plan");
    validate(cycle);
    long lower = Math.max(from, startTime);
    if (lower >= endTime) return null;
    var last = Instant.ofEpochMilli(endTime).atZone(ZONE).toLocalDate();
    var first = Instant.ofEpochMilli(lower).atZone(ZONE).toLocalDate().minusDays(1);
    for (var day = first; !day.isAfter(last); day = day.plusDays(1)) {
      Slot best = null;
      long midnight = day.atStartOfDay(ZONE).toInstant().toEpochMilli();
      for (var w : windows(cycle.getRule(), day)) {
        int periods = (end(w) - w.startMinute()) / w.intervalMinutes();
        long period = w.intervalMinutes() * 60000L;
        for (int p = 0; p < periods; p++) for (int n = 0; n < w.requiredCount(); n++) {
          long base = midnight + w.startMinute() * 60000L + p * period;
          long start = base + period * n / w.requiredCount();
          long finish = Math.min(base + period * (n + 1) / w.requiredCount(), endTime);
          if (start >= lower && start < finish && (best == null || start < best.start())) {
            Long warning = w.warningMinutes() == null ? null : start + Math.round(w.warningMinutes() * 60000);
            // 有效期截断的末次任务仍须在结束前提醒；不把过期预警当成正常提醒。
            best = new Slot(start, finish, warning != null && warning < finish ? warning : null);
          }
        }
      }
      if (best != null) return best;
    }
    return null;
  }
}
