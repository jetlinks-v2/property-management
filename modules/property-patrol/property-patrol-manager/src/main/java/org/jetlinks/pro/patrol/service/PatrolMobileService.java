package org.jetlinks.pro.patrol.service;

import lombok.AllArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.jetlinks.pro.patrol.integration.PatrolScope;
import org.jetlinks.pro.patrol.integration.SaasPersonDirectory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.List;

/** 手机端只读业务编排；AssetsHolder 决定计划范围，当前成员及岗位决定“我的任务”。 */
@Service
@AllArgsConstructor
public class PatrolMobileService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int LIMIT = 1000;
    private final PatrolScope scope;
    private final SaasPersonDirectory people;
    private final PatrolPlanService plans;
    private final PatrolTaskService tasks;
    private final PatrolExecutionService executions;
    private final PatrolExecutionWorkflow workflow;

    public record MobileTask(PatrolTaskEntity task, boolean executable, String reason) {}

    public Mono<List<MobileTask>> tasks(LocalDate date) {
        return tasks(date, true);
    }

    /** 全员仅扩大到当前有权访问的计划；其他岗位的任务仍不可执行。 */
    public Mono<List<MobileTask>> tasks(LocalDate date, boolean mine) {
        return ReactiveAuthenticationHolder.get().flatMap(auth -> scope.current().flatMap(current ->
                people.assertMembers(List.of(auth.getUser().getId()))
                    .thenMany(authorizedPlans(current.key()))
                    .collectList()
                    .flatMap(ids -> ids.isEmpty() ? Mono.just(List.<MobileTask>of())
                        : taskRows(ids, date).flatMapMany(Flux::fromIterable)
                            .flatMapSequential(task -> inspect(task, auth.getUser().getId(), mine), 8)
                            .collectList())))
                .as(MonoTracer.create("/patrol/mobile/tasks"));
    }

    private Flux<String> authorizedPlans(String scopeKey) {
        var query = QueryParamEntity.newQuery().and("scopeKey", scopeKey).getParam();
        query.setPaging(false);
        // 业务应用范围不能替代资产授权；两者必须同时满足。
        return AssetsHolder.injectQueryParam(query, PatrolAssetType.patrolPlan, "id")
            .flatMapMany(plans::query).take(LIMIT + 1L).collectList()
            .flatMapMany(rows -> rows.size() > LIMIT ? Flux.error(tooMany())
                : Flux.fromIterable(rows).map(row -> row.getId()));
    }

    private Mono<List<PatrolTaskEntity>> taskRows(List<String> ids, LocalDate date) {
        var query = QueryParamEntity.newQuery().in("planId", ids)
            .lt("startTime", date.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli())
            .gt("endTime", date.atStartOfDay(ZONE).toInstant().toEpochMilli())
            .orderByAsc("startTime").orderByAsc("id").getParam();
        query.setPaging(false);
        // 有界读取后完整过滤本人任务，避免先分页再过滤造成空页或错误总数。
        return tasks.query(query).take(LIMIT + 1L).collectList()
            .flatMap(rows -> rows.size() > LIMIT ? Mono.error(tooMany()) : Mono.just(rows));
    }

    private Mono<MobileTask> inspect(PatrolTaskEntity task, String userId, boolean mine) {
        return workflow.checkEligibility(task, userId, Instant.now())
            .map(key -> new MobileTask(task, true, ""))
            .onErrorResume(BusinessException.class, error ->
                // 原因是客户端契约，不能使用受当前语言影响的异常展示文本。
                mine && "error.patrol.execution.unassigned".equals(error.getI18nCode()) ? Mono.empty()
                    : Mono.just(new MobileTask(task, false, error.getI18nCode())));
    }

    public Mono<PagerResult<PatrolExecutionEntity>> history(QueryParamEntity request) {
        var query = request.clone();
        query.setPaging(true);
        query.setPageIndex(Math.max(0, query.getPageIndex()));
        query.setPageSize(Math.max(1, Math.min(50, query.getPageSize())));
        return ReactiveAuthenticationHolder.get().flatMap(auth -> scope.current().flatMap(current ->
            people.assertMembers(List.of(auth.getUser().getId()))
                .thenMany(authorizedPlans(current.key())).collectList()
                .flatMap(ids -> {
                    query.toNestQuery().and("userId", auth.getUser().getId()).in("planId", ids);
                    return ids.isEmpty() ? Mono.just(PagerResult.of(0, List.<PatrolExecutionEntity>of(), query))
                        : executions.queryPager(query);
                })));
    }

    private BusinessException tooMany() { return new BusinessException("error.patrol.mobile.too_many"); }
}
