package org.jetlinks.pro.smartpark.handover.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 首页与问题列表共享授权选择器；统计按数据库聚合，不收集项目全部问题。 */
@Service
@RequiredArgsConstructor
public class HandoverStatisticsService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final HandoverProblemService problems;
    private final HandoverProblemRepository repository;
    private final HandoverRecordRepository records;
    private final HandoverScope scope;
    private final HandoverProcessService processes;
    private final QueryHelper helper;

    public Mono<Map<String, Object>> statistics(HandoverQuery input, Integer selectedYear) {
        int year = selectedYear == null ? LocalDate.now(ZONE).getYear() : selectedYear;
        HandoverRules.require(year >= 1970 && year <= 9998, "request_invalid");
        HandoverRules.require(HandoverRules.hasText(input.getProjectId()), "project_required");
        long from = boundary(year, 1);
        long until = boundary(year + 1, 1) - 1;
        input.setInspectionTimeFrom(
                input.getInspectionTimeFrom() == null
                        ? from
                        : Math.max(from, input.getInspectionTimeFrom()));
        input.setInspectionTimeTo(
                input.getInspectionTimeTo() == null
                        ? until
                        : Math.min(until, input.getInspectionTimeTo()));
        return problems.selector(input)
                .flatMap(
                        selector ->
                                Mono.zip(
                                                problems.counts(input),
                                                categories(selector),
                                                monthly(selector, year),
                                                materialCount(input.getProjectId(), from, until),
                                                processes.get(input.getProjectId()))
                                        .map(
                                                result -> {
                                                    Map<String, Object> response =
                                                            new LinkedHashMap<>(result.getT1());
                                                    response.put("projectCount", 1L);
                                                    response.put("materialCount", result.getT4());
                                                    response.put("byCategory", result.getT2());
                                                    response.put("monthly", result.getT3());
                                                    response.put(
                                                            "stages", result.getT5().getStages());
                                                    response.put("year", year);
                                                    return response;
                                                }));
    }

    private Mono<Long> materialCount(String projectId, long from, long until) {
        var query =
                QueryParamEntity.newQuery()
                        .where("stage", "material")
                        .and("createTime", "gte", from)
                        .and("createTime", "lte", until)
                        .getParam();
        return scope.current()
                .flatMap(context -> scope.query(query, context, projectId))
                .flatMap(selector -> records.createQuery().setParam(selector).count())
                .map(Integer::longValue);
    }

    private Mono<List<CategoryPoint>> categories(QueryParamEntity selector) {
        QueryParamEntity grouped = selector.clone();
        grouped.setPaging(false);
        grouped.setSorts(List.of());
        String sql =
                "select category_id as id,max(category_name) as name,count(*) as total from"
                    + " sp_handover_problem group by category_id order by total desc,category_id";
        // 分类数量有明确上限，超限报错而不是悄悄省略图表分类。
        return helper.select(sql, CategoryPoint::new)
                .where(grouped)
                .fetch()
                .take(1001)
                .collectList()
                .doOnNext(points -> HandoverRules.require(points.size() <= 1000, "history_limit"));
    }

    private Mono<List<Map<String, Object>>> monthly(QueryParamEntity selector, int year) {
        // 固定12个月，各自由DB count处理，时间边界统一为上海时区的毫秒值。
        return Flux.range(1, 12)
                .concatMap(
                        month -> {
                            long from = boundary(year, month);
                            long until =
                                    month == 12 ? boundary(year + 1, 1) : boundary(year, month + 1);
                            var query =
                                    selector.clone()
                                            .toNestQuery()
                                            .and("inspectionTime", "gte", from)
                                            .and("inspectionTime", "lt", until)
                                            .getParam();
                            return Mono.zip(
                                            repository.createQuery().setParam(query).count(),
                                            repository
                                                    .createQuery()
                                                    .setParam(
                                                            query.clone()
                                                                    .toNestQuery()
                                                                    .and("status", 3)
                                                                    .getParam())
                                                    .count())
                                    .map(
                                            counts ->
                                                    Map.<String, Object>of(
                                                            "month",
                                                            String.format("%04d-%02d", year, month),
                                                            "total",
                                                            counts.getT1().longValue(),
                                                            "completed",
                                                            counts.getT2().longValue()));
                        })
                .collectList();
    }

    private static long boundary(int year, int month) {
        return LocalDate.of(year, month, 1).atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    @Getter
    @Setter
    public static class CategoryPoint {
        private String id;
        private String name;
        private long total;
    }
}
