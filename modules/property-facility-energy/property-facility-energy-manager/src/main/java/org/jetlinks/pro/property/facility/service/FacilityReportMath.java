package org.jetlinks.pro.property.facility.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.jetlinks.pro.property.facility.entity.*;

/** 报表的期间归属和最终验收口径；不把当前结果伪装成历史快照。 */
final class FacilityReportMath {
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private FacilityReportMath() {}

  static BigDecimal rate(long part, long total) {
    return total == 0 ? null : BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100))
      .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
  }

  static boolean within(Long time, long from, long to) {
    return time != null && time >= from && time < to;
  }

  static String day(long time) {
    return Instant.ofEpochMilli(time).atZone(ZONE).toLocalDate().toString();
  }

  static List<Map<String, Object>> days(long from, long to, String... keys) {
    var rows = new ArrayList<Map<String, Object>>();
    var date = Instant.ofEpochMilli(from).atZone(ZONE).toLocalDate();
    while (date.atStartOfDay(ZONE).toInstant().toEpochMilli() < to) {
      var row = new LinkedHashMap<String, Object>();
      row.put("date", date.toString());
      for (var key : keys) row.put(key, 0L);
      rows.add(row);
      date = date.plusDays(1);
    }
    return rows;
  }

  static String workBucket(TaskEntity task, long now) {
    if (Set.of("cancelled", "closed").contains(task.getState())) return task.getState();
    if ("completed".equals(task.getState()) && task.getCompletedTime() != null) {
      return task.getDeadline() != null && task.getCompletedTime() <= task.getDeadline() ? "onTime" : "late";
    }
    return task.getDeadline() != null && task.getDeadline() <= now ? "dueIncomplete" : "notDue";
  }

  static Map<String, Object> work(List<TaskEntity> source, List<GroupEntity> groups, long from, long to, long now) {
    var tasks = source.stream().filter(t -> t.getParentTaskId() == null && within(t.getScheduledTime(), from, to)).toList();
    var effective = tasks.stream().filter(t -> !Set.of("cancelled", "closed").contains(t.getState())).toList();
    var due = effective.stream().filter(t -> t.getDeadline() != null && t.getDeadline() <= now).toList();
    var result = new LinkedHashMap<String, Object>();
    result.put("total", tasks.size());
    result.put("completed", effective.stream().filter(t -> "completed".equals(t.getState())).count());
    result.put("due", due.size());
    result.put("onTimeRate", rate(due.stream().filter(t -> "onTime".equals(workBucket(t, now))).count(), due.size()));
    result.put("overdue", effective.stream().filter(t -> "dueIncomplete".equals(workBucket(t, now))).count());
    result.put("closed", tasks.stream().filter(t -> "closed".equals(t.getState())).count());
    result.put("cancelled", tasks.stream().filter(t -> "cancelled".equals(t.getState())).count());
    result.put("asOf", now);
    var trend = days(from, to, "onTime", "late", "dueIncomplete", "notDue", "closed", "cancelled");
    for (var task : tasks) increment(trend, day(task.getScheduledTime()), workBucket(task, now));
    result.put("trend", trend);
    var comparisons = new ArrayList<Map<String, Object>>();
    for (var group : groups) {
      var work = effective.stream().filter(t -> t.getGroupIds() != null && t.getGroupIds().contains(group.getId())).toList();
      if (work.isEmpty()) continue;
      var groupDue = work.stream().filter(t -> t.getDeadline() != null && t.getDeadline() <= now).toList();
      var row = new LinkedHashMap<String, Object>();
      row.put("id", group.getId()); row.put("name", group.getName()); row.put("total", work.size());
      row.put("onTimeRate", rate(groupDue.stream().filter(t -> "onTime".equals(workBucket(t, now))).count(), groupDue.size()));
      comparisons.add(row);
    }
    result.put("groups", comparisons);
    result.put("states", distribution(effective.stream().filter(t -> !"completed".equals(t.getState())).toList(), t -> switch (t.getState()) {
      case "open" -> "open";
      case "review", "external-submitted" -> "review";
      default -> "executing";
    }));
    return result;
  }

  static boolean effectiveFault(FaultEntity fault) {
    return !"cancelled".equals(fault.getState());
  }

  static Map<String, Object> faults(List<FaultEntity> source, long from, long to, long now) {
    var valid = source.stream().filter(FacilityReportMath::effectiveFault).toList();
    var created = valid.stream().filter(f -> within(f.getCreateTime(), from, to)).toList();
    var completed = valid.stream().filter(f -> "closed".equals(f.getState()) && within(f.getCompletedTime(), from, to)).toList();
    var open = created.stream().filter(f -> !"closed".equals(f.getState())).toList();
    long closed = created.stream().filter(f -> "closed".equals(f.getState())).count();
    var result = new LinkedHashMap<String, Object>();
    result.put("total", created.size()); result.put("completed", closed); result.put("outstanding", open.size());
    result.put("completionRate", rate(closed, created.size())); result.put("asOf", now);
    var durations = completed.stream().filter(f -> f.getCompletedTime() >= f.getCreateTime()).toList();
    result.put("averageClosureHours", durations.isEmpty() ? null : BigDecimal.valueOf(durations.stream()
      .mapToLong(f -> f.getCompletedTime() - f.getCreateTime()).average().orElse(0) / 3600000).setScale(2, RoundingMode.HALF_UP));
    var trend = days(from, to, "created", "completed");
    created.forEach(f -> increment(trend, day(f.getCreateTime()), "created"));
    completed.forEach(f -> increment(trend, day(f.getCompletedTime()), "completed"));
    result.put("trend", trend);
    result.put("devices", topDevices(created, FaultEntity::getDeviceId, FaultEntity::getDeviceName));
    result.put("ages", distribution(open, f -> {
      long age = Math.max(0, now - f.getCreateTime());
      return age < 86400000L ? "withinDay" : age < 3L * 86400000 ? "oneToThreeDays" : age < 7L * 86400000 ? "threeToSevenDays" : "overSevenDays";
    }));
    return result;
  }

  static String processing(AlertEntity event) {
    if ("open".equals(event.getProcessingState())) return "pending";
    if (Set.of("acknowledged", "processing").contains(Objects.toString(event.getProcessingState(), ""))) return "processing";
    if (event.getProcessingState() != null) return event.getProcessingState();
    return "closed".equals(event.getState()) ? "closed" : "confirmed".equals(event.getState()) ? "confirmed" : "pending";
  }

  static Map<String, Object> alerts(List<AlertEpisodeEntity> source, long from, long to, long now) {
    var events = source.stream().filter(e -> within(e.getFirstTime(), from, to)).toList();
    var result = new LinkedHashMap<String, Object>();
    result.put("total", events.size());
    result.put("critical", events.stream().filter(e -> "critical".equals(e.getLevel())).count());
    result.put("pending", events.stream().filter(e -> "pending".equals(processing(e))).count());
    result.put("recovered", events.stream().filter(e -> e.getRecoveredTime() != null || "recovered".equals(e.getState())).count());
    result.put("asOf", now);
    var types = events.stream().map(AlertEntity::getRuleType).filter(Objects::nonNull).distinct().sorted().toList();
    var trend = days(from, to, types.toArray(String[]::new));
    events.forEach(e -> increment(trend, day(e.getFirstTime()), Objects.toString(e.getRuleType(), "unknown")));
    result.put("trend", trend); result.put("types", types);
    result.put("devices", topDevices(events.stream().filter(e -> e.getDeviceId() != null).toList(), AlertEntity::getDeviceId, AlertEntity::getName));
    result.put("states", distribution(events, FacilityReportMath::processing));
    return result;
  }

  private static void increment(List<Map<String, Object>> rows, String date, String key) {
    rows.stream().filter(row -> date.equals(row.get("date"))).findFirst()
      .ifPresent(row -> row.put(key, ((Number) row.getOrDefault(key, 0L)).longValue() + 1));
  }

  private static <T> Map<String, Long> distribution(List<T> rows, Function<T, String> key) {
    var result = new LinkedHashMap<String, Long>();
    rows.forEach(row -> result.merge(key.apply(row), 1L, Long::sum));
    return result;
  }

  private static <T> List<Map<String, Object>> topDevices(List<T> rows, Function<T, String> id, Function<T, String> name) {
    var counts = distribution(rows.stream().filter(r -> id.apply(r) != null).toList(), id);
    return counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
      .limit(5).map(entry -> {
        var row = new LinkedHashMap<String, Object>(); row.put("id", entry.getKey()); row.put("count", entry.getValue());
        row.put("name", rows.stream().filter(r -> entry.getKey().equals(id.apply(r))).map(name).filter(Objects::nonNull).findFirst().orElse("—"));
        return (Map<String, Object>) row;
      }).toList();
  }
}
