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
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** 设置行锁串行化自动出账；计算只读取冻结快照，逻辑删除账单仍占用原账期身份。 */
@Service
@RequiredArgsConstructor
public class ReceivableGenerationService {
    private static final AssetType SPACE = AssetType.of("space");
    private final FinanceScope scope;
    private final ReactiveRepository<HouseSettingEntity, String> settings;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final NaturalBillingPeriodService periods;
    private final ReceivableCalculationService calculation;

    @Transactional
    public Mono<GenerationResult> generate(
            String project, String settingId, LocalDate businessDate) {
        return scope.project(project, CrudAssetPermission.save)
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
        var query = new QueryParamEntity();
        query.setForUpdate(true);
        return settings.createQuery()
                .setParam(query)
                .where("id", id)
                .and("projectId", project)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("setting_not_found", 404)));
    }

    /** 调用者必须处于保存设置的事务，并已持有设置/项目科目锁；失败交由事务整批回滚。 */
    Mono<GenerationResult> generate(
            HouseSettingEntity setting, LocalDate date, Authentication auth) {
        return generate(setting, date, auth, BillingTaskKind.ALL);
    }

    /** 仅后台任务内部调用：运行已授权保存的冻结设置，不接收HTTP来源或冒充某个操作员。 */
    @Transactional
    public Mono<GenerationResult> scheduled(
            String project, String settingId, LocalDate date, BillingTaskKind kind) {
        return locked(project, settingId)
                .flatMap(
                        setting ->
                                org.jetlinks.pro.tenant.holder.TenantHolder.currentId()
                                        .defaultIfEmpty("")
                                        .filter(
                                                tenant ->
                                                        Objects.equals(
                                                                tenant, setting.getTenantId()))
                                        .switchIfEmpty(
                                                Mono.error(error("task_tenant_invalid", 409)))
                                        .then(
                                                Mono.defer(
                                                        () ->
                                                                generate(
                                                                        setting,
                                                                        date,
                                                                        systemActor(),
                                                                        kind))))
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
            HouseSettingEntity setting, LocalDate date, Authentication auth, BillingTaskKind kind) {
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
                                                    setting, period, standard, date, auth, kind))
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
            BillingTaskKind kind) {
        var query = new QueryParamEntity();
        query.setForUpdate(true);
        return bills.createQuery()
                .setParam(query)
                .where("settingId", setting.getId())
                .and("periodStart", period.getStart().toString())
                .and("periodEnd", period.getEnd().toString())
                .fetchOne()
                .flatMap(
                        existing -> {
                            if (Boolean.TRUE.equals(existing.getDeleted())
                                    || Boolean.TRUE.equals(existing.getSplit()))
                                return Mono.just(false);
                            if (kind == BillingTaskKind.GENERATE) return Mono.just(false);
                            return refresh(existing, standard, date, auth, kind).thenReturn(false);
                        })
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    if (kind != BillingTaskKind.GENERATE
                                            && kind != BillingTaskKind.ALL) return Mono.empty();
                                    var row = newBill(setting, period, standard, auth);
                                    calculation.calculate(row, standard, date, kind);
                                    return bills.insert(row).thenReturn(true);
                                }));
    }

    private ReceivableEntity newBill(
            HouseSettingEntity setting,
            BillingPeriod period,
            StandardEntity standard,
            Authentication auth) {
        var row = new ReceivableEntity();
        row.setId(UUID.randomUUID().toString());
        init(row, auth);
        row.setProjectId(setting.getProjectId());
        row.setSpaceId(setting.getSpaceId());
        row.setFullName(setting.getFullName());
        row.setSubjectId(setting.getSubjectId());
        row.setSubjectName(setting.getSubjectName());
        row.setStandardId(setting.getStandardId());
        row.setStandardName(setting.getStandardName());
        row.setSettingId(setting.getId());
        row.setConfigVersion(setting.getConfigVersion());
        row.setCustomerId(setting.getCustomerId());
        row.setCustomerRelationshipId(setting.getCustomerRelationshipId());
        row.setCustomerName(setting.getCustomerName());
        row.setPeriodStart(period.getStart().toString());
        row.setPeriodEnd(period.getEnd().toString());
        row.setCalcStartDate(
                period.coveredStart(LocalDate.parse(setting.getEffectiveStartDate())).toString());
        row.setCalcEndDate(
                period.coveredEnd(SettingRules.date(setting.getEffectiveEndDate(), false))
                        .toString());
        row.setDueDate(row.getCalcEndDate());
        row.setPeriodStatus("OPEN");
        row.setSnapshotJson(setting.getSnapshotJson());
        row.setMoneyScale(standard.getChargeRoundType());
        row.setFrozen(false);
        row.setPaymentLocked(false);
        row.setBankCollectionLocked(false);
        row.setLateFeeLocked(false);
        row.setFinancialClosed(false);
        row.setDiscountLocked(false);
        row.setChargeLocked(false);
        row.setSplit(false);
        var wrapper = new BeanWrapperImpl(row);
        for (var field : wrapper.getPropertyDescriptors())
            if (field.getPropertyType() == BigDecimal.class)
                wrapper.setPropertyValue(field.getName(), BigDecimal.ZERO);
        return row;
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
