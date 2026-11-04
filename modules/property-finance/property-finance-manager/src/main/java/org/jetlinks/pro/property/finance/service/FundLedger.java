package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;

/** 资金余额与变动流水只允许在持锁业务事务中同时落库。 */
@Component
@RequiredArgsConstructor
public class FundLedger {
    private final ReactiveRepository<FundAccountEntity, String> accounts;
    private final ReactiveRepository<FundEntryEntity, String> entries;
    private final FundCreditLedger credits;

    Mono<FundEntryEntity> apply(
            String transaction,
            FundAccountEntity a,
            FundAccountEntity peer,
            FundsInput input,
            BigDecimal delta,
            BigDecimal cash,
            String method,
            String methodText,
            String bankName,
            String paymentId,
            String fingerprint,
            Authentication auth) {
        var entry = new FundEntryEntity();
        entry.setId(peer != null && delta.signum() > 0 ? transaction + ":in" : transaction);
        init(entry, auth);
        entry.setProjectId(a.getProjectId());
        entry.setSpaceId(a.getSpaceId());
        entry.setAccountId(a.getId());
        entry.setTransactionId(transaction);
        entry.setAction(
                peer != null
                        ? (delta.signum() > 0 ? "TRANSFER_IN" : "TRANSFER_OUT")
                        : input.getAction());
        entry.setCompanyId(a.getCompanyId());
        entry.setCompanyName(a.getCompanyName());
        entry.setFullName(a.getFullName());
        entry.setCustomerName(a.getCustomerName());
        entry.setSubjectName(a.getSubjectName());
        entry.setStandardName(a.getStandardName());
        entry.setPeerAccountId(peer == null ? null : peer.getId());
        entry.setPaymentId(paymentId);
        entry.setSourceEntryId(input.getSourceEntryId());
        if ("RETURN_OFFSET".equals(input.getAction()) || "TO_ADVANCE".equals(input.getAction()))
            entry.setRefundId(transaction);
        entry.setOccurredAt(input.getOccurredAt());
        entry.setPayerName(input.getPayerName());
        entry.setMethod(method);
        entry.setMethodText(methodText);
        entry.setRecordedId(input.getRecordedId());
        entry.setBankName(bankName);
        entry.setReason(input.getReason());
        entry.setAttachmentFileId(input.getAttachmentFileId());
        entry.setAttachmentName(input.getAttachmentName());
        entry.setRequestFingerprint(fingerprint);
        entry.setInputSnapshotJson(FundRules.canonical(input));
        entry.setAmount(delta);
        entry.setCashAmount(cash);
        entry.setBeforeBalance(a.getBalance());
        entry.setAfterBalance(a.getBalance().add(delta));
        var newRow = a.getCreateTime() == null;
        Mono<?> persist;
        if (newRow) {
            init(a, auth);
            a.setBalance(entry.getAfterBalance());
            persist = accounts.insert(a);
        } else {
            persist =
                    audit(accounts.createUpdate(), auth, a.getVersion())
                            .set("balance", entry.getAfterBalance())
                            .set("relationshipId", a.getRelationshipId())
                            .set("customerName", a.getCustomerName())
                            .set("fullName", a.getFullName())
                            .set("standardId", a.getStandardId())
                            .set("standardName", a.getStandardName())
                            .set("subjectName", a.getSubjectName())
                            .set("companyName", a.getCompanyName())
                            .where("id", a.getId())
                            .and("projectId", a.getProjectId())
                            .and("version", a.getVersion())
                            .and("deleted", false)
                            .execute()
                            .flatMap(SubjectService::changed)
                            .doOnSuccess(
                                    v -> {
                                        a.setBalance(entry.getAfterBalance());
                                        a.setVersion(a.getVersion() + 1);
                                    });
        }
        return snapshot(entry)
                .then(persist)
                .then(credits.record(entry, auth))
                .then(entries.insert(entry))
                .thenReturn(entry);
    }

    /** 撤销显示原存入的名称快照，不把当前账户资料改回历史版本。 */
    private Mono<Void> snapshot(FundEntryEntity entry) {
        if (!"REVOKE_TOPUP".equals(entry.getAction())) return Mono.empty();
        return entries.createQuery()
                .where("id", entry.getSourceEntryId())
                .and("projectId", entry.getProjectId())
                .and("accountId", entry.getAccountId())
                .and("action", "TOPUP")
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(FinanceScope.error("fund_revoke_source_blocked", 409)))
                .doOnNext(
                        original -> {
                            entry.setFullName(original.getFullName());
                            entry.setCustomerName(original.getCustomerName());
                            entry.setSubjectName(original.getSubjectName());
                            entry.setStandardName(original.getStandardName());
                            entry.setCompanyName(original.getCompanyName());
                        })
                .then();
    }
}
