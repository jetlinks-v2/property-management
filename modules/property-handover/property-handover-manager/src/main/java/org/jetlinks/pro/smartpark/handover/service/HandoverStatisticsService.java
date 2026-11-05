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
    private final HandoverProcessRepository processes;
    private final HandoverItemRepository items;
    private final HandoverDirectory directory;
    private final QueryHelper helper;

    public Mono<Map<String, Object>> statistics(HandoverQuery input, Integer selectedYear) {
        int year = selectedYear == null ? LocalDate.now(ZONE).getYear() : selectedYear;
        HandoverRules.require(year >= 1970 && year <= 9998, "request_invalid");
        // 目录命令已按当前认证/资产权限过滤；逐项目复用问题选择器，施工身份仍受责任单位限制。
        Flux<String> projects =
                HandoverRules.hasText(input.getProjectId())
                        ? directory.project(input.getProjectId()).map(org -> org.getId()).flux()
                        : directory.projects().map(org -> org.getId()).distinct();
        return projects.concatMap(project -> perProject(project, year, input.getView()))
                .reduce(empty(year), HandoverStatisticsService::merge);
    }

    private Mono<Map<String, Object>> perProject(String projectId, int year, String view) {
        var query = new HandoverQuery();
        query.setProjectId(projectId);
        query.setView(view);
        return problems.selector(query)
                .flatMap(
                        selector ->
                                directory
                                        .currentEmployee()
                                        .hasElement()
                                        .flatMap(
                                                employee ->
                                                        Mono.zip(
                                                                        problems.counts(query),
                                                                        categories(selector),
                                                                        monthly(selector, year),
                                                                        employee
                                                                                ? materialCount(
                                                                                        projectId)
                                                                                : Mono.just(0L),
                                                                        employee
                                                                                ? usedProject(
                                                                                        projectId)
                                                                                : Mono.just(false),
                                                                        years(selector))
                                                                .map(
                                                                        result -> {
                                                                            Map<String, Object>
                                                                                    value =
                                                                                            new LinkedHashMap<>(
                                                                                                    result
                                                                                                            .getT1());
                                                                            value.put(
                                                                                    "projectCount",
                                                                                    result.getT5()
                                                                                                    || ((Number)
                                                                                                                            value
                                                                                                                                    .get(
                                                                                                                                            "total"))
                                                                                                                    .longValue()
                                                                                                            > 0
                                                                                            ? 1L
                                                                                            : 0L);
                                                                            value.put(
                                                                                    "materialCount",
                                                                                    result.getT4());
                                                                            value.put(
                                                                                    "byCategory",
                                                                                    result.getT2());
                                                                            value.put(
                                                                                    "monthly",
                                                                                    result.getT3());
                                                                            value.put("year", year);
                                                                            value.put(
                                                                                    "years",
                                                                                    result.getT6());
                                                                            return value;
                                                                        })));
    }

    private Mono<QueryParamEntity> projectQuery(String projectId) {
        return scope.current().flatMap(c -> scope.query(new QueryParamEntity(), c, projectId));
    }

    private Mono<Long> materialCount(String projectId) {
        return projectQuery(projectId)
                .flatMap(
                        q ->
                                items.createQuery()
                                        .setParam(q)
                                        .and("source", "MATERIAL_TRANSFER")
                                        .count())
                .map(Integer::longValue);
    }

    private Mono<Boolean> usedProject(String projectId) {
        return projectQuery(projectId)
                .flatMap(
                        q ->
                                Mono.zip(
                                        processes.createQuery().setParam(q).count(),
                                        records.createQuery().setParam(q).count(),
                                        items.createQuery()
                                                .setParam(q)
                                                .and("source", "MATERIAL_TRANSFER")
                                                .count()))
                .map(counts -> counts.getT1() + counts.getT2() + counts.getT3() > 0);
    }

    private Mono<List<Integer>> years(QueryParamEntity selector) {
        var query = selector.clone();
        query.setPaging(false);
        query.setSorts(List.of());
        return helper.select(
                        "select distinct extract(year from"
                            + " timezone('Asia/Shanghai',to_timestamp(inspection_time/1000.0)))::integer"
                            + " as year from sp_handover_problem where inspection_time is not null",
                        YearPoint::new)
                .where(query)
                .fetch()
                .map(YearPoint::getYear)
                .sort(java.util.Comparator.reverseOrder())
                .collectList();
    }

    private static Map<String, Object> empty(int year) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (String key :
                List.of(
                        "total",
                        "pending",
                        "processing",
                        "review",
                        "completed",
                        "rejected",
                        "projectCount",
                        "materialCount")) value.put(key, 0L);
        value.put("byCategory", List.of());
        value.put("years", List.of(year));
        value.put("year", year);
        value.put(
                "monthly",
                java.util.stream.IntStream.rangeClosed(1, 12)
                        .mapToObj(
                                month ->
                                        Map.<String, Object>of(
                                                "month",
                                                String.format("%04d-%02d", year, month),
                                                "total",
                                                0L,
                                                "completed",
                                                0L))
                        .toList());
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> merge(Map<String, Object> total, Map<String, Object> next) {
        for (String key :
                List.of(
                        "total",
                        "pending",
                        "processing",
                        "review",
                        "completed",
                        "rejected",
                        "projectCount",
                        "materialCount"))
            total.put(
                    key,
                    ((Number) total.get(key)).longValue() + ((Number) next.get(key)).longValue());
        Map<String, CategoryPoint> groups = new LinkedHashMap<>();
        for (var list :
                List.of(
                        (List<CategoryPoint>) total.get("byCategory"),
                        (List<CategoryPoint>) next.get("byCategory")))
            for (var point : list) {
                String key = java.util.Objects.toString(point.id, "");
                var target =
                        groups.computeIfAbsent(
                                key,
                                id -> {
                                    var p = new CategoryPoint();
                                    p.id = point.id;
                                    p.name = point.name;
                                    return p;
                                });
                target.total += point.total;
            }
        total.put(
                "byCategory",
                groups.values().stream()
                        .sorted(
                                java.util.Comparator.comparingLong(CategoryPoint::getTotal)
                                        .reversed())
                        .toList());
        var months = (List<Map<String, Object>>) total.get("monthly");
        var additional = (List<Map<String, Object>>) next.get("monthly");
        var merged = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < 12; i++) {
            var value = new LinkedHashMap<>(months.get(i));
            for (String key : List.of("total", "completed"))
                value.put(
                        key,
                        ((Number) value.get(key)).longValue()
                                + ((Number) additional.get(i).get(key)).longValue());
            merged.add(value);
        }
        total.put("monthly", merged);
        var years = new java.util.TreeSet<Integer>(java.util.Comparator.reverseOrder());
        years.addAll((List<Integer>) total.get("years"));
        years.addAll((List<Integer>) next.get("years"));
        total.put("years", List.copyOf(years));
        return total;
    }

    @Getter
    @Setter
    public static class YearPoint {
        private Integer year;
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
                .doOnNext(
                        point -> {
                            if (!HandoverRules.hasText(point.getName())
                                    && HandoverRules.hasText(point.getId()))
                                point.setName(point.getId());
                        })
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
