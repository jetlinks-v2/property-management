package org.jetlinks.pro.patrol.service.statistics;

import lombok.AllArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.jetlinks.pro.patrol.service.PatrolExecutionService;
import org.jetlinks.pro.patrol.service.PatrolTaskService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

import static org.jetlinks.pro.patrol.service.statistics.PatrolOverview.*;

/** 统计、下钻与导出的统一事实读取边界。沿计划资产授权，批量读取执行事实，不访问其他系统的业务表。 */
@Service
@AllArgsConstructor
public class PatrolOverviewService {
    static final int TASK_LIMIT = 50000;
    static final int EXECUTION_LIMIT = 100000;
    private final PatrolTaskService tasks;
    private final PatrolExecutionService executions;

    public Mono<PatrolOverviewAggregation.Result> aggregate(Range range) {
        return facts(range, null).publishOn(Schedulers.parallel()).map(facts ->
                PatrolOverviewAggregation.aggregate(range, facts.periods(), facts.executions(), System.currentTimeMillis()));
    }

    /** 点位日是跨角色的派生结果，先对完整有界授权事实聚合，再稳定分页，不能按时段分页后计算。 */
    public Mono<PagerResult<PointDay>> drill(Drill request) {
        return aggregate(request.range()).map(result -> {
            var rows = result.points().stream().filter(point -> PatrolOverviewAggregation.matches(point, request.metric())).toList();
            int start = (int) Math.min(rows.size(), (long) request.pageIndex() * request.pageSize());
            int end = Math.min(rows.size(), start + request.pageSize());
            var paging = new QueryParamEntity();
            paging.setPageIndex(request.pageIndex()); paging.setPageSize(request.pageSize());
            return PagerResult.of(rows.size(), rows.subList(start, end), paging);
        });
    }

    public Mono<Detail> detail(DetailRequest request) {
        return facts(request.range(), request.pointId());
    }

    private Mono<Detail> facts(Range range, String pointId) {
        if (range.from() == null || range.to() == null || range.to().isBefore(range.from())
                || range.to().isAfter(range.from().plusDays(365)))
            return Mono.error(new BusinessException("error.patrol.statistics.range"));
        QueryParamEntity query = QueryParamEntity.newQuery()
                .nest(nest -> nest
                        .nest().gte("belongDate", range.from().toString()).lte("belongDate", range.to().toString()).end()
                        .orNest().isNull("belongDate").gte("businessDate", range.from().toString())
                        .lte("businessDate", range.to().toString()).end())
                .orderByAsc("belongDate").orderByAsc("projectId").orderByAsc("pointId")
                .orderByAsc("startTime").orderByAsc("id").getParam();
        if (range.categoryIds() != null && !range.categoryIds().isEmpty()) query.toQuery().in("categoryId", range.categoryIds());
        if (range.levelIds() != null && !range.levelIds().isEmpty()) query.toQuery().in("levelId", range.levelIds());
        if (range.projectId() != null) {
            if (range.projectId().isEmpty()) query.toQuery().isNull("projectId");
            else query.toQuery().is("projectId", range.projectId());
        }
        if (pointId != null) query.toQuery().is("pointId", pointId);
        query.setPageIndex(0); query.setPageSize(TASK_LIMIT + 1); query.setPaging(true);
        // 包括日期回退的 OR 条件先完整嵌套，再注入计划权限，避免 OR 放宽授权范围。
        return AssetsHolder.injectQueryParam(query, PatrolAssetType.patrolPlan, "planId")
                .flatMapMany(tasks::query).collectList().flatMap(periods -> {
                    if (periods.size() > TASK_LIMIT) return Mono.error(new BusinessException("error.patrol.statistics.too_many"));
                    // taskId 集合已授权；关联执行只能来自该集合，不另开未经授权的全量执行查询。
                    return Flux.fromIterable(periods).map(PatrolTaskEntity::getId).buffer(500)
                            .concatMap(ids -> {
                                var executionQuery = QueryParamEntity.newQuery().in("taskId", ids)
                                        .orderByAsc("receiveTime").orderByAsc("id").getParam();
                                executionQuery.setPageIndex(0); executionQuery.setPageSize(EXECUTION_LIMIT + 1);
                                executionQuery.setPaging(true);
                                return executions.query(executionQuery);
                            }).take(EXECUTION_LIMIT + 1L).collectList().map(facts -> {
                                if (facts.size() > EXECUTION_LIMIT) throw new BusinessException("error.patrol.statistics.too_many");
                                return new Detail(periods, facts);
                            });
                });
    }
}
