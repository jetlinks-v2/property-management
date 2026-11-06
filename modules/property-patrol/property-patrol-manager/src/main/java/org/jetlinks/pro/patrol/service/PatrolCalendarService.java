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
import reactor.core.scheduler.Schedulers;
import java.time.*;
import java.util.*;

/** 只读排班日历：日进度按归属日内的项目和点位去重，时段执行事实仍供详情及导出使用。 */
@Service
@AllArgsConstructor
public class PatrolCalendarService {
    private static final int MAX_ROWS = 50000;
    private final PatrolRosterService rosters;
    private final PatrolTaskService tasks;
    private final SaasPersonDirectory people;

    public record Shift(String name, long startTime, long endTime, long staffCount, long taskCount,
                        long expectedCount, long completedCount, long missedCount, String status) {}
    public record Day(String date, long staffCount, long taskCount, long expectedCount,
                      long completedCount, long missedCount, String status, List<Shift> shifts,
                      long pointCount, long completedPointCount, long missedPointCount, long absentPointCount) {}
    public record Summary(long scheduledDayCount, long totalDayCount, long staffCount, long rosterCount,
                          long taskCount, long expectedCount, long completedCount, long missedCount,
                          long pendingTaskCount, long ongoingTaskCount, long completedTaskCount, long abnormalTaskCount,
                          long pointCount, long completedPointCount, long missedPointCount, long absentPointCount) {}
    public record Month(String month, Summary summary, List<Day> days) {}
    public record Detail(String date, long staffCount, long shiftCount, long pointCount,
                         long expectedCount, long completedCount, long missedCount,
                         List<PatrolRosterEntity> staff, List<PatrolTaskEntity> tasks,
                         Map<String, String> staffTelephones, long completedPointCount, long missedPointCount,
                         long absentPointCount) {}
    record Rows(List<PatrolRosterEntity> staff, List<PatrolTaskEntity> tasks) {}

    public Mono<Month> month(YearMonth month) {
        return rows(month.atDay(1), month.plusMonths(1).atDay(1), false)
                .publishOn(Schedulers.parallel()).map(rows -> buildMonth(month, rows));
    }

    public Mono<Detail> day(LocalDate day) {
        return rows(day, day.plusDays(1), true).flatMap(rows ->
                people.telephones(rows.staff().stream().map(PatrolRosterEntity::getUserId).distinct().toList())
                        .map(telephones -> new Detail(day.toString(),
                                staffCount(rows.staff()), shiftCount(rows.staff()),
                                pointCount(rows.tasks()),
                                expected(rows.tasks()), completed(rows.tasks()), missed(rows.tasks()),
                                rows.staff(), rows.tasks(), telephones,
                                completedPointCount(rows.tasks()), missedPointCount(rows.tasks()), absentPointCount(rows.tasks()))));
    }

    private Mono<Rows> rows(LocalDate from, LocalDate until, boolean detail) {
        QueryParamEntity staffQuery = QueryParamEntity.newQuery()
                .gte("businessDate", from.toString()).lt("businessDate", until.toString())
                .and("enabled", true).orderByAsc("startTime").orderByAsc("id").getParam();
        // 已部署旧任务可能没有 belongDate，此时沿用其生成时保存的 businessDate，不按结束日推算。
        QueryParamEntity taskQuery = QueryParamEntity.newQuery()
                .nest(nest -> nest
                        .nest().gte("belongDate", from.toString()).lt("belongDate", until.toString()).end()
                        .orNest().isNull("belongDate").gte("businessDate", from.toString()).lt("businessDate", until.toString()).end())
                .getParam();
        if (detail) taskQuery.toQuery().orderByAsc("endTime").orderByAsc("startTime").orderByAsc("id");
        else {
            // 月历只需进度和班次字段，不读取每个时段的检查标准快照，也不为不展示的明细排序。
            taskQuery.setIncludes(Set.of("projectId", "pointId", "belongDate", "businessDate",
                    "startTime", "endTime", "requiredCount", "completedCount"));
            staffQuery.setIncludes(Set.of("id", "projectId", "businessDate", "userId", "rest",
                    "shiftName", "name", "startTime", "endTime"));
        }
        // 在数据库限制有界读取；多取一条用于明确报错，不能先返回全月后在客户端截断。
        staffQuery.setPageIndex(0); staffQuery.setPageSize(MAX_ROWS + 1); staffQuery.setPaging(true);
        taskQuery.setPageIndex(0); taskQuery.setPageSize(MAX_ROWS + 1); taskQuery.setPaging(true);
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
        var staffByDate = rows.staff().stream().collect(java.util.stream.Collectors.groupingBy(PatrolRosterEntity::getBusinessDate));
        var tasksByDate = rows.tasks().stream().collect(java.util.stream.Collectors.groupingBy(
                row -> row.getBelongDate() == null ? row.getBusinessDate() : row.getBelongDate()));
        for (int n = 1; n <= month.lengthOfMonth(); n++) {
            LocalDate date = month.atDay(n);
            var staff = staffByDate.getOrDefault(date, List.of());
            var tasks = tasksByDate.getOrDefault(date.toString(), List.of());
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
                    completed(tasks), missed(tasks), aggregateStatus(tasks), shiftRows,
                    pointCount(tasks), completedPointCount(tasks), missedPointCount(tasks), absentPointCount(tasks)));
        }
        var summary = new Summary(days.stream().filter(day -> !day.shifts().isEmpty()).count(), month.lengthOfMonth(),
                staffCount(rows.staff()), rows.staff().stream().filter(row -> !row.isRest()).count(),
                rows.tasks().size(), expected(rows.tasks()), completed(rows.tasks()), missed(rows.tasks()),
                countStatus(rows.tasks(), "pending"), countStatus(rows.tasks(), "ongoing"),
                countStatus(rows.tasks(), "completed"), countStatus(rows.tasks(), "abnormal"),
                days.stream().mapToLong(Day::pointCount).sum(), days.stream().mapToLong(Day::completedPointCount).sum(),
                days.stream().mapToLong(Day::missedPointCount).sum(), days.stream().mapToLong(Day::absentPointCount).sum());
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
    private static Map.Entry<String, String> pointKey(PatrolTaskEntity row) {
        return new AbstractMap.SimpleImmutableEntry<>(row.getProjectId(), row.getPointId());
    }
    private static long pointCount(List<PatrolTaskEntity> rows) {
        return rows.stream().map(PatrolCalendarService::pointKey).distinct().count();
    }
    /** 点位完成要求当天所有角色、所有时段均达到应巡次数，不能把一次打卡或单角色完成当成点位完成。 */
    private static long completedPointCount(List<PatrolTaskEntity> rows) {
        return rows.stream().collect(java.util.stream.Collectors.groupingBy(PatrolCalendarService::pointKey))
                .values().stream().filter(group -> group.stream()
                        .allMatch(row -> completed(row) >= row.getRequiredCount())).count();
    }
    private static long missedPointCount(List<PatrolTaskEntity> rows) {
        return rows.stream().collect(java.util.stream.Collectors.groupingBy(PatrolCalendarService::pointKey))
                .values().stream().filter(group -> group.stream().anyMatch(row -> completed(row) > 0)
                        && group.stream().anyMatch(row -> status(row).equals("abnormal"))).count();
    }
    /** 缺勤按点位判定：所有角色均无有效巡更，且全部应巡时段已截止；与漏检点位互斥。 */
    private static long absentPointCount(List<PatrolTaskEntity> rows) {
        long now = System.currentTimeMillis();
        return rows.stream().collect(java.util.stream.Collectors.groupingBy(PatrolCalendarService::pointKey))
                .values().stream().filter(group -> group.stream().noneMatch(row -> completed(row) > 0)
                        && group.stream().anyMatch(row -> row.getRequiredCount() > 0)
                        && group.stream().allMatch(row -> row.getEndTime() <= now)).count();
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
