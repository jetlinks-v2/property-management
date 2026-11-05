package org.jetlinks.pro.patrol.service.statistics;

import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.jetlinks.pro.patrol.service.statistics.PatrolOverview.*;

/** 已授权、有界事实的纯聚合器。一次时钟贯穿总计、项目、趋势和下钻，避免截止边界口径漂移。 */
public final class PatrolOverviewAggregation {
    private PatrolOverviewAggregation() {}
    private record Key(String project, String point, String date) {}

    public record Result(Report report, List<PointDay> points) {}

    public static Result aggregate(Range range, List<PatrolTaskEntity> tasks,
                                   List<PatrolExecutionEntity> executions, long now) {
        var executionByTask = executions.stream().collect(Collectors.groupingBy(PatrolExecutionEntity::getTaskId));
        Map<Key, List<PatrolTaskEntity>> grouped = tasks.stream().collect(Collectors.groupingBy(task ->
                new Key(Objects.toString(task.getProjectId(), ""), task.getPointId(), date(task))));
        List<PointDay> points = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            var periods = entry.getValue();
            var first = periods.get(0);
            long expected = 0, completed = 0, executed = 0, overdue = 0, notDue = 0, dueExpected = 0, dueCompleted = 0;
            boolean abnormal = false, allEnded = true, allFuture = true;
            for (var task : periods) {
                int required = Math.max(0, task.getRequiredCount());
                int done = Math.max(0, Math.min(required, task.getCompletedCount()));
                var facts = executionByTask.getOrDefault(task.getId(), List.of());
                expected += required;
                completed += done;
                executed += Math.min(done, facts.size());
                if (task.getEndTime() <= now) {
                    overdue += required - done;
                    dueExpected += required;
                    dueCompleted += done;
                }
                else notDue += required - done;
                allEnded &= task.getEndTime() <= now;
                allFuture &= task.getStartTime() > now;
                abnormal |= facts.stream().anyMatch(fact -> fact.getResult() == PatrolExecutionEntity.CheckResult.fail);
            }
            // 审核补计沿用任务计数，但不虚构打卡人员；缺勤必须等当天所有角色、时段截止后判断。
            String state = completed >= expected ? "completed"
                    : completed == 0 && allEnded ? "absent"
                    : completed > 0 && overdue > 0 ? "missed"
                    : allFuture ? "pending" : "ongoing";
            var counts = new Counts(expected, completed, executed, completed - executed, overdue, notDue, 1,
                    flag(state.equals("completed")), flag(state.equals("missed")), flag(state.equals("absent")),
                    flag(state.equals("pending")), flag(state.equals("ongoing")), flag(abnormal), dueExpected, dueCompleted);
            var key = entry.getKey();
            points.add(new PointDay(key.project() + "|" + key.point() + "|" + key.date(),
                    key.project(), first.getProjectName(), key.point(), first.getPointName(), key.date(),
                    names(periods, PatrolTaskEntity::getCategoryName), names(periods, PatrolTaskEntity::getLevelName),
                    names(periods, PatrolTaskEntity::getTaskGroupId), state, counts));
        }
        points.sort(Comparator.comparing(PointDay::date).reversed()
                .thenComparing(PointDay::projectId, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(PointDay::pointId));
        var projects = points.stream().collect(Collectors.groupingBy(PointDay::projectId, TreeMap::new, Collectors.toList()))
                .entrySet().stream().map(entry -> new Project(entry.getKey(), entry.getValue().get(0).projectName(),
                        sum(entry.getValue()))).toList();
        var days = points.stream().collect(Collectors.groupingBy(PointDay::date, TreeMap::new, Collectors.toList()))
                .entrySet().stream().map(entry -> new Day(entry.getKey(), sum(entry.getValue()))).toList();
        // 贡献只来源于执行事实，不能把角色共享任务的应巡量复制给每个角色成员。
        var people = executions.stream().collect(Collectors.groupingBy(PatrolExecutionEntity::getUserId))
                .entrySet().stream().map(entry -> {
                    var facts = entry.getValue();
                    var latest = facts.stream().max(Comparator.comparingLong(PatrolExecutionEntity::getReceiveTime)).orElseThrow();
                    return new Person(entry.getKey(), latest.getUserName(), facts.size(),
                            facts.stream().filter(fact -> fact.getResult() == PatrolExecutionEntity.CheckResult.fail).count(),
                            facts.stream().map(fact -> Arrays.asList(fact.getProjectId(), fact.getPointId())).distinct().count(),
                            latest.getReceiveTime());
                }).sorted(Comparator.comparing(Person::userName, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Person::userId)).toList();
        return new Result(new Report(range.from(), range.to(), now, sum(points), projects, days, people), points);
    }

    public static boolean matches(PointDay point, Metric metric) {
        return switch (metric) {
            case all -> true;
            case completed -> point.counts().completed() > 0;
            case finished -> point.state().equals("completed");
            case due -> point.counts().dueExpected() > 0;
            case overdue -> point.counts().overdue() > 0;
            case notDue -> point.counts().notDue() > 0;
            case anomaly -> point.counts().abnormalPoints() > 0;
            default -> point.state().equals(metric.name());
        };
    }
    public static String date(PatrolTaskEntity task) {
        return task.getBelongDate() == null ? task.getBusinessDate() : task.getBelongDate();
    }
    private static List<String> names(List<PatrolTaskEntity> rows, Function<PatrolTaskEntity, String> field) {
        return rows.stream().map(field).filter(Objects::nonNull).filter(value -> !value.isBlank()).distinct().sorted().toList();
    }
    private static long flag(boolean condition) { return condition ? 1 : 0; }
    private static Counts sum(List<PointDay> rows) {
        long[] sum = new long[15];
        for (var row : rows) {
            var c = row.counts();
            long[] values = {c.expected(), c.completed(), c.executed(), c.credited(), c.overdue(), c.notDue(), c.pointDays(),
                    c.completedPoints(), c.missedPoints(), c.absentPoints(), c.pendingPoints(), c.ongoingPoints(), c.abnormalPoints(),
                    c.dueExpected(), c.dueCompleted()};
            for (int i = 0; i < sum.length; i++) sum[i] += values[i];
        }
        return new Counts(sum[0], sum[1], sum[2], sum[3], sum[4], sum[5], sum[6], sum[7], sum[8], sum[9], sum[10], sum[11], sum[12], sum[13], sum[14]);
    }
}
