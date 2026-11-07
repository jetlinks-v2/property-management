package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.init;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.carry.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.jetlinks.pro.property.finance.receivable.*;
import org.jetlinks.pro.property.finance.setting.*;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;

/** 自动任务的单笔事务入口。减免、余额消费、应收及结果共同提交，调度器另行记录失败。 */
@Service
@RequiredArgsConstructor
public class CarryForwardApplyService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private final FinanceScope scope;
    private final FinancialPeriodGate periodGate;
    private final HouseArchiveSource archive;
    private final FundSourceService sources;
    private final CashierConfiguration configuration;
    private final ReceivableCalculationService calculation;
    private final ReceivableAdjustmentService adjustments;
    private final FundsService funds;
    private final ReactiveRepository<CarryForwardPlanEntity, String> plans;
    private final ReactiveRepository<CarryForwardItemEntity, String> items;
    private final ReactiveRepository<CarryForwardRemissionEntity, String> remissions;
    private final ReactiveRepository<ReceivableEntity, String> bills;

    /** 仅接收保存计划的身份，不暴露任意减免或金额覆盖入口；所有调用仍须项目和空间授权。 */
    @Transactional
    public Mono<CarryForwardItemEntity> apply(String project, Request request, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(auth -> Mono.defer(() -> authorized(project, request, date, auth)))
                .as(MonoTracer.create("/property/finance/carry/apply"));
    }

    private Mono<CarryForwardItemEntity> authorized(
            String project, Request r, LocalDate date, Authentication auth) {
        validate(r, date);
        var id =
                identity(
                        List.of(
                                project,
                                r.planId(),
                                r.runId(),
                                r.receivableId(),
                                Objects.toString(r.accountId(), "")));
        // record不能交给Fastjson1推断属性；显式列出所有影响入账的字段，变载荷必须冲突。
        var fingerprint =
                fingerprint(
                        JSON.toJSONString(
                                Arrays.asList(
                                        project,
                                        r.planId(),
                                        r.planVersion(),
                                        r.runId(),
                                        r.receivableId(),
                                        r.accountId(),
                                        r.occurredAt(),
                                        date.toString())));
        // 先锁期间再锁计划/应收，包含零资金的纯减免；嵌套资金入口沿用本事务的期间锁。
        return periodGate.inOpenPeriod(
                project,
                LocalDateTime.parse(r.occurredAt()).toLocalDate(),
                existing(project, id, fingerprint),
                () ->
                        lockedPlan(project, r.planId())
                                .flatMap(
                                        plan ->
                                                existing(project, id, fingerprint)
                                                        .switchIfEmpty(
                                                                Mono.defer(
                                                                        () ->
                                                                                prepare(
                                                                                        project,
                                                                                        r,
                                                                                        date,
                                                                                        plan,
                                                                                        id,
                                                                                        fingerprint,
                                                                                        auth)))));
    }

    private Mono<CarryForwardItemEntity> prepare(
            String project,
            Request r,
            LocalDate date,
            CarryForwardPlanEntity plan,
            String id,
            String fingerprint,
            Authentication auth) {
        if (!Boolean.TRUE.equals(plan.getEnabled())
                || !Objects.equals(plan.getVersion(), r.planVersion()))
            return Mono.error(error("carry_plan_changed", 409));
        var input = JSON.parseObject(plan.getConfigJson(), CarryForwardPlanInput.class);
        CarryForwardRules.validate(input);
        if (!Boolean.TRUE.equals(input.getEnabled()))
            return Mono.error(error("carry_plan_changed", 409));
        return bill(project, r.receivableId(), false)
                .flatMap(
                        candidate -> {
                            checkBill(input, candidate, date);
                            if (r.accountId() == null)
                                return prepareWithoutAccount(
                                        project,
                                        r,
                                        date,
                                        plan,
                                        input,
                                        candidate,
                                        id,
                                        fingerprint,
                                        auth);
                            return sources.account(project, r.accountId(), false)
                                    .flatMap(
                                            account ->
                                                    prepareWithAccount(
                                                            project,
                                                            r,
                                                            date,
                                                            plan,
                                                            input,
                                                            candidate,
                                                            account,
                                                            id,
                                                            fingerprint,
                                                            auth));
                        });
    }

    private Mono<CarryForwardItemEntity> prepareWithoutAccount(
            String project,
            Request r,
            LocalDate date,
            CarryForwardPlanEntity plan,
            CarryForwardPlanInput input,
            ReceivableEntity candidate,
            String id,
            String fingerprint,
            Authentication auth) {
        var company = SettingRules.standard(candidate.getSnapshotJson()).getCompanyId();
        return archive.context(project, candidate.getSpaceId(), true)
                .doOnNext(
                        context -> {
                            if (!project.equals(context.house().projectId())
                                    || !candidate.getSpaceId().equals(context.house().spaceId())
                                    || context.customers().stream()
                                            .noneMatch(
                                                    c ->
                                                            SettingRules.selectedCustomer(
                                                                    candidate, c.customerId())))
                                throw error("cashier_payer_invalid", 409);
                        })
                .then(
                        company == null
                                ? Mono.empty()
                                : configuration.balanceCompany(project, company, true).then())
                .then(bill(project, candidate.getId(), true))
                .flatMap(
                        locked ->
                                applyLocked(
                                        project,
                                        r,
                                        date,
                                        plan,
                                        input,
                                        candidate,
                                        locked,
                                        null,
                                        id,
                                        fingerprint,
                                        auth));
    }

    private Mono<CarryForwardItemEntity> prepareWithAccount(
            String project,
            Request r,
            LocalDate date,
            CarryForwardPlanEntity plan,
            CarryForwardPlanInput input,
            ReceivableEntity candidate,
            FundAccountEntity account,
            String id,
            String fingerprint,
            Authentication auth) {
        if (!CarryForwardRules.matches(project, input, candidate, account))
            return Mono.error(error("fund_offset_invalid", 409));
        var offset = offset(r, account, candidate, BigDecimal.ONE);
        // 与柜台相同：基础档案→公司→应收→账户；绝不先锁账户再等待应收。
        return sources.prepare(project, offset, true)
                .flatMap(
                        source ->
                                configuration
                                        .balanceCompany(project, account.getCompanyId(), true)
                                        .then(sources.lockReferences(project, offset))
                                        .then(bill(project, candidate.getId(), true))
                                        .flatMap(
                                                locked ->
                                                        sources.lock(project, source)
                                                                .flatMap(
                                                                        lockedSource ->
                                                                                applyLocked(
                                                                                        project,
                                                                                        r,
                                                                                        date,
                                                                                        plan,
                                                                                        input,
                                                                                        candidate,
                                                                                        locked,
                                                                                        lockedSource
                                                                                                .source(),
                                                                                        id,
                                                                                        fingerprint,
                                                                                        auth))));
    }

    private Mono<CarryForwardItemEntity> applyLocked(
            String project,
            Request r,
            LocalDate date,
            CarryForwardPlanEntity plan,
            CarryForwardPlanInput input,
            ReceivableEntity candidate,
            ReceivableEntity locked,
            FundAccountEntity account,
            String id,
            String fingerprint,
            Authentication auth) {
        // 预读只用于确定锁集合；真正计算前必须验证锁定的快照没有切换到另一组上游资料。
        if (!Objects.equals(candidate.getVersion(), locked.getVersion())
                || !Objects.equals(candidate.getSnapshotJson(), locked.getSnapshotJson()))
            return Mono.error(error("version_conflict", 409));
        checkBill(input, locked, date);
        if (account != null && !CarryForwardRules.matches(project, input, locked, account))
            return Mono.error(error("fund_offset_invalid", 409));
        ReceivablePolicy.assertCarryReductionAllowed(locked);
        if (account != null && account.getInvoiceOccupiedAmount().signum() > 0)
            return Mono.error(error("fund_invoice_occupied", 409));
        calculation.calculate(
                locked, SettingRules.standard(locked.getSnapshotJson()), date, BillingTaskKind.ALL);
        var remissionId = identity(List.of(project, plan.getId(), locked.getId()));
        return remissions
                .findById(remissionId)
                .hasElement()
                .flatMap(
                        alreadyRemitted -> {
                            var quote =
                                    effectiveReduction(
                                            locked,
                                            CarryForwardRules.quote(
                                                    input,
                                                    locked.getArrears(),
                                                    locked.getFinalLateFeeAmount(),
                                                    account == null ? ZERO : account.getBalance(),
                                                    alreadyRemitted),
                                            date);
                            return reduce(project, locked, quote, date)
                                    .flatMap(
                                            after ->
                                                    collect(
                                                            project,
                                                            r,
                                                            date,
                                                            plan,
                                                            input,
                                                            after,
                                                            account,
                                                            quote,
                                                            id,
                                                            fingerprint,
                                                            auth))
                                    .flatMap(
                                            item ->
                                                    markRemission(
                                                                    project,
                                                                    plan.getId(),
                                                                    locked.getId(),
                                                                    remissionId,
                                                                    quote,
                                                                    item,
                                                                    auth)
                                                            // 最后写执行事实；此步失败也必须回滚减免、核销和来源消费。
                                                            .then(items.insert(item))
                                                            .thenReturn(item));
                        });
    }

    private CarryForwardRules.Quote effectiveReduction(
            ReceivableEntity row, CarryForwardRules.Quote quote, LocalDate date) {
        if (quote.feeReduction().signum() == 0) return quote;
        var afterFee = new ReceivableEntity();
        BeanUtils.copyProperties(row, afterFee);
        afterFee.setFeeReductionAmount(row.getFeeReductionAmount().add(quote.feeReduction()));
        calculation.calculate(
                afterFee, SettingRules.standard(row.getSnapshotJson()), date, BillingTaskKind.ALL);
        // 减免本金会同步降低比例滞纳金，仅对重算后仍存在的滞纳金登记显式减免，不能重复冲减。
        var lateReduction = quote.lateFeeReduction().min(afterFee.getFinalLateFeeAmount());
        var amount = quote.amount().min(afterFee.getArrears().subtract(lateReduction).max(ZERO));
        return new CarryForwardRules.Quote(
                amount,
                quote.feeReduction(),
                lateReduction,
                afterFee.getArrears().subtract(lateReduction).subtract(amount));
    }

    private Mono<ReceivableEntity> reduce(
            String project, ReceivableEntity row, CarryForwardRules.Quote q, LocalDate date) {
        if (q.feeReduction().signum() == 0 && q.lateFeeReduction().signum() == 0)
            return Mono.just(row);
        var input = new AdjustmentInput();
        input.setVersion(row.getVersion());
        input.setAdjustmentMode("REDUCTION");
        input.setFeeReductionAmount(q.feeReduction().toPlainString());
        input.setLateFeeReductionAmount(q.lateFeeReduction().toPlainString());
        input.setLateFeeReductionReason("自动结转计划减免");
        input.setReason("自动结转计划减免");
        return adjustments
                .reduceForCarry(project, row.getId(), input, date)
                .then(bill(project, row.getId(), true));
    }

    private Mono<CarryForwardItemEntity> collect(
            String project,
            Request r,
            LocalDate date,
            CarryForwardPlanEntity plan,
            CarryForwardPlanInput input,
            ReceivableEntity row,
            FundAccountEntity account,
            CarryForwardRules.Quote quote,
            String id,
            String fingerprint,
            Authentication auth) {
        // 本金减免会影响按比例滞纳金。重新读账后的真实欠费决定核销额，不能使用减免前的quote.amount。
        var payable =
                CarryForwardRules.quote(
                                input,
                                row.getArrears(),
                                row.getFinalLateFeeAmount(),
                                account == null ? ZERO : account.getBalance(),
                                true)
                        .amount();
        var item = result(project, r, date, row, quote, payable, id, fingerprint, auth);
        if (payable.signum() == 0) return Mono.just(item);
        var offset = offset(r, account, row, payable);
        // 所有上游锁均由本事务取得，复用资金入口的预览/执行不会扩大锁集合。
        return funds.preview(project, offset, date)
                .flatMap(
                        preview ->
                                funds.execute(
                                        project,
                                        new FundsService.Submission(id, preview.token(), offset),
                                        date))
                .map(
                        posted -> {
                            item.setPaymentId(posted.entry().getId());
                            return item;
                        });
    }

    private Mono<Void> markRemission(
            String project,
            String plan,
            String bill,
            String id,
            CarryForwardRules.Quote q,
            CarryForwardItemEntity item,
            Authentication auth) {
        if (q.feeReduction().signum() == 0 && q.lateFeeReduction().signum() == 0)
            return Mono.empty();
        var marker = new CarryForwardRemissionEntity();
        marker.setId(id);
        init(marker, auth);
        marker.setProjectId(project);
        marker.setPlanId(plan);
        marker.setReceivableId(bill);
        marker.setItemId(item.getId());
        marker.setFeeReduction(q.feeReduction());
        marker.setLateFeeReduction(q.lateFeeReduction());
        return remissions.insert(marker).then();
    }

    private static CarryForwardItemEntity result(
            String project,
            Request r,
            LocalDate date,
            ReceivableEntity row,
            CarryForwardRules.Quote q,
            BigDecimal payable,
            String id,
            String fingerprint,
            Authentication auth) {
        var item = new CarryForwardItemEntity();
        item.setId(id);
        init(item, auth);
        item.setProjectId(project);
        item.setPlanId(r.planId());
        item.setPlanVersion(r.planVersion());
        item.setRunId(r.runId());
        item.setReceivableId(row.getId());
        item.setSpaceId(row.getSpaceId());
        item.setAccountId(r.accountId());
        item.setOccurredAt(r.occurredAt());
        item.setBusinessDate(date.toString());
        item.setRequestFingerprint(fingerprint);
        item.setAmount(payable);
        item.setFeeReduction(q.feeReduction());
        item.setLateFeeReduction(q.lateFeeReduction());
        boolean changed =
                payable.signum() > 0
                        || q.feeReduction().signum() > 0
                        || q.lateFeeReduction().signum() > 0;
        item.setStatus(changed ? "SUCCEEDED" : "SKIPPED");
        if (!changed) item.setReason("INSUFFICIENT_BALANCE");
        return item;
    }

    private static FundsInput offset(
            Request r, FundAccountEntity a, ReceivableEntity bill, BigDecimal amount) {
        var i = new FundsInput();
        i.setAction("OFFSET");
        i.setType("ADVANCE");
        i.setCompanyId(a.getCompanyId());
        i.setAccountId(a.getId());
        i.setVersion(a.getVersion());
        i.setAmount(amount);
        i.setOccurredAt(r.occurredAt());
        i.setPayerName(a.getCustomerName());
        i.setReason("自动结转");
        i.setItems(List.of(new CashierInput.Item(bill.getId(), bill.getVersion(), amount)));
        return i;
    }

    private Mono<CarryForwardItemEntity> existing(String project, String id, String fingerprint) {
        return items.createQuery()
                .where("id", id)
                .and("projectId", project)
                .fetchOne()
                .flatMap(
                        item -> {
                            if (!fingerprint.equals(item.getRequestFingerprint()))
                                return Mono.error(error("carry_request_conflict", 409));
                            return AssetsHolder.assertPermission(
                                            AssetType.of("space"),
                                            item.getSpaceId(),
                                            CrudAssetPermission.save)
                                    .thenReturn(item);
                        });
    }

    private Mono<CarryForwardPlanEntity> lockedPlan(String project, String id) {
        var query = new QueryParamEntity();
        query.setForUpdate(true);
        return plans.createQuery()
                .setParam(query)
                .where("id", id)
                .and("projectId", project)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("carry_plan_not_found", 404)));
    }

    private Mono<ReceivableEntity> bill(String project, String id, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return bills.createQuery()
                .setParam(query)
                .where("id", id)
                .and("projectId", project)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("receivable_not_found", 404)))
                .flatMap(
                        row ->
                                AssetsHolder.assertPermission(
                                                AssetType.of("space"),
                                                row.getSpaceId(),
                                                CrudAssetPermission.save)
                                        .thenReturn(row));
    }

    private static void checkBill(CarryForwardPlanInput p, ReceivableEntity row, LocalDate date) {
        if (!p.getSubjectIds().contains(row.getSubjectId())
                || !CarryForwardRules.includesStandard(p, row.getStandardId())
                || row.getCalcStartDate().compareTo(p.getPeriodStart()) < 0
                || row.getCalcEndDate().compareTo(p.getPeriodEnd()) > 0)
            throw error("carry_bill_invalid", 409);
        if (row.getCalculatedAsOfDate() != null
                && date.isBefore(LocalDate.parse(row.getCalculatedAsOfDate())))
            throw error("receivable_date_regression", 409);
    }

    private static void validate(Request r, LocalDate date) {
        if (r == null || date == null || r.planVersion() == null || r.planVersion() < 0)
            throw error("carry_plan_invalid", 400);
        for (var id : Arrays.asList(r.planId(), r.receivableId()))
            if (id == null || id.isBlank() || id.length() > 64)
                throw error("carry_plan_invalid", 400);
        if (r.runId() == null
                || r.runId().isBlank()
                || r.runId().length() > 100
                || r.occurredAt() == null
                || r.occurredAt().length() > 32) throw error("carry_plan_invalid", 400);
        if (r.accountId() != null && (r.accountId().isBlank() || r.accountId().length() > 64))
            throw error("carry_plan_invalid", 400);
        try {
            if (LocalDateTime.parse(r.occurredAt()).toLocalDate().isAfter(date))
                throw error("carry_plan_invalid", 400);
        } catch (DateTimeException | NullPointerException failure) {
            throw error("carry_plan_invalid", 400);
        }
    }

    private static String identity(List<?> values) {
        return UUID.nameUUIDFromBytes(JSON.toJSONString(values).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static String fingerprint(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** runId由持久调度槽位生成；重试保留原业务时间和载荷。accountId为空仅允许真实全免。 */
    public record Request(
            String planId,
            Long planVersion,
            String runId,
            String receivableId,
            String accountId,
            String occurredAt) {}
}
