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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** 原实收的可退额、应收欠费与目标预收同事务变动，内部转存不产生现金流。 */
@Service
@RequiredArgsConstructor
public class PaymentToAdvanceService {
    private final FinanceScope scope;
    private final FinancialPeriodGate periodGate;
    private final FundSourceService accounts;
    private final CashierConfiguration configuration;
    private final RefundSourceService sources;
    private final RefundLedger refunds;
    private final FundLedger ledger;
    private final ReactiveRepository<FundEntryEntity, String> entries;

    public Mono<Preview> preview(String project, PaymentToAdvanceInput input, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(
                        Mono.defer(
                                () -> {
                                    validate(input, date);
                                    return prepare(project, input, date, false)
                                            .map(p -> preview(input, date, p));
                                }));
    }

    @Transactional
    public Mono<FundsService.Result> confirm(String project, Submission request, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(auth -> Mono.defer(() -> confirm(project, request, date, auth)))
                .onErrorMap(this::duplicate)
                .as(MonoTracer.create("/property/finance/payment-to-advance/confirm"));
    }

    private Mono<FundsService.Result> confirm(
            String project, Submission r, LocalDate date, Authentication auth) {
        if (r == null
                || r.requestId() == null
                || !r.requestId().matches("[A-Za-z0-9_-]{8,100}")
                || r.token() == null
                || !r.token().matches("[a-f0-9]{64}"))
            return Mono.error(error("fund_invalid", 400));
        validate(r.input(), date);
        var id =
                UUID.nameUUIDFromBytes(
                                ("payment-to-advance:"
                                                + project.length()
                                                + ":"
                                                + project
                                                + r.requestId())
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString();
        var fingerprint = r.input().fingerprint();
        return periodGate.inOpenPeriod(
                project,
                LocalDateTime.parse(r.input().getOccurredAt()).toLocalDate(),
                existing(project, id, fingerprint),
                () ->
                        prepare(project, r.input(), date, true)
                                .flatMap(
                                        p ->
                                                existing(project, id, fingerprint)
                                                        .switchIfEmpty(
                                                                Mono.defer(
                                                                        () ->
                                                                                write(
                                                                                        id, r, date,
                                                                                        p,
                                                                                        auth)))));
    }

    private void validate(PaymentToAdvanceInput i, LocalDate date) {
        if (i == null) throw error("fund_invalid", 400);
        i.validate(date);
    }

    private Mono<Prepared> prepare(
            String project, PaymentToAdvanceInput i, LocalDate date, boolean lock) {
        var target = i.target();
        // 先锁基础档案，再按公司ID排序，避免跨公司相反方向转存产生反向锁序。
        return sources.load(project, i.refund("target", "实收转预存"), false)
                .then(Mono.defer(() -> accounts.prepare(project, target, lock)))
                .thenMany(
                        Flux.fromIterable(
                                        new TreeSet<>(
                                                List.of(i.getCompanyId(), i.getTargetCompanyId())))
                                .concatMap(id -> configuration.balanceCompany(project, id, lock)))
                .collectMap(FinanceCompanyEntity::getId)
                .flatMap(
                        companies ->
                                (lock
                                                ? accounts.lockReferences(project, target)
                                                : Mono.<Void>empty())
                                        .then(accounts.prepare(project, target, false))
                                        .flatMap(
                                                a ->
                                                        sources.load(
                                                                        project,
                                                                        i.refund(
                                                                                a.source().getId(),
                                                                                a.source()
                                                                                        .getCustomerName()),
                                                                        lock)
                                                                .flatMap(
                                                                        s ->
                                                                                (lock
                                                                                                ? accounts
                                                                                                        .lock(
                                                                                                                project,
                                                                                                                a)
                                                                                                : Mono
                                                                                                        .just(
                                                                                                                a))
                                                                                        .map(
                                                                                                locked ->
                                                                                                        prepared(
                                                                                                                i,
                                                                                                                date,
                                                                                                                locked,
                                                                                                                s,
                                                                                                                companies)))));
    }

    private Prepared prepared(
            PaymentToAdvanceInput i,
            LocalDate date,
            FundSourceService.Prepared a,
            RefundSourceService.Sources s,
            Map<String, FinanceCompanyEntity> companies) {
        var account = a.source();
        var source = companies.get(i.getCompanyId());
        var target = companies.get(i.getTargetCompanyId());
        if (source == null || target == null) throw error("fund_source_invalid", 409);
        if (account.getBalance().add(i.getAmount()).precision()
                        - account.getBalance().add(i.getAmount()).scale()
                > 18) throw error("fund_invalid", 400);
        account.setCompanyName(target.getCompanyName());
        var shares =
                RefundRules.allocateToAdvance(
                        i.refund(account.getId(), account.getCustomerName()), s.lines(), date);
        return new Prepared(a, source, target, s, shares);
    }

    private Preview preview(PaymentToAdvanceInput i, LocalDate date, Prepared p) {
        var a = p.target().source();
        // Bean与显式字段参与token，Fastjson 1.x不能以record整体作为来源指纹。
        var token =
                PaymentRules.digest(
                        JSON.toJSONString(
                                List.of(
                                        i.fingerprint(),
                                        date.toString(),
                                        JSON.toJSONString(a),
                                        Objects.toString(a.getSourceIdentity(), ""),
                                        p.target().referenceIdentity(),
                                        JSON.toJSONString(p.sourceCompany()),
                                        JSON.toJSONString(p.targetCompany()),
                                        JSON.toJSONString(p.sources().lines()),
                                        JSON.toJSONString(p.sources().bills()))));
        var indexed = new HashMap<String, PaymentAllocationEntity>();
        p.sources().lines().forEach(line -> indexed.put(line.getId(), line));
        var allocations =
                p.shares().stream()
                        .map(
                                share -> {
                                    var line = indexed.get(share.sourceId());
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
        return new Preview(
                token,
                FundRules.money(i.getAmount()).toPlainString(),
                a.getBalance().toPlainString(),
                a.getBalance().add(i.getAmount()).toPlainString(),
                a.getFullName(),
                a.getCustomerName(),
                a.getSubjectName(),
                a.getStandardName(),
                p.sourceCompany().getCompanyName(),
                a.getCompanyName(),
                allocations);
    }

    private Mono<FundsService.Result> write(
            String id, Submission r, LocalDate date, Prepared p, Authentication auth) {
        if (!preview(r.input(), date, p).token().equals(r.token()))
            return Mono.error(error("fund_preview_expired", 409));
        var i = r.input();
        var a = p.target().source();
        var row = new RefundEntity();
        row.setId(id);
        init(row, auth);
        row.setProjectId(a.getProjectId());
        row.setDestination("BALANCE");
        row.setAccountId(a.getId());
        row.setCompanyId(p.sourceCompany().getId());
        row.setCompanyName(p.sourceCompany().getCompanyName());
        row.setRequestFingerprint(i.fingerprint());
        row.setStatus("SUCCEEDED");
        row.setAmount(FundRules.money(i.getAmount()));
        row.setMethod("TO_ADVANCE");
        row.setMethodText("实收转预存");
        row.setRecordedId(a.getId());
        row.setReceiverName(a.getCustomerName());
        row.setRefundedAt(i.getOccurredAt());
        row.setReason(i.getReason().trim());
        row.setAttachmentFileId(i.getAttachmentFileId());
        row.setAttachmentName(i.getAttachmentName());
        var fund = i.target();
        fund.setAction("TO_ADVANCE");
        fund.setAccountId(a.getId());
        fund.setMethod("TO_ADVANCE");
        fund.setRecordedId(a.getId());
        fund.setPayerName(a.getCustomerName());
        return refunds.persist(row, p.sources(), p.shares(), auth)
                .then(
                        ledger.apply(
                                id,
                                a,
                                null,
                                fund,
                                row.getAmount(),
                                BigDecimal.ZERO.setScale(2),
                                "TO_ADVANCE",
                                "实收转预存",
                                null,
                                null,
                                i.fingerprint(),
                                auth))
                .map(entry -> new FundsService.Result(a, null, entry));
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
                                    .map(a -> new FundsService.Result(a, null, entry));
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

    public record Submission(String requestId, String token, PaymentToAdvanceInput input) {}

    public record Preview(
            String token,
            String amount,
            String beforeBalance,
            String afterBalance,
            String fullName,
            String customerName,
            String subjectName,
            String standardName,
            String sourceCompanyName,
            String companyName,
            List<RefundService.Allocation> allocations) {}

    private record Prepared(
            FundSourceService.Prepared target,
            FinanceCompanyEntity sourceCompany,
            FinanceCompanyEntity targetCompany,
            RefundSourceService.Sources sources,
            List<RefundRules.Share> shares) {}
}
