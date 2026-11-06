package org.jetlinks.pro.property.quality.service;

import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.property.quality.api.QualityHomeQuery;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 首页沿用旧任务口径：总览以完成时间计实际完成，趋势和项目对比以截止时间计同一批任务。
 * 任务与检查项分别由 QualityScope / QualityReports 注入 AssetsHolder，不以组织筛选替代权限。
 * 每个请求使用两次条件聚合，趋势只收集最多八个时间桶；通用 HTTP/SQL 链路已覆盖只读查询追踪。
 */
@Service
@RequiredArgsConstructor
public class QualityHome {
    private final QualityHomeAggregation aggregation;
    private final QualityDirectory directory;

    public Mono<Map<String, Object>> overview(QualityHomeQuery query) {
        var today = LocalDate.now(QualityScheduleRules.ZONE);
        var start = query.getStartDate() == null ? today.withDayOfYear(1) : LocalDate.parse(query.getStartDate());
        var end = query.getEndDate() == null ? today : LocalDate.parse(query.getEndDate());
        QualityScope.require(!end.isBefore(start), "date_range");
        return aggregation.windows(query.getProjectId(), List.of(new QualityHomeRules.Window("",
            QualityHomeRules.at(start.atStartOfDay()), QualityHomeRules.at(end.plusDays(1).atStartOfDay()))), false)
            .map(rows -> rows.get(0));
    }

    public Mono<Map<String, Object>> trend(QualityHomeQuery query) {
        var today = LocalDate.now(QualityScheduleRules.ZONE);
        var mode = query.getTrendType();
        var windows = QualityHomeRules.windows(mode, today);
        var start = "week".equals(mode) ? today.minusDays(6) : "month".equals(mode) ? today.withDayOfMonth(1) : today;
        var end = "month".equals(mode) ? today.withDayOfMonth(1).plusMonths(1) : today.plusDays(1);
        var allWindows = new java.util.ArrayList<QualityHomeRules.Window>();
        allWindows.add(new QualityHomeRules.Window("", QualityHomeRules.at(start.atStartOfDay()), QualityHomeRules.at(end.atStartOfDay())));
        allWindows.addAll(windows);
        return aggregation.windows(query.getProjectId(), allWindows, true)
            .map(rows -> trendResult(rows.get(0), windows, rows.subList(1, rows.size())));
    }

    public Flux<Map<String, Object>> compare(QualityHomeQuery query) {
        var today = LocalDate.now(QualityScheduleRules.ZONE);
        var start = query.getStartDate() == null ? today.withDayOfYear(1) : LocalDate.parse(query.getStartDate());
        var end = query.getEndDate() == null ? today : LocalDate.parse(query.getEndDate());
        QualityScope.require(!end.isBefore(start), "date_range");
        return aggregation.projects(query.getProjectId(), QualityHomeRules.at(start.atStartOfDay()),
            QualityHomeRules.at(end.plusDays(1).atStartOfDay())).flatMapMany(rows ->
            directory.projects().filter(project -> query.getProjectId() == null || query.getProjectId().equals(project.getId()))
                .map(project -> {
                    var row = rows.getOrDefault(project.getId(), QualityHomeAggregation.result(null, null, 0));
                    row.put("projectId", project.getId()); row.put("projectName", project.getName());
                    return row;
                }));
    }

    private static Map<String, Object> trendResult(Map<String, Object> summary, List<QualityHomeRules.Window> windows,
                                                  List<Map<String, Object>> slices) {
        summary.put("xAxis", windows.stream().map(QualityHomeRules.Window::label).toList());
        summary.put("taskCompleteRateSeries", slices.stream().map(row -> row.get("taskCompleteRate") == null ? 0D : row.get("taskCompleteRate")).toList());
        summary.put("qualifiedRateSeries", slices.stream().map(row -> row.get("qualifiedRate") == null ? 0D : row.get("qualifiedRate")).toList());
        return summary;
    }
}
