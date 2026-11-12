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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** 手机端只读业务编排；AssetsHolder 决定计划范围，当前成员及岗位决定“我的任务”。 */
@Service
@AllArgsConstructor
public class PatrolMobileService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int LIMIT = 1000;
    private final PatrolScope scope;
    private final SaasPersonDirectory people;
    private final PatrolTaskService tasks;
    private final PatrolPointService points;
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
                    .then(taskRows(current.key(), date))
                    .flatMapMany(rows -> workflow.listEligibility(rows, auth.getUser().getId(), Instant.now())
                            .flatMapMany(check -> Flux.fromIterable(rows)
                                    .flatMapSequential(task -> inspect(task, check.apply(task), mine), 8)))
                    .collectList()))
                .as(MonoTracer.create("/patrol/mobile/tasks"));
    }

    /** NFC编码仅收窄已经过项目、资产和员工资格校验的任务；不能凭卡号读取其他点位。 */
    public Mono<List<MobileTask>> tasks(LocalDate date, boolean mine, String nfcCode) {
        if (nfcCode == null) return tasks(date, mine);
        String code = nfcCode.trim();
        if (code.isEmpty() || code.length() > 128) return Mono.error(new BusinessException("error.patrol.nfc_invalid", 400));
        return tasks(date, mine).flatMap(rows -> {
            var ids = rows.stream().map(row -> row.task().getPointId()).distinct().toList();
            if (ids.isEmpty()) return Mono.just(List.of());
            return points.findById(ids)
                    .filter(point -> code.equals(point.getNfcCode()))
                    .map(point -> point.getId()).collectList()
                    .map(matched -> rows.stream().filter(row -> matched.contains(row.task().getPointId())).toList());
        });
    }

    private Mono<QueryParamEntity> authorizedFactQuery(String scopeKey, QueryParamEntity query) {
        var planScope = QueryParamEntity.newQuery().and("scopeKey", scopeKey).getParam();
        query.toNestQuery().and("planId", "patrol-project-patrolPlan", planScope.getTerms());
        // 直接向事实的 planId 注入授权，与应用范围并列；嵌套同种计划子查询会遮蔽关联别名。
        return AssetsHolder.injectQueryParam(query, PatrolAssetType.patrolPlan, "planId");
    }

    private Mono<List<PatrolTaskEntity>> taskRows(String scopeKey, LocalDate date) {
        var query = QueryParamEntity.newQuery()
            .lt("startTime", date.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli())
            .gt("endTime", date.atStartOfDay(ZONE).toInstant().toEpochMilli())
            .orderByAsc("startTime").orderByAsc("id").getParam();
        query.setPaging(true);
        query.setPageIndex(0);
        query.setPageSize(LIMIT + 1);
        // 授权计划作为数据库子查询；数量限制只检查当天任务，不受无关历史计划数量影响。
        return authorizedFactQuery(scopeKey, query)
            .flatMap(authorized -> tasks.query(authorized).take(LIMIT + 1L).collectList())
            .flatMap(rows -> rows.size() > LIMIT ? Mono.error(tooMany()) : Mono.just(rows));
    }

    private Mono<MobileTask> inspect(PatrolTaskEntity task, Mono<String> eligibility, boolean mine) {
        return eligibility
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
                .then(Mono.defer(() -> {
                    query.toNestQuery().and("userId", auth.getUser().getId());
                    return authorizedFactQuery(current.key(), query).flatMap(executions::queryPager);
                }))));
    }

    private BusinessException tooMany() { return new BusinessException("error.patrol.mobile.too_many"); }
}
