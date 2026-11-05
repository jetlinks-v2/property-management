package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.init;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** 以原冲抵为唯一来源，恢复应收与余额的同一事务；不调用现金出款配置。 */
@Service
@RequiredArgsConstructor
public class FundReturnService {
    private final FinanceScope scope;
    private final FinancialPeriodGate periodGate;
    private final FundSourceService accounts;
    private final CashierConfiguration configuration;
    private final RefundSourceService sources;
    private final RefundLedger refunds;
    private final FundLedger ledger;
    private final ReactiveRepository<FundEntryEntity, String> entries;
    private final ReactiveRepository<PaymentEntity, String> payments;
    private final ReactiveRepository<PaymentAllocationEntity, String> lines;

    public Mono<Preview> preview(String project, FundReturnInput input, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(
                        Mono.defer(
                                () -> {
                                    if (input == null)
                                        return Mono.error(error("fund_return_invalid", 400));
                                    input.validate(date);
                                    return prepare(project, input, date, false)
                                            .map(p -> preview(input, date, p));
                                }));
    }

    @Transactional
    public Mono<FundsService.Result> confirm(String project, Submission request, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> confirmAuthenticated(project, request, date, auth)))
                .onErrorMap(this::duplicate)
                .as(MonoTracer.create("/property/finance/funds/return-offset"));
    }

    private Mono<FundsService.Result> confirmAuthenticated(
            String project, Submission request, LocalDate date, Authentication auth) {
        if (request == null
                || request.requestId() == null
                || !request.requestId().matches("[A-Za-z0-9_-]{8,100}")
                || request.token() == null
                || !request.token().matches("[a-f0-9]{64}")
                || request.input() == null) return Mono.error(error("fund_return_invalid", 400));
        request.input().validate(date);
        var id =
                UUID.nameUUIDFromBytes(
                                ("fund-return:"
                                                + project.length()
                                                + ":"
                                                + project
                                                + request.requestId())
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString();
        var fingerprint = request.input().fingerprint();
        return periodGate.inOpenPeriod(
                project,
                LocalDateTime.parse(request.input().occurredAt()).toLocalDate(),
                existing(project, id, fingerprint),
                () ->
                        prepare(project, request.input(), date, true)
                                .flatMap(
                                        prepared ->
                                                existing(project, id, fingerprint)
                                                        .switchIfEmpty(
                                                                Mono.defer(
                                                                        () ->
                                                                                write(
                                                                                        project, id,
                                                                                        request,
                                                                                        date,
                                                                                        prepared,
                                                                                        auth)))));
    }

    private Mono<Origin> origin(String project, FundReturnInput input) {
        return entries.createQuery()
                .where("id", input.sourceEntryId())
                .and("projectId", project)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("fund_not_found", 404)))
                .flatMap(
                        entry -> {
                            if (!"OFFSET".equals(entry.getAction())
                                    || entry.getPaymentId() == null
                                    || entry.getAmount().signum() >= 0)
                                return Mono.error(error("fund_return_source_invalid", 409));
                            return accounts.account(project, entry.getAccountId(), false)
                                    .flatMap(
                                            account ->
                                                    payments.createQuery()
                                                            .where("id", entry.getPaymentId())
                                                            .and("projectId", project)
                                                            .and("deleted", false)
                                                            .fetchOne()
                                                            .switchIfEmpty(
                                                                    Mono.error(
                                                                            error(
                                                                                    "payment_not_found",
                                                                                    404)))
                                                            .flatMap(
                                                                    payment ->
                                                                            lines.createQuery()
                                                                                    .where(
                                                                                            "paymentId",
                                                                                            payment
                                                                                                    .getId())
                                                                                    .and(
                                                                                            "projectId",
                                                                                            project)
                                                                                    .and(
                                                                                            "deleted",
                                                                                            false)
                                                                                    .fetch()
                                                                                    .take(101)
                                                                                    .collectList()
                                                                                    .map(
                                                                                            rows ->
                                                                                                    validateOrigin(
                                                                                                            entry,
                                                                                                            account,
                                                                                                            payment,
                                                                                                            rows,
                                                                                                            input))));
                        });
    }

    /** 校验整笔原冲抵的账户、房屋、客户和资金方式一致，拒绝串用现金来源或其他账户。 */
    private Origin validateOrigin(
            FundEntryEntity entry,
            FundAccountEntity account,
            PaymentEntity payment,
            List<PaymentAllocationEntity> rows,
            FundReturnInput input) {
        if (rows.isEmpty()
                || rows.size() > 100
                || !"SUCCEEDED".equals(payment.getStatus())
                || !"RECEIVABLE".equals(payment.getSourceType())
                || !Objects.equals(entry.getCompanyId(), account.getCompanyId())
                || !Objects.equals(payment.getCompanyId(), account.getCompanyId())
                || !Objects.equals(entry.getSpaceId(), account.getSpaceId())
                || payment.getAmount().compareTo(entry.getAmount().negate()) != 0
                || rows.stream()
                        .anyMatch(
                                line ->
                                        !account.getType().equals(line.getMethod())
                                                || !account.getId().equals(line.getRecordedId())
                                                || !account.getSpaceId().equals(line.getSpaceId())
                                                || !account.getCustomerId()
                                                        .equals(line.getCustomerId())
                                                || line.getReceivableId() == null
                                                || !"RECEIVABLE".equals(line.getSourceType())
                                                || line.getRefundedAmount().signum() < 0
                                                || line.getRefundedAmount()
                                                                .compareTo(line.getAmount())
                                                        > 0)
                || rows.stream()
                                .map(PaymentAllocationEntity::getAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                                .compareTo(payment.getAmount())
                        != 0) throw error("fund_return_source_invalid", 409);
        if (LocalDateTime.parse(input.occurredAt())
                .isBefore(LocalDateTime.parse(entry.getOccurredAt())))
            throw error("refund_before_payment", 400);
        var available =
                rows.stream()
                        .filter(line -> line.getAmount().compareTo(line.getRefundedAmount()) > 0)
                        .toList();
        if (available.isEmpty()) throw error("refund_exceeds_balance", 409);
        return new Origin(entry, account, available);
    }

    private Mono<Prepared> prepare(
            String project, FundReturnInput input, LocalDate date, boolean lock) {
        return origin(project, input)
                .flatMap(
                        origin ->
                                configuration
                                        .balanceCompany(
                                                project, origin.account().getCompanyId(), lock)
                                        .flatMap(
                                                company ->
                                                        prepareAccount(
                                                                project, input, date, lock, origin,
                                                                company)));
    }

    private Mono<Prepared> prepareAccount(
            String project,
            FundReturnInput input,
            LocalDate date,
            boolean lock,
            Origin origin,
            FinanceCompanyEntity company) {
        var refund = refundInput(input, origin, company);
        RefundRules.validateBalance(refund, date);
        // 公司、应收和来源明细锁之后再锁余额，所有资金入口保持同一锁顺序。
        return sources.loadBalance(project, refund, lock)
                .flatMap(
                        rows ->
                                accounts.account(project, origin.account().getId(), lock)
                                        .map(
                                                account -> {
                                                    if (!Objects.equals(
                                                            account.getVersion(),
                                                            input.accountVersion()))
                                                        throw error("version_conflict", 409);
                                                    var total =
                                                            account.getBalance()
                                                                    .add(input.amount());
                                                    if (total.precision() - total.scale() > 18)
                                                        throw error("fund_return_invalid", 400);
                                                    return new Prepared(
                                                            origin.entry(),
                                                            account,
                                                            company,
                                                            rows,
                                                            RefundRules.allocateBalance(
                                                                    refund, rows.lines(), date));
                                                }));
    }

    private RefundInput refundInput(
            FundReturnInput input, Origin origin, FinanceCompanyEntity company) {
        var refund = new RefundInput();
        refund.setCompanyId(company.getId());
        refund.setMethod(origin.account().getType());
        refund.setRecordedId(origin.account().getId());
        refund.setAmount(FundRules.money(input.amount()));
        refund.setRefundedAt(input.occurredAt());
        refund.setReceiverName(origin.entry().getCustomerName());
        refund.setReason(input.reason());
        refund.setAttachmentFileId(input.attachmentFileId());
        refund.setAttachmentName(input.attachmentName());
        refund.setItems(
                origin.available().stream()
                        .map(line -> new RefundInput.Item(line.getId(), line.getVersion()))
                        .toList());
        return refund;
    }

    private Preview preview(FundReturnInput input, LocalDate date, Prepared p) {
        var index = new HashMap<String, PaymentAllocationEntity>();
        p.sources().lines().forEach(line -> index.put(line.getId(), line));
        var allocations =
                p.shares().stream()
                        .map(
                                share -> {
                                    var line = index.get(share.sourceId());
                                    return new RefundService.Allocation(
                                            line.getId(),
                                            line.getPaymentId(),
                                            line.getSourceType(),
                                            line.getReceivableId(),
                                            line.getFullName(),
                                            line.getCustomerName(),
                                            line.getSubjectName(),
                                            line.getAmount().toPlainString(),
                                            line.getRefundedAmount().toPlainString(),
                                            share.amount().toPlainString());
                                })
                        .toList();
        var token =
                PaymentRules.digest(
                        input.fingerprint()
                                + ":"
                                + date
                                + ":"
                                + JSON.toJSONString(p.account())
                                + ":"
                                + JSON.toJSONString(p.company())
                                + ":"
                                + JSON.toJSONString(p.sources().lines())
                                + ":"
                                + JSON.toJSONString(p.sources().bills()));
        return new Preview(
                token,
                FundRules.money(input.amount()).toPlainString(),
                p.account().getBalance().toPlainString(),
                p.account().getBalance().add(input.amount()).toPlainString(),
                p.entry().getFullName(),
                p.entry().getCustomerName(),
                p.entry().getSubjectName(),
                p.company().getCompanyName(),
                allocations);
    }

    private Mono<FundsService.Result> write(
            String project,
            String id,
            Submission request,
            LocalDate date,
            Prepared p,
            Authentication auth) {
        if (!preview(request.input(), date, p).token().equals(request.token()))
            return Mono.error(error("fund_preview_expired", 409));
        var input = request.input();
        var row = new RefundEntity();
        row.setId(id);
        init(row, auth);
        row.setProjectId(project);
        row.setDestination("BALANCE");
        row.setAccountId(p.account().getId());
        row.setCompanyId(p.company().getId());
        row.setCompanyName(p.company().getCompanyName());
        row.setRequestFingerprint(input.fingerprint());
        row.setStatus("SUCCEEDED");
        row.setAmount(FundRules.money(input.amount()));
        row.setMethod(p.account().getType());
        row.setMethodText("ADVANCE".equals(p.account().getType()) ? "退回预收余额" : "退回押金余额");
        row.setRecordedId(p.account().getId());
        row.setReceiverName(p.entry().getCustomerName());
        row.setRefundedAt(input.occurredAt());
        row.setReason(input.reason().trim());
        row.setAttachmentFileId(input.attachmentFileId());
        row.setAttachmentName(input.attachmentName());
        var fund = new FundsInput();
        fund.setAction("RETURN_OFFSET");
        fund.setType(p.account().getType());
        fund.setAccountId(p.account().getId());
        fund.setSourceEntryId(p.entry().getId());
        fund.setVersion(p.account().getVersion());
        fund.setAmount(row.getAmount());
        fund.setCompanyId(row.getCompanyId());
        fund.setOccurredAt(row.getRefundedAt());
        fund.setPayerName(row.getReceiverName());
        fund.setReason(row.getReason());
        fund.setMethod(row.getMethod());
        fund.setRecordedId(row.getRecordedId());
        fund.setAttachmentFileId(row.getAttachmentFileId());
        fund.setAttachmentName(row.getAttachmentName());
        return refunds.persist(row, p.sources(), p.shares(), auth)
                .then(
                        ledger.apply(
                                id,
                                p.account(),
                                null,
                                fund,
                                row.getAmount(),
                                BigDecimal.ZERO.setScale(2),
                                row.getMethod(),
                                row.getMethodText(),
                                null,
                                p.entry().getPaymentId(),
                                input.fingerprint(),
                                auth))
                .map(entry -> new FundsService.Result(p.account(), null, entry));
    }

    private Mono<FundsService.Result> existing(String project, String id, String fingerprint) {
        return entries.createQuery()
                .where("id", id)
                .and("projectId", project)
                .fetchOne()
                .flatMap(
                        entry -> {
                            if (!fingerprint.equals(entry.getRequestFingerprint()))
                                return Mono.error(error("fund_request_conflict", 409));
                            return accounts.account(project, entry.getAccountId(), false)
                                    .map(account -> new FundsService.Result(account, null, entry));
                        });
    }

    private Throwable duplicate(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("fund_request_conflict", 409);
        return failure;
    }

    public record Submission(String requestId, String token, FundReturnInput input) {}

    public record Preview(
            String token,
            String amount,
            String beforeBalance,
            String afterBalance,
            String fullName,
            String customerName,
            String subjectName,
            String companyName,
            List<RefundService.Allocation> allocations) {}

    private record Origin(
            FundEntryEntity entry,
            FundAccountEntity account,
            List<PaymentAllocationEntity> available) {}

    private record Prepared(
            FundEntryEntity entry,
            FundAccountEntity account,
            FinanceCompanyEntity company,
            RefundSourceService.Sources sources,
            List<RefundRules.Share> shares) {}
}
