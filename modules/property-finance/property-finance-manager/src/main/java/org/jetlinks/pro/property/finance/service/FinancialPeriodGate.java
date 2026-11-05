package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.apache.commons.codec.digest.DigestUtils;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.finance.entity.FinancialPeriodControlEntity;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 财务写入的项目级事务门闩。调用方先验证项目权限，再锁本行，最后锁公司及业务来源。 */
@Component
@RequiredArgsConstructor
public class FinancialPeriodGate {
    private final ReactiveRepository<FinancialPeriodControlEntity, String> controls;
    private final DatabaseOperator database;

    /** 锁持有到外层响应式事务结束；禁止在授权查询或只读预览中调用。 */
    public Mono<FinancialPeriodControlEntity> lock(String project) {
        return TransactionSynchronizationManager.forCurrentTransaction()
                .onErrorMap(
                        NoTransactionException.class,
                        ignored -> error("period_transaction_required", 500))
                .flatMap(
                        transaction -> {
                            if (!transaction.isActualTransactionActive())
                                return Mono.error(error("period_transaction_required", 500));
                            return Authentication.currentReactive()
                                    .switchIfEmpty(
                                            Mono.error(UnAuthorizedException.NoStackTrace::new));
                        })
                .flatMap(
                        auth ->
                                TenantHolder.currentId()
                                        .defaultIfEmpty("")
                                        .flatMap(tenant -> lock(project, tenant, auth)))
                .as(MonoTracer.create("property.finance.period.lock"));
    }

    private Mono<FinancialPeriodControlEntity> lock(
            String project, String tenant, Authentication auth) {
        var id = DigestUtils.sha256Hex(JSON.toJSONString(List.of(tenant, project)));
        return locked(id)
                .switchIfEmpty(
                        Mono.defer(() -> initialize(id, tenant, project, auth).then(locked(id))));
    }

    private Mono<Void> initialize(String id, String tenant, String project, Authentication auth) {
        var row = new FinancialPeriodControlEntity();
        row.setId(id);
        InvoiceCompanyService.init(row, auth);
        var values =
                Map.<String, Object>of(
                        "id",
                        id,
                        "tenantId",
                        tenant,
                        "projectId",
                        project,
                        "version",
                        row.getVersion(),
                        "deleted",
                        row.getDeleted(),
                        "createTime",
                        row.getCreateTime(),
                        "modifyTime",
                        row.getModifyTime(),
                        "modifierId",
                        row.getModifierId(),
                        "modifierName",
                        row.getModifierName());
        // 并发首次写入不得覆盖已结日期，也不能让重复插入使 PostgreSQL 事务失效。
        return database.dml()
                .upsert("pm_fin_period_control")
                .doNothingOnConflict(true)
                .value(values)
                .execute()
                .reactive()
                .then();
    }

    /** 以本次资金业务日期判断开放期间，不能用历史应收的结账标记阻止缴纳欠费。 */
    public Mono<Void> assertOpen(String project, LocalDate businessDate) {
        return lock(project).flatMap(control -> assertOpen(control, businessDate));
    }

    /**
     * 已成功请求先恢复，等待期间锁后再查一次，避免并发原请求被后续版本或月结状态误拒绝。 replay 必须为每次订阅重新查询且重新验证资产权限的冷流，mutation 不得提前获取业务锁。
     */
    public <T> Mono<T> inOpenPeriod(
            String project, LocalDate businessDate, Mono<T> replay, Supplier<Mono<T>> mutation) {
        return replay.switchIfEmpty(
                Mono.defer(
                        () ->
                                lock(project)
                                        .flatMap(
                                                control ->
                                                        replay.switchIfEmpty(
                                                                Mono.defer(
                                                                        () ->
                                                                                assertOpen(
                                                                                                control,
                                                                                                businessDate)
                                                                                        .then(
                                                                                                Mono
                                                                                                        .defer(
                                                                                                                mutation)))))));
    }

    private Mono<Void> assertOpen(FinancialPeriodControlEntity control, LocalDate businessDate) {
        if (control.getClosedThrough() != null
                && !businessDate.isAfter(LocalDate.parse(control.getClosedThrough())))
            return Mono.error(error("period_closed", 409));
        return Mono.empty();
    }

    private Mono<FinancialPeriodControlEntity> locked(String id) {
        var query = new QueryParamEntity();
        query.setForUpdate(true);
        return controls.createQuery().setParam(query).where("id", id).fetchOne();
    }
}
