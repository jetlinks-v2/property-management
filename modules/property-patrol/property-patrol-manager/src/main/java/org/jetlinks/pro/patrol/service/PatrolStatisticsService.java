package org.jetlinks.pro.patrol.service;

import lombok.AllArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;

/** 原 ReportDao 的次数覆盖率、时段完成合格率；不是检查项结果合格率。 */
@Service
@AllArgsConstructor
public class PatrolStatisticsService {
    private final PatrolTaskService tasks;
    public record Range(LocalDate from, LocalDate to, List<String> categoryIds) {}
    public record Row(String id, String name, long pointCount, long taskCount, long completedTasks,
                      long expected, long completed, long missing, double coverage, double qualification) {}
    /** 总计在同一授权任务流中计算，点位去重，百分比由分子分母重算。 */
    public record Report(LocalDate from, LocalDate to, List<Row> rows, Row total) {}
    private static final class Accumulator {
        final Map<String, Count> groups = new TreeMap<>();
        final Count total = new Count();
        void add(PatrolTaskEntity task, boolean people) {
            String id = people ? task.getRoleId() : task.getPointId();
            Count count = groups.computeIfAbsent(id, ignored -> new Count());
            count.name = people ? task.getRoleName() : task.getPointName();
            count.add(task);
            total.add(task);
        }
        Report report(Range range) {
            return new Report(range.from(), range.to(), groups.entrySet().stream()
                    .map(entry -> entry.getValue().row(entry.getKey())).toList(), total.row("total"));
        }
    }
    static final class Count {
        String name;
        Set<String> points = new HashSet<>();
        long tasks, completeTasks, expected, completed;
        void add(PatrolTaskEntity task) {
            points.add(task.getPointId()); tasks++;
            expected += task.getRequiredCount(); completed += task.getCompletedCount();
            if (task.getCompletedCount() >= task.getRequiredCount()) completeTasks++;
        }
        Row row(String id) {
            return new Row(id, name, points.size(), tasks, completeTasks, expected, completed,
                    expected - completed, percent(completed, expected), percent(completeTasks, tasks));
        }
    }
    public Mono<List<Row>> query(Range range, boolean people) {
        return report(range, people).map(Report::rows);
    }
    public Mono<Report> report(Range range, boolean people) {
        if (range.from() == null || range.to() == null || range.to().isBefore(range.from())
                || range.to().isAfter(range.from().plusDays(365)))
            return Mono.error(new BusinessException("error.patrol.statistics.range"));
        var zone = ZoneId.of("Asia/Shanghai");
        // 当前旧 PC 的 *New 报表均按 created_time 过滤；不擅自替换成归属日或打卡时间。
        QueryParamEntity query = QueryParamEntity.newQuery()
                .gte("createTime", range.from().atStartOfDay(zone).toInstant().toEpochMilli())
                .lt("createTime", range.to().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()).getParam();
        if (range.categoryIds() != null && !range.categoryIds().isEmpty())
            query.toQuery().in("categoryId", range.categoryIds());
        query.setPaging(false);
        // 以指定岗位为旧角色指派对象；不能把同一份岗位应巡次数复制给每个成员。
        return AssetsHolder.injectQueryParam(query, PatrolAssetType.patrolPlan, "planId")
                .flatMapMany(tasks::query)
                .collect(Accumulator::new, (counts, task) -> counts.add(task, people))
                .map(counts -> counts.report(range));
    }
    private static double percent(long numerator, long denominator) {
        return denominator == 0 ? 0 : Math.round(10000.0 * numerator / denominator) / 100.0;
    }
}
