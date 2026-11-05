package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

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

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** 原流水不变，只新增未使用存入的整笔反向事实；公司和账户锁内核对来源账。 */
@Service
@RequiredArgsConstructor
public class FundRevokeService {
    private final FinanceScope scope;
    private final FinancialPeriodGate periodGate;
    private final FundSourceService accounts;
    private final CashierConfiguration configuration;
    private final FundCreditLedger credits;
    private final FundLedger ledger;
    private final ReactiveRepository<FundEntryEntity, String> entries;

    public Mono<Preview> preview(String project, FundRevokeInput input, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(
                        Mono.defer(
                                () -> {
                                    validate(input, date);
                                    return prepare(project, input, false)
                                            .map(p -> preview(input, date, p));
                                }));
    }

    @Transactional
    public Mono<FundsService.Result> confirm(String project, Submission r, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(auth -> Mono.defer(() -> confirm(project, r, date, auth)))
                .onErrorMap(this::duplicate)
                .as(MonoTracer.create("/property/finance/funds/revoke-topup"));
    }

    private Mono<FundsService.Result> confirm(
            String project, Submission r, LocalDate date, Authentication auth) {
        if (r == null
                || r.requestId() == null
                || !r.requestId().matches("[A-Za-z0-9_-]{8,100}")
                || r.token() == null
                || !r.token().matches("[a-f0-9]{64}"))
            return Mono.error(error("fund_revoke_invalid", 400));
        validate(r.input(), date);
        var id =
                UUID.nameUUIDFromBytes(
                                ("fund-revoke:" + project.length() + ":" + project + r.requestId())
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString();
        var fingerprint = r.input().fingerprint();
        return periodGate.inOpenPeriod(
                project,
                LocalDateTime.parse(r.input().occurredAt()).toLocalDate(),
                existing(project, id, fingerprint),
                () ->
                        prepare(project, r.input(), true)
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

    private void validate(FundRevokeInput input, LocalDate date) {
        if (input == null) throw error("fund_revoke_invalid", 400);
        input.validate(date);
    }

    private Mono<Prepared> prepare(String project, FundRevokeInput input, boolean lock) {
        return entries.createQuery()
                .where("id", input.sourceEntryId())
                .and("projectId", project)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("fund_not_found", 404)))
                .flatMap(
                        entry -> {
                            if (!"TOPUP".equals(entry.getAction())
                                    || entry.getAmount().signum() <= 0
                                    || entry.getAmount().compareTo(entry.getCashAmount()) != 0
                                    || !PaymentRules.MANUAL_METHODS.contains(entry.getMethod()))
                                return Mono.error(error("fund_revoke_source_blocked", 409));
                            if (LocalDateTime.parse(input.occurredAt())
                                    .isBefore(LocalDateTime.parse(entry.getOccurredAt())))
                                return Mono.error(error("refund_before_payment", 400));
                            // 历史收款冲销使用原方式快照，不要求当前收款字典仍启用。
                            return configuration
                                    .balanceCompany(project, entry.getCompanyId(), lock)
                                    .flatMap(
                                            company ->
                                                    accounts.account(
                                                                    project,
                                                                    entry.getAccountId(),
                                                                    lock)
                                                            .flatMap(
                                                                    account ->
                                                                            credits.source(
                                                                                            project,
                                                                                            account
                                                                                                    .getId(),
                                                                                            entry
                                                                                                    .getId())
                                                                                    .map(
                                                                                            credit ->
                                                                                                    checked(
                                                                                                            input,
                                                                                                            entry,
                                                                                                            account,
                                                                                                            credit,
                                                                                                            company))));
                        });
    }

    private Prepared checked(
            FundRevokeInput input,
            FundEntryEntity entry,
            FundAccountEntity a,
            FundCreditEntity credit,
            FinanceCompanyEntity company) {
        if (!Objects.equals(a.getVersion(), input.accountVersion()))
            throw error("version_conflict", 409);
        if (!"ADVANCE".equals(a.getType())
                || !Objects.equals(a.getCompanyId(), entry.getCompanyId())
                || !Objects.equals(a.getSpaceId(), entry.getSpaceId())
                || credit.getOriginalAmount().compareTo(entry.getAmount()) != 0
                || credit.getRemainingAmount().compareTo(credit.getOriginalAmount()) != 0
                || credit.getInvoiceOccupiedAmount().signum() > 0
                || a.getInvoiceOccupiedAmount().signum() > 0
                || Boolean.TRUE.equals(credit.getDayClosed())
                || Boolean.TRUE.equals(credit.getMonthClosed())
                || a.getBalance().compareTo(entry.getAmount()) < 0)
            throw error("fund_revoke_source_blocked", 409);
        return new Prepared(entry, a, credit, company);
    }

    private Preview preview(FundRevokeInput input, LocalDate date, Prepared p) {
        var token =
                PaymentRules.digest(
                        JSON.toJSONString(
                                List.of(
                                        input.fingerprint(),
                                        date.toString(),
                                        JSON.toJSONString(p.entry()),
                                        JSON.toJSONString(p.account()),
                                        JSON.toJSONString(p.credit()),
                                        JSON.toJSONString(p.company()))));
        return new Preview(
                token,
                p.entry().getAmount().toPlainString(),
                p.entry().getCashAmount().negate().toPlainString(),
                p.account().getBalance().toPlainString(),
                p.account().getBalance().subtract(p.entry().getAmount()).toPlainString(),
                p.entry().getFullName(),
                p.entry().getCustomerName(),
                p.entry().getCompanyName(),
                p.entry().getSubjectName(),
                p.entry().getStandardName(),
                p.entry().getMethodText(),
                p.entry().getBankName());
    }

    private Mono<FundsService.Result> write(
            String id, Submission r, LocalDate date, Prepared p, Authentication auth) {
        if (!preview(r.input(), date, p).token().equals(r.token()))
            return Mono.error(error("fund_preview_expired", 409));
        var input = new FundsInput();
        var original = p.entry();
        input.setAction("REVOKE_TOPUP");
        input.setType("ADVANCE");
        input.setAccountId(p.account().getId());
        input.setVersion(p.account().getVersion());
        input.setSourceEntryId(original.getId());
        input.setCompanyId(original.getCompanyId());
        input.setAmount(original.getAmount());
        input.setOccurredAt(r.input().occurredAt());
        input.setReason(r.input().reason().trim());
        input.setPayerName(original.getPayerName());
        input.setMethod(original.getMethod());
        input.setRecordedId(original.getRecordedId());
        input.setAttachmentFileId(r.input().attachmentFileId());
        input.setAttachmentName(r.input().attachmentName());
        return ledger.apply(
                        id,
                        p.account(),
                        null,
                        input,
                        original.getAmount().negate(),
                        original.getCashAmount().negate(),
                        original.getMethod(),
                        original.getMethodText(),
                        original.getBankName(),
                        null,
                        r.input().fingerprint(),
                        auth)
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

    public record Submission(String requestId, String token, FundRevokeInput input) {}

    public record Preview(
            String token,
            String amount,
            String cashAmount,
            String beforeBalance,
            String afterBalance,
            String fullName,
            String customerName,
            String companyName,
            String subjectName,
            String standardName,
            String methodText,
            String bankName) {}

    private record Prepared(
            FundEntryEntity entry,
            FundAccountEntity account,
            FundCreditEntity credit,
            FinanceCompanyEntity company) {}
}
