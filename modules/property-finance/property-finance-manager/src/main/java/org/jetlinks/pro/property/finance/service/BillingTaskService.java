package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.BillingTaskEntity;
import org.jetlinks.pro.property.finance.entity.HouseSettingEntity;
import org.jetlinks.pro.property.finance.setting.BillingTaskKind;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

/** 持久任务协调器：SQL CAS租约竞争执行权，事务算费失败回滚后再记录失败。 */
@Service
@RequiredArgsConstructor
public class BillingTaskService {
    private static final AssetType SPACE = AssetType.of("space");
    private final ReactiveRepository<BillingTaskEntity, String> tasks;
    private final ReactiveRepository<HouseSettingEntity, String> settings;
    private final ReactiveRepository<
                    org.jetlinks.pro.property.finance.entity.ReceivableEntity, String>
            bills;
    private final ReceivableGenerationService generation;
    private final FinanceScope scope;

    /** 仅生命周期作业入口调用。清除调用上下文，逐项恢复持久化租户，权限来自已保存设置的系统委托。 */
    Mono<RunSummary> scan(BillingTaskKind kind, LocalDate date) {
        var configured =
                settings.createQuery()
                        .where("deleted", false)
                        .and("reviewState", "APPROVED")
                        .lte("effectiveStartDate", date.toString())
                        .fetch()
                        .flatMap(setting -> execute(setting, kind, date), 4, 1);
        // 历史导入根账不依赖收费设置；子账由根账沿来源链处理，避免重复计算。
        var historical =
                kind == BillingTaskKind.GENERATE
                        ? reactor.core.publisher.Flux.<TaskResult>empty()
                        : bills.createQuery()
                                .where("deleted", false)
                                .isNull("settingId")
                                .isNull("sourceReceivableId")
                                .fetch()
                                .flatMap(bill -> executeReceivable(bill, kind, date), 4, 1);
        return configured
                .concatWith(historical)
                .reduce(new RunSummary(0, 0, 0), RunSummary::add)
                .contextWrite(ignored -> Context.empty());
    }

    Mono<TaskResult> execute(HouseSettingEntity setting, BillingTaskKind kind, LocalDate date) {
        return ensureTask(setting, kind, date)
                .flatMap(
                        task ->
                                run(
                                        task,
                                        () ->
                                                generation.scheduled(
                                                        setting.getProjectId(),
                                                        setting.getId(),
                                                        date,
                                                        kind)))
                .as(MonoTracer.create("/property/finance/billing-task/execute"));
    }

    Mono<TaskResult> executeReceivable(
            org.jetlinks.pro.property.finance.entity.ReceivableEntity bill,
            BillingTaskKind kind,
            LocalDate date) {
        String key = "BILL:" + bill.getId() + ":" + kind.name() + ":" + date;
        String id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        return tasks.findById(id)
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    var task = new BillingTaskEntity();
                                    task.setId(id);
                                    task.setProjectId(bill.getProjectId());
                                    task.setSpaceId(bill.getSpaceId());
                                    task.setFullName(bill.getFullName());
                                    task.setReceivableId(bill.getId());
                                    task.setTenantId(bill.getTenantId());
                                    task.setKind(kind.name());
                                    task.setBusinessDate(date.toString());
                                    task.setStatus("PENDING");
                                    task.setAttempts(0);
                                    task.setLeaseUntil(0L);
                                    task.setCreateTime(System.currentTimeMillis());
                                    return tasks.insert(task)
                                            .thenReturn(task)
                                            .onErrorResume(
                                                    failure ->
                                                            tasks.findById(id)
                                                                    .switchIfEmpty(
                                                                            Mono.error(failure)));
                                }))
                .flatMap(
                        task ->
                                run(
                                        task,
                                        () ->
                                                generation.scheduledReceivable(
                                                        bill.getProjectId(),
                                                        bill.getId(),
                                                        date,
                                                        kind)))
                .as(MonoTracer.create("property.finance.billing-task.execute-history"));
    }

    private Mono<TaskResult> run(
            BillingTaskEntity task,
            java.util.function.Supplier<Mono<ReceivableGenerationService.GenerationResult>>
                    action) {
        return claim(task)
                .flatMap(
                        owner -> {
                            Mono<ReceivableGenerationService.GenerationResult> run =
                                    Mono.defer(action);
                            if (task.getTenantId() != null && !task.getTenantId().isEmpty())
                                run =
                                        run.contextWrite(
                                                TenantHolder.makeCurrent(task.getTenantId()));
                            return run.timeout(Duration.ofMinutes(5))
                                    .flatMap(
                                            result ->
                                                    complete(task, owner, result)
                                                            .thenReturn(
                                                                    new TaskResult("SUCCEEDED")))
                                    .onErrorResume(
                                            failure ->
                                                    fail(task, owner, failure)
                                                            .thenReturn(new TaskResult("FAILED")));
                        })
                .defaultIfEmpty(new TaskResult("SKIPPED"));
    }

    private Mono<BillingTaskEntity> ensureTask(
            HouseSettingEntity setting, BillingTaskKind kind, LocalDate date) {
        String key = setting.getId() + ":" + kind.name() + ":" + date;
        String id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        return tasks.findById(id)
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    var task = new BillingTaskEntity();
                                    task.setId(id);
                                    task.setProjectId(setting.getProjectId());
                                    task.setSpaceId(setting.getSpaceId());
                                    task.setFullName(setting.getFullName());
                                    task.setSettingId(setting.getId());
                                    task.setTenantId(setting.getTenantId());
                                    task.setKind(kind.name());
                                    task.setBusinessDate(date.toString());
                                    task.setStatus("PENDING");
                                    task.setAttempts(0);
                                    task.setLeaseUntil(0L);
                                    task.setCreateTime(System.currentTimeMillis());
                                    return tasks.insert(task)
                                            .thenReturn(task)
                                            .onErrorResume(
                                                    failure ->
                                                            tasks.findById(id)
                                                                    .switchIfEmpty(
                                                                            Mono.error(failure)));
                                }));
    }

    private Mono<String> claim(BillingTaskEntity task) {
        long now = System.currentTimeMillis();
        if (!"PENDING".equals(task.getStatus())
                && !("RUNNING".equals(task.getStatus()) && task.getLeaseUntil() < now))
            return Mono.empty();
        String owner = UUID.randomUUID().toString();
        return tasks.createUpdate()
                .set("status", "RUNNING")
                .set("leaseOwner", owner)
                .set("leaseUntil", now + 600_000)
                .set("attempts", task.getAttempts() + 1)
                .set("startTime", now)
                .setNull("finishTime")
                .setNull("errorCode")
                .where("id", task.getId())
                .and("status", task.getStatus())
                .and("leaseUntil", task.getLeaseUntil())
                .execute()
                .filter(count -> count == 1)
                .map(count -> owner);
    }

    private Mono<Void> complete(
            BillingTaskEntity task,
            String owner,
            ReceivableGenerationService.GenerationResult result) {
        return tasks.createUpdate()
                .set("status", "SUCCEEDED")
                .set("generated", result.generated())
                .set("retained", result.retained())
                .set("finishTime", System.currentTimeMillis())
                .set("leaseUntil", 0L)
                .setNull("leaseOwner")
                .where("id", task.getId())
                .and("leaseOwner", owner)
                .execute()
                .flatMap(SubjectService::changed)
                .then();
    }

    private Mono<Void> fail(BillingTaskEntity task, String owner, Throwable failure) {
        // 保存稳定业务错误键，不把SQL、令牌或用户输入等底层异常写到任务界面。
        String message = failure.getMessage();
        String code =
                message != null && message.matches("error\\.property_finance_[a-z_]+")
                        ? message
                        : "error.property_finance_task_failed";
        return tasks.createUpdate()
                .set("status", "FAILED")
                .set("errorCode", code)
                .set("finishTime", System.currentTimeMillis())
                .set("leaseUntil", 0L)
                .setNull("leaseOwner")
                .where("id", task.getId())
                .and("leaseOwner", owner)
                .execute()
                .flatMap(SubjectService::changed)
                .then();
    }

    public Mono<PagerResult<BillingTaskEntity>> query(String project, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        Mono.defer(
                                () -> {
                                    var query = SubjectService.page(input);
                                    query.toNestQuery(nested -> nested.and("projectId", project));
                                    return FinanceSpaceQuery.inject(
                                                    query, tasks, CrudAssetPermission.read)
                                            .flatMap(
                                                    secured ->
                                                            QueryHelper.queryPager(
                                                                    secured, tasks::createQuery));
                                }));
    }

    /** 重试只重置失败身份，下一次轮询重新获取租约；不能修改日期、项目和任务类型。 */
    public Mono<Void> retry(String project, String id) {
        return scope.project(project, CrudAssetPermission.save)
                .then(tasks.createQuery().where("id", id).and("projectId", project).fetchOne())
                .switchIfEmpty(Mono.error(error("task_not_found", 404)))
                .flatMap(
                        task ->
                                AssetsHolder.assertPermission(
                                                SPACE, task.getSpaceId(), CrudAssetPermission.save)
                                        .then(
                                                tasks.createUpdate()
                                                        .set("status", "PENDING")
                                                        .set("leaseUntil", 0L)
                                                        .where("id", id)
                                                        .and("status", "FAILED")
                                                        .execute()
                                                        .flatMap(SubjectService::changed)))
                .then();
    }

    /** 崩溃后的租约和人工重试在所有业务日中恢复，而不只扫描今天。 */
    Mono<RunSummary> resume() {
        return tasks.createQuery()
                .in("status", java.util.List.of("PENDING", "RUNNING"))
                .fetch()
                .flatMap(this::resumeTask, 4, 1)
                .reduce(new RunSummary(0, 0, 0), RunSummary::add)
                .contextWrite(ignored -> Context.empty());
    }

    private Mono<TaskResult> resumeTask(BillingTaskEntity task) {
        var kind = BillingTaskKind.valueOf(task.getKind());
        var date = LocalDate.parse(task.getBusinessDate());
        Mono<TaskResult> resume =
                task.getReceivableId() == null
                        ? settings.findById(task.getSettingId())
                                .filter(row -> !Boolean.TRUE.equals(row.getDeleted()))
                                .flatMap(setting -> execute(setting, kind, date))
                        : bills.findById(task.getReceivableId())
                                .filter(row -> !Boolean.TRUE.equals(row.getDeleted()))
                                .flatMap(bill -> executeReceivable(bill, kind, date));
        return resume.switchIfEmpty(
                tasks.createUpdate()
                        .set("status", "SKIPPED")
                        .set("finishTime", System.currentTimeMillis())
                        .set("leaseUntil", 0L)
                        .setNull("leaseOwner")
                        .where("id", task.getId())
                        .and("status", task.getStatus())
                        .execute()
                        .thenReturn(new TaskResult("SKIPPED")));
    }

    record TaskResult(String status) {}

    record RunSummary(long succeeded, long failed, long skipped) {
        RunSummary add(TaskResult result) {
            return new RunSummary(
                    succeeded + ("SUCCEEDED".equals(result.status()) ? 1 : 0),
                    failed + ("FAILED".equals(result.status()) ? 1 : 0),
                    skipped + ("SKIPPED".equals(result.status()) ? 1 : 0));
        }
    }
}
