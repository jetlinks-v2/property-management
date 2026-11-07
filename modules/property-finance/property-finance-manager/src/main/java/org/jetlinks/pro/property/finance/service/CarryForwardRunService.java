package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.carry.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.springframework.stereotype.Service;

import reactor.core.publisher.*;

import java.time.*;
import java.util.*;

/** 受权执行器：每次最多一页，恢复保留槽位/业务日期/账户集合；后台委托由单独入口提供。 */
@Service
@RequiredArgsConstructor
public class CarryForwardRunService {
    private static final int PAGE_SIZE = 32;
    private final FinanceScope scope;
    private final CarryForwardRunStore store;
    private final CarryForwardApplyService application;
    private final ReactiveRepository<CarryForwardRunEntity, String> runs;
    private final ReactiveRepository<CarryForwardPlanEntity, String> plans;
    private final ReactiveRepository<ReceivableEntity, String> bills;

    Mono<CarryForwardRunEntity> prepare(String project, String plan, Instant now) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                plans.createQuery()
                                        .where("id", plan)
                                        .and("projectId", project)
                                        .and("deleted", false)
                                        .and("enabled", true)
                                        .fetchOne()
                                        .switchIfEmpty(
                                                Mono.error(error("carry_plan_not_found", 404)))
                                        .flatMap(
                                                row ->
                                                        Mono.justOrEmpty(
                                                                        CarryForwardRules.due(
                                                                                JSON.parseObject(
                                                                                        row
                                                                                                .getConfigJson(),
                                                                                        CarryForwardPlanInput
                                                                                                .class),
                                                                                now))
                                                                .flatMap(
                                                                        slot ->
                                                                                store.ensure(
                                                                                        row, slot,
                                                                                        auth))));
    }

    Mono<CarryForwardRunEntity> executeDue(String project, String plan, Instant now) {
        return prepare(project, plan, now).flatMap(run -> resume(project, run.getId()));
    }

    Mono<CarryForwardRunEntity> resume(String project, String id) {
        return scope.project(project, CrudAssetPermission.save)
                .then(row(project, id))
                .flatMap(
                        run ->
                                store.claim(id)
                                        .flatMap(owner -> batch(run, owner))
                                        .then(row(project, id)))
                .as(MonoTracer.create("/property/finance/carry-run/resume"));
    }

    Mono<CarryForwardRunEntity> retry(String project, String id) {
        return scope.project(project, CrudAssetPermission.save)
                .then(row(project, id))
                .flatMap(run -> scope.authenticated().flatMap(auth -> store.retry(id, auth)))
                .then(resume(project, id))
                .as(MonoTracer.create("/property/finance/carry-run/retry"));
    }

    private Mono<Void> batch(CarryForwardRunEntity run, String owner) {
        return currentPlan(run)
                .flatMap(
                        valid -> {
                            if (!valid) return cancel(run, owner);
                            return store.pending(run.getId(), PAGE_SIZE)
                                    .flatMap(
                                            pending ->
                                                    pending.isEmpty()
                                                            ? store.stagePage(
                                                                    run.getId(), owner, PAGE_SIZE)
                                                            : Mono.just(pending))
                                    .flatMap(
                                            page ->
                                                    Flux.fromIterable(page)
                                                            .concatMap(
                                                                    work ->
                                                                            attempt(
                                                                                    run, owner,
                                                                                    work))
                                                            .then(finishBatch(run, owner)));
                        });
    }

    private Mono<Void> attempt(
            CarryForwardRunEntity run, String owner, CarryForwardWorkEntity work) {
        // 只将金融尝试失败记入明细；租约或结果落库失败保留待处理状态，恢复时重放原入账请求。
        return store.heartbeat(run.getId(), owner)
                .then(
                        executeWork(run, work)
                                .timeout(Duration.ofSeconds(60))
                                .map(status -> new Outcome(status, null))
                                .onErrorResume(
                                        failure -> Mono.just(new Outcome("FAILED", code(failure)))))
                .flatMap(
                        outcome ->
                                store.outcome(
                                        run.getId(),
                                        owner,
                                        work,
                                        outcome.status(),
                                        outcome.code()));
    }

    private Mono<Void> finishBatch(CarryForwardRunEntity run, String owner) {
        return currentPlan(run)
                .flatMap(
                        valid -> {
                            if (!valid) return cancel(run, owner);
                            // 预存下一页使恢复不依赖内存游标；一轮最多执行PAGE_SIZE笔。
                            return store.stagePage(run.getId(), owner, PAGE_SIZE)
                                    .flatMap(
                                            next ->
                                                    store.finish(
                                                            run.getId(),
                                                            owner,
                                                            next.isEmpty(),
                                                            null));
                        });
    }

    private Mono<Void> cancel(CarryForwardRunEntity run, String owner) {
        return store.finish(run.getId(), owner, false, "error.property_finance_carry_plan_changed");
    }

    private Mono<String> executeWork(CarryForwardRunEntity run, CarryForwardWorkEntity work) {
        return store.accounts(run, work)
                .flatMapMany(
                        ids ->
                                Flux.fromIterable(
                                        ids.stream().map(id -> Objects.toString(id, "")).toList()))
                // Reactor不允许null信号，null账户单独转换为空串，仅在构造应用请求时还原。
                .concatMap(
                        account ->
                                application
                                        .apply(
                                                run.getProjectId(),
                                                new CarryForwardApplyService.Request(
                                                        run.getPlanId(),
                                                        run.getPlanVersion(),
                                                        run.getId(),
                                                        work.getReceivableId(),
                                                        account.isEmpty() ? null : account,
                                                        run.getSlot()),
                                                LocalDate.parse(run.getBusinessDate()))
                                        .flatMap(
                                                item ->
                                                        bills.findById(work.getReceivableId())
                                                                .map(
                                                                        bill ->
                                                                                new Applied(
                                                                                        item,
                                                                                        bill.getArrears()
                                                                                                        .signum()
                                                                                                == 0))))
                // 同房多个收费对象共享一笔应收；结清后立即停止，不能再向后面的账户发起扣款。
                .takeUntil(Applied::settled)
                .map(result -> "SUCCEEDED".equals(result.item().getStatus()))
                .reduce(false, (a, b) -> a || b)
                .map(success -> success ? "SUCCEEDED" : "SKIPPED");
    }

    private Mono<Boolean> currentPlan(CarryForwardRunEntity run) {
        return plans.createQuery()
                .where("id", run.getPlanId())
                .and("projectId", run.getProjectId())
                .and("deleted", false)
                .and("enabled", true)
                .and("version", run.getPlanVersion())
                .count()
                .map(count -> count == 1);
    }

    private Mono<CarryForwardRunEntity> row(String project, String id) {
        return runs.createQuery()
                .where("id", id)
                .and("projectId", project)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("carry_run_not_found", 404)));
    }

    private static String code(Throwable error) {
        var message = error.getMessage();
        return message != null && message.matches("error\\.property_finance_[a-z_]+")
                ? message
                : "error.property_finance_carry_run_failed";
    }

    private record Outcome(String status, String code) {}

    private record Applied(CarryForwardItemEntity item, boolean settled) {}
}
