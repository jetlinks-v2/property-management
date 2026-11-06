package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.init;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.carry.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/** 调度元数据的短事务，绝不在页级事务中执行金融扣款。仅供同模块受权执行器调用。 */
@Service
@RequiredArgsConstructor
public class CarryForwardRunStore {
    private static final long LEASE_MILLIS = 120_000;
    private final ReactiveRepository<CarryForwardRunEntity, String> runs;
    private final ReactiveRepository<CarryForwardWorkEntity, String> works;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<FundAccountEntity, String> funds;
    private final ReactiveRepository<CarryForwardPlanEntity, String> plans;
    private final ReactiveRepository<CarryForwardPlanLogEntity, String> logs;

    Mono<CarryForwardRunEntity> ensure(
            CarryForwardPlanEntity plan, String slot, Authentication auth) {
        String id = identity(List.of(plan.getId(), slot));
        return runs.findById(id)
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    var run = new CarryForwardRunEntity();
                                    run.setId(id);
                                    init(run, auth);
                                    run.setProjectId(plan.getProjectId());
                                    run.setPlanId(plan.getId());
                                    run.setPlanVersion(plan.getVersion());
                                    run.setSlot(slot);
                                    run.setBusinessDate(
                                            LocalDateTime.parse(slot).toLocalDate().toString());
                                    run.setTenantId(plan.getTenantId());
                                    run.setConfigJson(plan.getConfigJson());
                                    run.setStatus("PENDING");
                                    run.setLeaseUntil(0L);
                                    run.setAttempts(0);
                                    run.setSucceeded(0);
                                    run.setFailed(0);
                                    run.setSkipped(0);
                                    return runs.insert(run)
                                            .thenReturn(run)
                                            .onErrorResume(
                                                    failure ->
                                                            runs.findById(id)
                                                                    .switchIfEmpty(
                                                                            Mono.error(failure)));
                                }));
    }

    /** 比较已读状态及租约，过期持有者只能失去执行权，不能覆盖新持有者的游标和结果。 */
    Mono<String> claim(String id) {
        return runs.findById(id)
                .flatMap(
                        run -> {
                            long now = System.currentTimeMillis();
                            if (!"PENDING".equals(run.getStatus())
                                    && !("RUNNING".equals(run.getStatus())
                                            && run.getLeaseUntil() < now)) return Mono.empty();
                            String owner = UUID.randomUUID().toString();
                            return runs.createUpdate()
                                    .set("status", "RUNNING")
                                    .set("leaseOwner", owner)
                                    .set("leaseUntil", now + LEASE_MILLIS)
                                    .set("attempts", run.getAttempts() + 1)
                                    .setNull("finishTime")
                                    .where("id", id)
                                    .and("status", run.getStatus())
                                    .and("leaseUntil", run.getLeaseUntil())
                                    .execute()
                                    .filter(changed -> changed == 1)
                                    .map(ignored -> owner);
                        });
    }

    Mono<Void> heartbeat(String run, String owner) {
        long now = System.currentTimeMillis();
        return runs.createUpdate()
                .set("leaseUntil", now + LEASE_MILLIS)
                .where("id", run)
                .and("status", "RUNNING")
                .and("leaseOwner", owner)
                .gt("leaseUntil", now)
                .execute()
                .flatMap(CarryForwardRunStore::owned);
    }

    /** 先原子保存候选页和扫描游标，再逐笔入账。应收变为已付、程序退出都不会丢掉本页。 */
    @Transactional
    public Mono<List<CarryForwardWorkEntity>> stagePage(String id, String owner, int size) {
        if (size < 1 || size > 100) return Mono.error(error("carry_plan_invalid", 400));
        return lock(id, owner)
                .flatMap(
                        run -> {
                            var input =
                                    JSON.parseObject(
                                            run.getConfigJson(), CarryForwardPlanInput.class);
                            var q =
                                    bills.createQuery()
                                            .setParam(page(size))
                                            .where("projectId", run.getProjectId())
                                            .and("deleted", false)
                                            .in("subjectId", input.getSubjectIds())
                                            .gte("calcStartDate", input.getPeriodStart())
                                            .lte("calcEndDate", input.getPeriodEnd())
                                            .lte("createTime", run.getCreateTime())
                                            .gt("arrears", java.math.BigDecimal.ZERO);
                            if (run.getCursorId() != null) q.gt("id", run.getCursorId());
                            return q.fetch()
                                    .collectList()
                                    .flatMap(
                                            rows -> {
                                                if (rows.isEmpty()) return Mono.just(List.of());
                                                var candidates =
                                                        rows.stream()
                                                                .map(row -> candidate(run, row))
                                                                .toList();
                                                return works.insertBatch(Mono.just(candidates))
                                                        .then(
                                                                runs.createUpdate()
                                                                        .set(
                                                                                "cursorId",
                                                                                rows.get(
                                                                                                rows
                                                                                                                .size()
                                                                                                        - 1)
                                                                                        .getId())
                                                                        .where("id", id)
                                                                        .and("leaseOwner", owner)
                                                                        .execute())
                                                        .flatMap(CarryForwardRunStore::owned)
                                                        .thenReturn(candidates);
                                            });
                        });
    }

    Mono<List<CarryForwardWorkEntity>> pending(String run, int size) {
        return works.createQuery()
                .setParam(page(size))
                .where("runId", run)
                .and("status", "PENDING")
                .fetch()
                .collectList();
    }

    /** 候选账户只冻结一次，恢复时保持原账户集合及顺序。没有账户用null请求验证真实全免。 */
    @Transactional
    public Mono<List<String>> accounts(CarryForwardRunEntity run, CarryForwardWorkEntity work) {
        var q = new QueryParamEntity();
        q.setForUpdate(true);
        return works.createQuery()
                .setParam(q)
                .where("id", work.getId())
                .and("runId", run.getId())
                .and("projectId", run.getProjectId())
                .fetchOne()
                .switchIfEmpty(Mono.error(error("carry_run_not_found", 404)))
                .flatMap(
                        locked -> {
                            if (locked.getAccountIdsJson() != null)
                                return Mono.just(
                                        JSON.parseArray(locked.getAccountIdsJson(), String.class));
                            return chooseAccounts(run, work)
                                    .flatMap(
                                            ids ->
                                                    works.createUpdate()
                                                            .set(
                                                                    "accountIdsJson",
                                                                    JSON.toJSONString(ids))
                                                            .where("id", locked.getId())
                                                            .execute()
                                                            .thenReturn(ids));
                        });
    }

    private Mono<List<String>> chooseAccounts(
            CarryForwardRunEntity run, CarryForwardWorkEntity work) {
        var input = JSON.parseObject(run.getConfigJson(), CarryForwardPlanInput.class);
        return bills.findById(work.getReceivableId())
                .switchIfEmpty(Mono.error(error("receivable_not_found", 404)))
                .flatMap(
                        bill ->
                                funds.createQuery()
                                        .setParam(page(1001))
                                        .where("projectId", run.getProjectId())
                                        .and("spaceId", bill.getSpaceId())
                                        .and("subjectId", bill.getSubjectId())
                                        .and("deleted", false)
                                        .and("type", "ADVANCE")
                                        .fetch()
                                        .collectList()
                                        .map(rows -> accountIds(run, input, bill, rows)));
    }

    private static List<String> accountIds(
            CarryForwardRunEntity run,
            CarryForwardPlanInput input,
            ReceivableEntity bill,
            List<FundAccountEntity> rows) {
        // 明确拒绝超限，不能截断账户后把未支付金额当成余额不足。
        if (rows.size() > 1000) throw error("carry_accounts_exceeded", 409);
        var selected =
                rows.stream()
                        .filter(a -> CarryForwardRules.matches(run.getProjectId(), input, bill, a))
                        .map(FundAccountEntity::getId)
                        .toList();
        return selected.isEmpty() ? Collections.singletonList(null) : selected;
    }

    @Transactional
    public Mono<Void> outcome(
            String run, String owner, CarryForwardWorkEntity work, String status, String code) {
        return lock(run, owner)
                .then(
                        works.createUpdate()
                                .set("status", status)
                                .set("attempts", work.getAttempts() + 1)
                                .set("finishTime", System.currentTimeMillis())
                                .set("errorCode", code)
                                .where("id", work.getId())
                                .and("runId", run)
                                .and("status", "PENDING")
                                .execute())
                .flatMap(CarryForwardRunStore::owned);
    }

    /** 批次上限到达后释放为PENDING；只有扫描耗尽且无待处理项才写终态。计数始终取持久工作明细。 */
    @Transactional
    public Mono<Void> finish(String id, String owner, boolean exhausted, String cancelCode) {
        return lock(id, owner)
                .flatMap(
                        run ->
                                Mono.zip(
                                                count(id, "SUCCEEDED"),
                                                count(id, "FAILED"),
                                                count(id, "SKIPPED"),
                                                count(id, "PENDING"))
                                        .flatMap(
                                                counts -> {
                                                    String status =
                                                            cancelCode != null
                                                                    ? "CANCELLED"
                                                                    : !exhausted
                                                                                    || counts
                                                                                                    .getT4()
                                                                                            > 0
                                                                            ? "PENDING"
                                                                            : counts.getT2() > 0
                                                                                    ? "PARTIAL"
                                                                                    : "SUCCEEDED";
                                                    var update =
                                                            runs.createUpdate()
                                                                    .set("status", status)
                                                                    .set(
                                                                            "succeeded",
                                                                            counts.getT1())
                                                                    .set("failed", counts.getT2())
                                                                    .set("skipped", counts.getT3())
                                                                    .set("leaseUntil", 0L)
                                                                    .setNull("leaseOwner")
                                                                    .set("errorCode", cancelCode)
                                                                    .where("id", id)
                                                                    .and("leaseOwner", owner);
                                                    if (!"PENDING".equals(status))
                                                        update.set(
                                                                "finishTime",
                                                                System.currentTimeMillis());
                                                    return update.execute()
                                                            .flatMap(CarryForwardRunStore::owned);
                                                }));
    }

    @Transactional
    public Mono<Void> retry(String id, Authentication auth) {
        var q = new QueryParamEntity();
        q.setForUpdate(true);
        return runs.createQuery()
                .setParam(q)
                .where("id", id)
                .fetchOne()
                .flatMap(
                        run -> {
                            if (!"PARTIAL".equals(run.getStatus()))
                                return Mono.error(error("carry_retry_invalid", 409));
                            // 整批重试必须覆盖全部失败房屋，不能先重置再发现部分无权。
                            return plans.createQuery()
                                    .where("id", run.getPlanId())
                                    .and("projectId", run.getProjectId())
                                    .and("version", run.getPlanVersion())
                                    .and("enabled", true)
                                    .and("deleted", false)
                                    .count()
                                    .flatMap(
                                            count ->
                                                    count == 1
                                                            ? Mono.empty()
                                                            : Mono.error(
                                                                    error(
                                                                            "carry_plan_changed",
                                                                            409)))
                                    .thenMany(
                                            works.createQuery()
                                                    .where("runId", id)
                                                    .and("status", "FAILED")
                                                    .fetch())
                                    .buffer(100)
                                    .concatMap(
                                            rows ->
                                                    AssetsHolder.currentReactive()
                                                            .switchIfEmpty(
                                                                    Mono.error(
                                                                            error(
                                                                                    "permission_denied",
                                                                                    403)))
                                                            .flatMap(
                                                                    holder ->
                                                                            holder.assertPermission(
                                                                                    "space",
                                                                                    rows.stream()
                                                                                            .map(
                                                                                                    CarryForwardWorkEntity
                                                                                                            ::getSpaceId)
                                                                                            .distinct()
                                                                                            .toList(),
                                                                                    CrudAssetPermission
                                                                                            .save)))
                                    .then(
                                            works.createUpdate()
                                                    .set("status", "PENDING")
                                                    .setNull("errorCode")
                                                    .setNull("finishTime")
                                                    .where("runId", id)
                                                    .and("status", "FAILED")
                                                    .execute())
                                    .then(
                                            runs.createUpdate()
                                                    .set("status", "PENDING")
                                                    .setNull("finishTime")
                                                    .where("id", id)
                                                    .execute())
                                    .flatMap(CarryForwardRunStore::owned)
                                    .then(retryLog(run, auth));
                        });
    }

    private Mono<Void> retryLog(CarryForwardRunEntity run, Authentication auth) {
        var log = new CarryForwardPlanLogEntity();
        log.setId(UUID.randomUUID().toString());
        log.setProjectId(run.getProjectId());
        log.setPlanId(run.getPlanId());
        log.setOperation("RUN_RETRY");
        log.setBeforeJson(
                JSON.toJSONString(Map.of("runId", run.getId(), "status", run.getStatus())));
        log.setAfterJson(JSON.toJSONString(Map.of("runId", run.getId(), "status", "PENDING")));
        log.setOperatorId(auth.getUser().getId());
        log.setOperatorName(FinanceScope.operatorName(auth));
        log.setCreateTime(System.currentTimeMillis());
        return logs.insert(log).then();
    }

    private Mono<Integer> count(String run, String status) {
        return works.createQuery().where("runId", run).and("status", status).count();
    }

    private Mono<CarryForwardRunEntity> lock(String id, String owner) {
        var q = new QueryParamEntity();
        q.setForUpdate(true);
        return runs.createQuery()
                .setParam(q)
                .where("id", id)
                .and("status", "RUNNING")
                .and("leaseOwner", owner)
                .gt("leaseUntil", System.currentTimeMillis())
                .fetchOne()
                .switchIfEmpty(Mono.error(error("carry_lease_lost", 409)));
    }

    private static Mono<Void> owned(int changed) {
        return changed == 1 ? Mono.empty() : Mono.error(error("carry_lease_lost", 409));
    }

    private static CarryForwardWorkEntity candidate(
            CarryForwardRunEntity run, ReceivableEntity row) {
        var w = new CarryForwardWorkEntity();
        w.setId(identity(List.of(run.getId(), row.getId())));
        w.setVersion(0L);
        w.setDeleted(false);
        w.setCreateTime(System.currentTimeMillis());
        w.setProjectId(run.getProjectId());
        w.setRunId(run.getId());
        w.setReceivableId(row.getId());
        w.setSpaceId(row.getSpaceId());
        w.setFullName(row.getFullName());
        w.setStatus("PENDING");
        w.setAttempts(0);
        return w;
    }

    private static String identity(List<String> values) {
        return UUID.nameUUIDFromBytes(JSON.toJSONString(values).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static QueryParamEntity page(int size) {
        var q = new QueryParamEntity();
        q.setPaging(true);
        q.setPageIndex(0);
        q.setPageSize(size);
        q.toQuery().orderByAsc("id");
        return q;
    }
}
