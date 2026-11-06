package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;

/** 每次仅关闭最早已到期账期；配置行锁持有到月结、任务摘要和节流点全部提交。 */
@Service
@RequiredArgsConstructor
public class MonthClosingAutomaticService {
    private final FinanceScope scope;
    private final MonthClosingScheduleService schedules;
    private final MonthClosingService closing;
    private final FinancialPeriodGate gate;
    private final ReactiveRepository<MonthClosingScheduleEntity, String> plans;
    private final ReactiveRepository<MonthClosingAutomaticRunEntity, String> runs;
    private final ReactiveRepository<AccountingPeriodEntity, String> periods;
    private final ReactiveRepository<MonthClosingOperationEntity, String> operations;

    @Transactional
    public Mono<MonthClosingAutomaticRunEntity> execute(
            MonthClosingScheduleEntity candidate, Instant now, ZoneId zone) {
        return scope.project(candidate.getProjectId(), CrudAssetPermission.save)
                .then(TenantHolder.currentId().defaultIfEmpty(""))
                .flatMap(
                        tenant -> {
                            if (!Objects.equals(tenant, candidate.getTenantId()))
                                return Mono.error(error("task_tenant_invalid", 409));
                            return schedules
                                    .row(candidate.getProjectId(), tenant, true)
                                    .filter(row -> executable(row, candidate, now))
                                    .flatMap(
                                            row ->
                                                    gate.lock(row.getProjectId())
                                                            .then(first(row))
                                                            .flatMap(
                                                                    period ->
                                                                            close(
                                                                                    row, period,
                                                                                    now, zone)));
                        })
                .as(MonoTracer.create("property.finance.month-closing.automatic.execute"));
    }

    private Mono<MonthClosingAutomaticRunEntity> close(
            MonthClosingScheduleEntity plan,
            AccountingPeriodEntity period,
            Instant now,
            ZoneId zone) {
        LocalDate today = now.atZone(zone).toLocalDate();
        if (MonthClosingScheduleDates.due(plan.getMode(), plan.getDay(), period.getEndDate())
                .isAfter(today)) return Mono.empty();
        // 人工反结是明确的人工决策，必须重新保存调度才允许再次自动关闭该期。
        return operations
                .createQuery()
                .where("projectId", plan.getProjectId())
                .and("tenantId", plan.getTenantId())
                .and("accountMonth", period.getAccountMonth())
                .and("action", "REOPEN")
                .and("deleted", false)
                .gte("createTime", plan.getModifyTime())
                .count()
                .flatMap(
                        count -> {
                            if (count > 0) return Mono.error(error("month_schedule_reopened", 409));
                            return closing.preview(
                                            plan.getProjectId(),
                                            period.getAccountMonth(),
                                            "CLOSE",
                                            today)
                                    .flatMap(
                                            preview ->
                                                    closing.submit(
                                                            plan.getProjectId(),
                                                            period.getAccountMonth(),
                                                            "CLOSE",
                                                            new MonthClosingService.Submission(
                                                                    UUID.randomUUID().toString(),
                                                                    preview.version(),
                                                                    preview.token(),
                                                                    "自动月结 / Automatic monthly"
                                                                        + " close"),
                                                            today))
                                    .flatMap(
                                            result -> {
                                                var run =
                                                        run(
                                                                plan,
                                                                period.getAccountMonth(),
                                                                "SUCCEEDED",
                                                                null,
                                                                now);
                                                run.setOperationId(result.operationId());
                                                return runs.insert(run)
                                                        .then(throttle(plan, now.plusSeconds(60)))
                                                        .thenReturn(run);
                                            });
                        });
    }

    /** 失败必须在原结账事务已回滚后调用；持久记录失败同样向任务层传播，不能吞掉。 */
    @Transactional
    public Mono<MonthClosingAutomaticRunEntity> failed(
            MonthClosingScheduleEntity candidate, Instant now, String errorCode) {
        return schedules
                .row(candidate.getProjectId(), candidate.getTenantId(), true)
                .filter(row -> executable(row, candidate, now))
                .flatMap(
                        row ->
                                first(row)
                                        .map(Optional::of)
                                        .defaultIfEmpty(Optional.empty())
                                        .flatMap(
                                                period -> {
                                                    var run =
                                                            run(
                                                                    row,
                                                                    period.map(
                                                                                    AccountingPeriodEntity
                                                                                            ::getAccountMonth)
                                                                            .orElse(null),
                                                                    "FAILED",
                                                                    errorCode,
                                                                    now);
                                                    return runs.insert(run)
                                                            .then(
                                                                    throttle(
                                                                            row,
                                                                            now.plusSeconds(3600)))
                                                            .thenReturn(run);
                                                }));
    }

    private Mono<AccountingPeriodEntity> first(MonthClosingScheduleEntity plan) {
        var query = new QueryParamEntity();
        query.setPaging(true);
        query.setPageSize(1);
        query.toQuery().orderByAsc("accountMonth");
        return periods.createQuery()
                .setParam(query)
                .where("projectId", plan.getProjectId())
                .and("tenantId", plan.getTenantId())
                .and("status", "OPEN")
                .and("deleted", false)
                .fetchOne();
    }

    private boolean executable(
            MonthClosingScheduleEntity row, MonthClosingScheduleEntity expected, Instant now) {
        return Boolean.TRUE.equals(row.getEnabled())
                && Objects.equals(row.getVersion(), expected.getVersion())
                && row.getNextAttemptAt() != null
                && row.getNextAttemptAt() <= now.toEpochMilli();
    }

    private Mono<Void> throttle(MonthClosingScheduleEntity plan, Instant next) {
        return plans.createUpdate()
                .set("nextAttemptAt", next.toEpochMilli())
                .where("id", plan.getId())
                .and("version", plan.getVersion())
                .execute()
                .flatMap(SubjectService::changed);
    }

    private MonthClosingAutomaticRunEntity run(
            MonthClosingScheduleEntity plan,
            String month,
            String status,
            String code,
            Instant now) {
        var run = new MonthClosingAutomaticRunEntity();
        run.setId(UUID.randomUUID().toString());
        run.setVersion(0L);
        run.setDeleted(false);
        run.setCreateTime(now.toEpochMilli());
        run.setModifyTime(now.toEpochMilli());
        // 委托操作者来自持久配置；失败时没有可用登录，不能虚构system身份来通过资产校验。
        run.setModifierId(plan.getModifierId());
        run.setModifierName(plan.getModifierName());
        run.setTenantId(plan.getTenantId());
        run.setProjectId(plan.getProjectId());
        run.setScheduleVersion(plan.getVersion());
        run.setAccountMonth(month);
        run.setStatus(status);
        run.setErrorCode(code);
        return run;
    }
}
