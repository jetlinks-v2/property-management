package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.calculation.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.setting.BillingTaskKind;
import org.jetlinks.pro.property.finance.setting.SettingRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.*;

import java.time.LocalDate;
import java.util.*;

/** 设置行锁串行化自动出账；计算只读取冻结快照，逻辑删除账单仍占用原账期身份。 */
@Service
@RequiredArgsConstructor
public class ReceivableGenerationService {
    private static final AssetType SPACE = AssetType.of("space");
    private final FinanceScope scope;
    private final FinancialPeriodGate periodGate;
    private final ReactiveRepository<HouseSettingEntity, String> settings;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final NaturalBillingPeriodService periods;
    private final ReceivableCalculationService calculation;

    @Transactional
    public Mono<GenerationResult> generate(
            String project, String settingId, LocalDate businessDate) {
        return scope.project(project, CrudAssetPermission.save)
                .then(periodGate.assertOpen(project, businessDate))
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                locked(project, settingId)
                                        .flatMap(
                                                row ->
                                                        AssetsHolder.assertPermission(
                                                                        SPACE,
                                                                        row.getSpaceId(),
                                                                        CrudAssetPermission.save)
                                                                .then(
                                                                        generate(
                                                                                row,
                                                                                businessDate,
                                                                                auth))))
                .as(MonoTracer.create("/property/finance/receivable/generate"));
    }

    private Mono<HouseSettingEntity> locked(String project, String id) {
        return setting(project, id, true);
    }

    private Mono<HouseSettingEntity> setting(String project, String id, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return settings.createQuery()
                .setParam(query)
                .where("id", id)
                .and("projectId", project)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("setting_not_found", 404)));
    }

    /** 调用者必须先取得期间锁，再锁设置/项目科目；重入同一期间锁取得关闭边界，失败整批回滚。 */
    Mono<GenerationResult> generate(
            HouseSettingEntity setting, LocalDate date, Authentication auth) {
        return periodGate
                .lock(setting.getProjectId())
                .flatMap(
                        control ->
                                periodGate
                                        .assertOpen(control, date)
                                        .then(
                                                generate(
                                                        setting,
                                                        date,
                                                        auth,
                                                        BillingTaskKind.ALL,
                                                        control)));
    }

    /** 仅后台任务内部调用：运行已授权保存的冻结设置，不接收HTTP来源或冒充某个操作员。 */
    @Transactional
    public Mono<GenerationResult> scheduled(
            String project, String settingId, LocalDate date, BillingTaskKind kind) {
        // 先只读核对持久归属，再期间→设置锁；不能先锁设置再等待期间锁。
        return setting(project, settingId, false)
                .flatMap(
                        initial ->
                                periodGate
                                        .lockForBillingTask(initial, systemActor())
                                        .flatMap(
                                                control ->
                                                        periodGate
                                                                .assertOpen(control, date)
                                                                .then(locked(project, settingId))
                                                                .flatMap(
                                                                        locked -> {
                                                                            if (!Objects.equals(
                                                                                    initial
                                                                                            .getTenantId(),
                                                                                    locked
                                                                                            .getTenantId()))
                                                                                return Mono.error(
                                                                                        error(
                                                                                                "task_tenant_invalid",
                                                                                                409));
                                                                            return generate(
                                                                                    locked,
                                                                                    date,
                                                                                    systemActor(),
                                                                                    kind,
                                                                                    control);
                                                                        })))
                .as(MonoTracer.create("/property/finance/receivable/scheduled"));
    }

    private static Authentication systemActor() {
        var auth = new org.hswebframework.web.authorization.simple.SimpleAuthentication();
        var user = new org.hswebframework.web.authorization.simple.SimpleUser();
        user.setId("property-finance-job");
        user.setUsername("property-finance-job");
        auth.setUser(user);
        return auth;
    }

    private Mono<GenerationResult> generate(
            HouseSettingEntity setting,
            LocalDate date,
            Authentication auth,
            BillingTaskKind kind,
            FinancialPeriodControlEntity control) {
        return Mono.defer(
                () -> {
                    if (date == null) return Mono.error(error("setting_invalid_dates", 400));
                    var start = LocalDate.parse(setting.getEffectiveStartDate());
                    var end = SettingRules.date(setting.getEffectiveEndDate(), false);
                    var through = end == null || date.isBefore(end) ? date : end;
                    if (through.isBefore(start)) return Mono.just(new GenerationResult(0, 0));
                    // 有界同步路径；大项目按持久批次执行，不在一个事务内无界分配账期对象。
                    if (java.time.temporal.ChronoUnit.MONTHS.between(start, through) > 1200)
                        return Mono.error(error("setting_range_too_large", 400));
                    var standard = SettingRules.standard(setting.getSnapshotJson());
                    return Flux.fromIterable(
                                    periods.split(standard.getSettlementMethod(), start, through))
                            .concatMap(
                                    period ->
                                            generateOne(
                                                    setting, period, standard, date, auth, kind,
                                                    control))
                            .reduce(
                                    new GenerationResult(0, 0),
                                    (result, created) ->
                                            new GenerationResult(
                                                    result.generated() + (created ? 1 : 0),
                                                    result.retained() + (created ? 0 : 1)));
                });
    }

    private Mono<Boolean> generateOne(
            HouseSettingEntity setting,
            BillingPeriod period,
            StandardEntity standard,
            LocalDate date,
            Authentication auth,
            BillingTaskKind kind,
            FinancialPeriodControlEntity control) {
        var query = new QueryParamEntity();
        query.setForUpdate(true);
        return bills.createQuery()
                .setParam(query)
                .where("settingId", setting.getId())
                .and("periodStart", period.getStart().toString())
                .and("periodEnd", period.getEnd().toString())
                .isNull("sourceReceivableId")
                .fetchOne()
                .flatMap(
                        existing -> {
                            if (Boolean.TRUE.equals(existing.getDeleted())) return Mono.just(false);
                            if (Boolean.TRUE.equals(existing.getSplit()))
                                return refreshChildren(existing, date, auth, kind)
                                        .thenReturn(false);
                            if (kind == BillingTaskKind.GENERATE) return Mono.just(false);
                            return refresh(existing, standard, date, auth, kind).thenReturn(false);
                        })
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    if (kind != BillingTaskKind.GENERATE
                                            && kind != BillingTaskKind.ALL) return Mono.empty();
                                    var row = newBill(setting, period, standard, auth);
                                    // 在新开放期执行也不能补写已结日期的缺失账；保留原身份的账走上方分支。
                                    return periodGate
                                            .assertOpen(
                                                    control,
                                                    LocalDate.parse(row.getCalcStartDate()))
                                            .then(
                                                    Mono.defer(
                                                            () -> {
                                                                calculation.calculate(
                                                                        row, standard, date, kind);
                                                                return bills.insert(row)
                                                                        .thenReturn(true);
                                                            }));
                                }));
    }

    private ReceivableEntity newBill(
            HouseSettingEntity setting,
            BillingPeriod period,
            StandardEntity standard,
            Authentication auth) {
        var row = ReceivableDrafts.create(setting, period, standard);
        row.setId(UUID.randomUUID().toString());
        init(row, auth);
        return row;
    }

    /** 原账只保留历史身份；沿来源链刷新叶子账，不以原设置重新生成拆分前的欠款。 */
    private Mono<Void> refreshChildren(
            ReceivableEntity source, LocalDate date, Authentication auth, BillingTaskKind kind) {
        if (kind == BillingTaskKind.GENERATE) return Mono.empty();
        var query = new QueryParamEntity();
        query.setForUpdate(true);
        query.toQuery().orderByAsc("id");
        return bills.createQuery()
                .setParam(query)
                .where("sourceReceivableId", source.getId())
                .and("deleted", false)
                .fetch()
                .concatMap(
                        child ->
                                Boolean.TRUE.equals(child.getSplit())
                                        ? refreshChildren(child, date, auth, kind)
                                        : refresh(
                                                child,
                                                SettingRules.standard(child.getSnapshotJson()),
                                                date,
                                                auth,
                                                kind))
                .then();
    }

    private Mono<Void> refresh(
            ReceivableEntity bill,
            StandardEntity standard,
            LocalDate date,
            Authentication auth,
            BillingTaskKind kind) {
        // 已收、月结或支付占用的账单不被自动刷新改写；收款前刷新走统一资金入口。
        if (Boolean.TRUE.equals(bill.getFinancialClosed())
                || bill.getPaidAmount().signum() > 0 && bill.getArrears().signum() == 0)
            return Mono.empty();
        if (Boolean.TRUE.equals(bill.getPaymentLocked())
                || bill.getPaymentOccupiedAmount().signum() > 0)
            return Mono.error(error("receivable_payment_locked", 409));
        if (date.isBefore(LocalDate.parse(bill.getCalculatedAsOfDate())))
            return Mono.error(error("receivable_date_regression", 409));
        calculation.calculate(bill, standard, date, kind);
        var update =
                audit(bills.createUpdate(), auth, bill.getVersion())
                        .set("contractReceivableAmount", bill.getContractReceivableAmount())
                        .set("accruedReceivableAmount", bill.getAccruedReceivableAmount())
                        .set("accruedAsOfDate", bill.getAccruedAsOfDate())
                        .set("calculatedAsOfDate", bill.getCalculatedAsOfDate())
                        .set("periodStatus", bill.getPeriodStatus())
                        .set("accruedLateFeeAmount", bill.getAccruedLateFeeAmount())
                        .set("finalLateFeeAmount", bill.getFinalLateFeeAmount())
                        .set("actualReceivableAmount", bill.getActualReceivableAmount())
                        .set("arrears", bill.getArrears());
        return update.where("id", bill.getId())
                .and("version", bill.getVersion())
                .and("deleted", false)
                .execute()
                .flatMap(SubjectService::changed)
                .then();
    }

    public record GenerationResult(int generated, int retained) {}
}
