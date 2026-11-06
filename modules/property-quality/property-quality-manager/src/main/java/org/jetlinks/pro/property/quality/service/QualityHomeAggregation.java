package org.jetlinks.pro.property.quality.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.defaults.record.Record;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.pro.property.quality.asset.QualityAssetType;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 首页在数据库按项目或时间窗聚合；授权条件只解析一次，不随项目数增加查询次数。 */
@Service
@RequiredArgsConstructor
public class QualityHomeAggregation {
    private final QualityScope scope;
    private final QualityReports reports;
    private final QueryHelper helper;

    public Mono<List<Map<String, Object>>> windows(String project, List<QualityHomeRules.Window> windows, boolean deadlineCohort) {
        return Mono.zip(taskQuery(project, windows, deadlineCohort), recordQuery(project, windows, deadlineCohort)).flatMap(queries -> Mono.zip(
            helper.select(sql(windows, false, deadlineCohort, false), arguments(windows)).where(queries.getT1()).fetch().single(),
            helper.select(sql(windows, true, deadlineCohort, false), arguments(windows)).where(queries.getT2()).fetch().single()
        )).map(rows -> {
            var result = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < windows.size(); i++) result.add(result(rows.getT1(), rows.getT2(), i));
            return result;
        });
    }

    public Mono<Map<String, Map<String, Object>>> projects(String project, long start, long end) {
        var windows = List.of(new QualityHomeRules.Window("", start, end));
        return Mono.zip(taskQuery(project, windows, true), recordQuery(project, windows, true)).flatMap(queries -> Mono.zip(
            helper.select(sql(windows, false, true, true), arguments(windows)).where(queries.getT1()).fetch()
                .collectMap(row -> row.getString("projectId").orElse("")),
            helper.select(sql(windows, true, true, true), arguments(windows)).where(queries.getT2()).fetch()
                .collectMap(row -> row.getString("projectId").orElse(""))
        )).map(rows -> {
            // 只保存数据库聚合后的项目维度，不读取任务/照片/检查项实体。
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            rows.getT1().forEach((id, row) -> result.put(id, result(row, rows.getT2().get(id), 0)));
            rows.getT2().forEach((id, row) -> result.computeIfAbsent(id, ignored -> result(null, row, 0)));
            return result;
        });
    }

    private Mono<QueryParamEntity> taskQuery(String project, List<QualityHomeRules.Window> windows, boolean deadlineCohort) {
        var range = range(windows);
        var time = QueryParamEntity.newQuery().gte("reportDeadline", range[0]).lt("reportDeadline", range[1]);
        // 总览的完成口径独立于截止时间，必须保留区间外到期但本期完成的任务。
        if (!deadlineCohort) time.or(nest -> nest.gte("completeTime", range[0]).lt("completeTime", range[1]));
        var query = time.getParam().toNestQuery().getParam();
        if (project != null && !project.isBlank()) query.toQuery().and("projectId", project);
        return scope.query(query, QualityAssetType.qualityTask, "id").map(QualityHomeAggregation::aggregateQuery);
    }

    private Mono<QueryParamEntity> recordQuery(String project, List<QualityHomeRules.Window> windows, boolean deadlineCohort) {
        var range = range(windows);
        String timeField = deadlineCohort ? "reportDeadline" : "completeTime";
        var records = QueryParamEntity.newQuery().where("recordStatus", 2).gte(timeField, range[0]).lt(timeField, range[1]).getParam();
        // 检查项仍通过已授权工单子查询，项目筛选不能替代工单资产权限。
        return reports.recordQuery(projectQuery(project), records).map(QualityHomeAggregation::aggregateQuery);
    }

    private static long[] range(List<QualityHomeRules.Window> windows) {
        long start = windows.stream().filter(w -> w.start() < w.end()).mapToLong(QualityHomeRules.Window::start).min().orElse(0);
        long end = windows.stream().filter(w -> w.start() < w.end()).mapToLong(QualityHomeRules.Window::end).max().orElse(0);
        return new long[]{start, end};
    }

    private static QueryParamEntity aggregateQuery(QueryParamEntity query) {
        query.setPaging(false); query.setSorts(List.of());
        return query;
    }

    private static QueryParamEntity projectQuery(String project) {
        var query = new QueryParamEntity();
        if (project != null && !project.isBlank()) query.toQuery().and("projectId", project);
        return query;
    }

    static String sql(List<QualityHomeRules.Window> windows, boolean records, boolean deadlineCohort, boolean grouped) {
        var columns = new ArrayList<String>();
        if (grouped) columns.add("project_id as \"projectId\"");
        for (int i = 0; i < windows.size(); i++) {
            String first = records ? deadlineCohort ? "report_deadline" : "complete_time" : "report_deadline";
            String second = records ? first : deadlineCohort ? "report_deadline" : "complete_time";
            columns.add("coalesce(sum(case when " + first + ">=? and " + first + "<? then 1 else 0 end),0) as n" + i);
            String state = records ? "is_qualified=1" : "task_status=2";
            columns.add("coalesce(sum(case when " + second + ">=? and " + second + "<? and " + state + " then 1 else 0 end),0) as v" + i);
        }
        return "select " + String.join(",", columns) + " from " + (records ? "pq_record" : "pq_task")
            + (grouped ? " group by project_id" : "");
    }

    static Object[] arguments(List<QualityHomeRules.Window> windows) {
        var args = new ArrayList<Object>();
        for (var window : windows) {
            args.add(window.start()); args.add(window.end()); args.add(window.start()); args.add(window.end());
        }
        return args.toArray();
    }

    static Map<String, Object> result(Record tasks, Record records, int index) {
        long should = count(tasks, "n" + index), actual = count(tasks, "v" + index);
        var result = new LinkedHashMap<String, Object>();
        result.put("shouldCompleteCount", should); result.put("actualCompleteCount", actual);
        result.put("taskCompleteRate", QualityHomeRules.percent(actual, should));
        result.put("qualifiedRate", QualityHomeRules.percent(count(records, "v" + index), count(records, "n" + index)));
        return result;
    }

    private static long count(Record row, String key) {
        return row == null ? 0 : row.get(key).map(value -> ((Number) value).longValue()).orElse(0L);
    }
}
