package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

/** 批次元数据按项目读取，房屋及入账明细再按space资产读取；不开放通用写接口。 */
@Service
@RequiredArgsConstructor
public class CarryForwardRunAccessService {
    private static final AssetType SPACE = AssetType.of("space");
    private final FinanceScope scope;
    private final CarryForwardRunService runner;
    private final ReactiveRepository<CarryForwardPlanEntity, String> plans;
    private final ReactiveRepository<CarryForwardRunEntity, String> runs;
    private final ReactiveRepository<CarryForwardWorkEntity, String> works;
    private final ReactiveRepository<CarryForwardItemEntity, String> items;

    public Mono<PagerResult<CarryForwardRunEntity>> query(
            String project, String plan, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(plan(project, plan))
                .then(
                        Mono.defer(
                                () -> {
                                    var q = page(input);
                                    q.toNestQuery(
                                            n -> n.and("projectId", project).and("planId", plan));
                                    return QueryHelper.queryPager(q, runs::createQuery);
                                }));
    }

    /** 保留已逻辑删除计划的批次历史，但必须同时匹配project、plan、run。 */
    public Mono<CarryForwardRunEntity> detail(String project, String plan, String run) {
        return scope.project(project, CrudAssetPermission.read)
                .then(plan(project, plan))
                .then(
                        runs.createQuery()
                                .where("projectId", project)
                                .and("planId", plan)
                                .and("id", run)
                                .fetchOne()
                                .switchIfEmpty(Mono.error(error("carry_run_not_found", 404))));
    }

    public Mono<PagerResult<CarryForwardWorkEntity>> works(
            String project, String plan, String run, QueryParamEntity input) {
        return detail(project, plan, run)
                .then(
                        Mono.defer(
                                () -> {
                                    var q = page(input);
                                    q.toNestQuery(
                                            n -> n.and("projectId", project).and("runId", run));
                                    return AssetsHolder.injectQueryParam(q, SPACE, "spaceId")
                                            .flatMap(
                                                    secured ->
                                                            QueryHelper.queryPager(
                                                                    secured, works::createQuery));
                                }));
    }

    public Mono<CarryForwardWorkEntity> work(String project, String plan, String run, String work) {
        return detail(project, plan, run)
                .then(
                        works.createQuery()
                                .where("projectId", project)
                                .and("runId", run)
                                .and("id", work)
                                .fetchOne()
                                .switchIfEmpty(Mono.error(error("carry_work_not_found", 404))))
                .flatMap(
                        row ->
                                AssetsHolder.assertPermission(
                                                SPACE, row.getSpaceId(), CrudAssetPermission.read)
                                        .thenReturn(row));
    }

    public Mono<PagerResult<CarryForwardItemEntity>> items(
            String project, String plan, String run, String work, QueryParamEntity input) {
        return work(project, plan, run, work)
                .flatMap(
                        row -> {
                            var q = page(input);
                            q.toNestQuery(
                                    n ->
                                            n.and("projectId", project)
                                                    .and("planId", plan)
                                                    .and("runId", run)
                                                    .and("receivableId", row.getReceivableId())
                                                    .and("spaceId", row.getSpaceId()));
                            return QueryHelper.queryPager(q, items::createQuery);
                        });
    }

    public Mono<CarryForwardRunEntity> retry(String project, String plan, String run) {
        return detail(project, plan, run).then(runner.retry(project, run));
    }

    private Mono<CarryForwardPlanEntity> plan(String project, String plan) {
        return plans.createQuery()
                .where("id", plan)
                .and("projectId", project)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("carry_plan_not_found", 404)));
    }

    private static QueryParamEntity page(QueryParamEntity input) {
        var q = SubjectService.page(input == null ? new QueryParamEntity() : input);
        q.setForUpdate(false);
        if (q.getSorts().isEmpty()) q.toQuery().orderByDesc("createTime").orderByDesc("id");
        return q;
    }
}
