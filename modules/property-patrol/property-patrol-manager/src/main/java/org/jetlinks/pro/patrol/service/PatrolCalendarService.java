package org.jetlinks.pro.patrol.service;

import lombok.AllArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolRosterEntity;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.jetlinks.pro.patrol.integration.SaasPersonDirectory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;

/** 对照通邑 PatrolScheduleCalendarService：只读月历、当日人员/任务及导出。 */
@Service
@AllArgsConstructor
public class PatrolCalendarService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_ROWS = 50000;
    private final PatrolRosterService rosters;
    private final PatrolTaskService tasks;
    private final SaasPersonDirectory people;

    public record Shift(String name, long startTime, long endTime, long staffCount, long taskCount,
                        long expectedCount, long completedCount, long missedCount, String status) {}
    public record Day(String date, long staffCount, long taskCount, long expectedCount,
                      long completedCount, long missedCount, String status, List<Shift> shifts) {}
    public record Summary(long scheduledDayCount, long totalDayCount, long staffCount, long rosterCount,
                          long taskCount, long expectedCount, long completedCount, long missedCount,
                          long pendingTaskCount, long ongoingTaskCount, long completedTaskCount, long abnormalTaskCount) {}
    public record Month(String month, Summary summary, List<Day> days) {}
    public record Detail(String date, long staffCount, long shiftCount, long pointCount,
                         long expectedCount, long completedCount, long missedCount,
                         List<PatrolRosterEntity> staff, List<PatrolTaskEntity> tasks,
                         Map<String, String> staffTelephones) {}
    record Rows(List<PatrolRosterEntity> staff, List<PatrolTaskEntity> tasks) {}

    public Mono<Month> month(YearMonth month) {
        return rows(month.atDay(1), month.plusMonths(1).atDay(1)).map(rows -> buildMonth(month, rows));
    }

    public Mono<Detail> day(LocalDate day) {
        return rows(day, day.plusDays(1)).flatMap(rows ->
                people.telephones(rows.staff().stream().map(PatrolRosterEntity::getUserId).distinct().toList())
                        .map(telephones -> new Detail(day.toString(),
                                staffCount(rows.staff()), shiftCount(rows.staff()),
                                rows.tasks().stream().map(PatrolTaskEntity::getPointId).distinct().count(),
                                expected(rows.tasks()), completed(rows.tasks()), missed(rows.tasks()),
                                rows.staff(), rows.tasks(), telephones)));
    }

    private Mono<Rows> rows(LocalDate from, LocalDate until) {
        QueryParamEntity staffQuery = QueryParamEntity.newQuery()
                .gte("businessDate", from.toString()).lt("businessDate", until.toString())
                .and("enabled", true).orderByAsc("startTime").orderByAsc("id").getParam();
        QueryParamEntity taskQuery = QueryParamEntity.newQuery()
                .gte("endTime", from.atStartOfDay(ZONE).toInstant().toEpochMilli())
                .lt("endTime", until.atStartOfDay(ZONE).toInstant().toEpochMilli())
                .orderByAsc("endTime").orderByAsc("startTime").orderByAsc("id").getParam();
        staffQuery.setPaging(false); taskQuery.setPaging(false);
        // 两类数据分别注入其拥有的资产权限，不能用计划权限替代排班权限。
        return Mono.zip(
                AssetsHolder.injectQueryParam(staffQuery, PatrolAssetType.patrolRoster, "id")
                        .flatMapMany(rosters::query).take(MAX_ROWS + 1L).collectList(),
                AssetsHolder.injectQueryParam(taskQuery, PatrolAssetType.patrolPlan, "planId")
                        .flatMapMany(tasks::query).take(MAX_ROWS + 1L).collectList())
                .map(pair -> {
                    if (pair.getT1().size() > MAX_ROWS || pair.getT2().size() > MAX_ROWS)
                        throw new BusinessException("error.patrol.calendar.too_many");
                    return new Rows(pair.getT1(), pair.getT2());
                });
    }

    private Month buildMonth(YearMonth month, Rows rows) {
        List<Day> days = new ArrayList<>();
        for (int n = 1; n <= month.lengthOfMonth(); n++) {
            LocalDate date = month.atDay(n);
            var staff = rows.staff().stream().filter(row -> row.getBusinessDate().equals(date)).toList();
            var tasks = rows.tasks().stream().filter(row -> Instant.ofEpochMilli(row.getEndTime())
                    .atZone(ZONE).toLocalDate().equals(date)).toList();
            Map<String, List<PatrolRosterEntity>> shifts = new LinkedHashMap<>();
            staff.stream().filter(row -> !row.isRest()).forEach(row -> shifts.computeIfAbsent(
                    shiftKey(row), key -> new ArrayList<>()).add(row));
            var shiftRows = shifts.values().stream().map(group -> {
                var first = group.get(0);
                var shiftTasks = tasks.stream().filter(task -> java.util.Objects.equals(task.getProjectId(), first.getProjectId()))
                        .filter(task -> task.getStartTime() < first.getEndTime()
                        && first.getStartTime() < task.getEndTime()).toList();
                return new Shift(shiftName(first), first.getStartTime(), first.getEndTime(), staffCount(group),
                        shiftTasks.size(), expected(shiftTasks), completed(shiftTasks), missed(shiftTasks), aggregateStatus(shiftTasks));
            }).toList();
            days.add(new Day(date.toString(), staffCount(staff), tasks.size(), expected(tasks),
                    completed(tasks), missed(tasks), aggregateStatus(tasks), shiftRows));
        }
        var summary = new Summary(days.stream().filter(day -> !day.shifts().isEmpty()).count(), month.lengthOfMonth(),
                staffCount(rows.staff()), rows.staff().stream().filter(row -> !row.isRest()).count(),
                rows.tasks().size(), expected(rows.tasks()), completed(rows.tasks()), missed(rows.tasks()),
                countStatus(rows.tasks(), "pending"), countStatus(rows.tasks(), "ongoing"),
                countStatus(rows.tasks(), "completed"), countStatus(rows.tasks(), "abnormal"));
        return new Month(month.toString(), summary, days);
    }

    static String shiftName(PatrolRosterEntity row) {
        return row.getShiftName() == null || row.getShiftName().isBlank() ? row.getName() : row.getShiftName();
    }
    static String shiftKey(PatrolRosterEntity row) {
        // 不同岗位的同名同时间班次与原系统一样合并展示，统计人数取唯一人员。
        return row.getProjectId() + "|" + shiftName(row) + "|" + row.getStartTime() + "|" + row.getEndTime();
    }
    private static long staffCount(List<PatrolRosterEntity> rows) {
        return rows.stream().filter(row -> !row.isRest()).map(PatrolRosterEntity::getUserId).distinct().count();
    }
    private static long shiftCount(List<PatrolRosterEntity> rows) {
        return rows.stream().filter(row -> !row.isRest()).map(PatrolCalendarService::shiftKey).distinct().count();
    }
    static int completed(PatrolTaskEntity row) { return Math.max(0, Math.min(row.getRequiredCount(), row.getCompletedCount())); }
    private static long expected(List<PatrolTaskEntity> rows) { return rows.stream().mapToLong(PatrolTaskEntity::getRequiredCount).sum(); }
    private static long completed(List<PatrolTaskEntity> rows) { return rows.stream().mapToLong(PatrolCalendarService::completed).sum(); }
    private static long missed(List<PatrolTaskEntity> rows) {
        return rows.stream().filter(row -> status(row).equals("abnormal"))
                .mapToLong(row -> row.getRequiredCount() - completed(row)).sum();
    }
    /** 与原 resolveTaskStatus 一致：先完成，再逾期，再未开始，否则进行中。 */
    public static String status(PatrolTaskEntity row) {
        long now = System.currentTimeMillis();
        if (completed(row) >= row.getRequiredCount()) return "completed";
        if (row.getEndTime() <= now) return "abnormal";
        if (row.getStartTime() > now) return "pending";
        return "ongoing";
    }
    private static long countStatus(List<PatrolTaskEntity> rows, String status) {
        return rows.stream().filter(row -> status(row).equals(status)).count();
    }
    private static String aggregateStatus(List<PatrolTaskEntity> rows) {
        for (String status : List.of("abnormal", "ongoing", "pending"))
            if (rows.stream().anyMatch(row -> status(row).equals(status))) return status;
        return rows.isEmpty() ? "none" : "completed";
    }
}
