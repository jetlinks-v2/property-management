package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.audit;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.PaymentRules;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.*;

import java.time.LocalDateTime;
import java.util.*;

/** 收据单向核销登记；整批版本和资产校验、登记及日志同事务，不触碰资金账或占用账。 */
@Service
@RequiredArgsConstructor
public class ReceiptVerificationService {
    private final FinanceScope scope;
    private final FinancialPeriodGate periodGate;
    private final DocumentStore store;
    private final ReactiveRepository<DocumentEntity, String> documents;
    private final ReactiveRepository<DocumentOperationEntity, String> operations;

    @Transactional
    public Mono<List<DocumentStore.Detail>> verify(String project, Input input, LocalDateTime now) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            var normalized = normalize(input, now);
                                            return TenantHolder.currentId()
                                                    .defaultIfEmpty("")
                                                    .flatMap(
                                                            tenant -> {
                                                                var key =
                                                                        DocumentStore.requestKey(
                                                                                tenant,
                                                                                project,
                                                                                auth,
                                                                                "RECEIPT",
                                                                                "VERIFY",
                                                                                normalized
                                                                                        .requestId());
                                                                var fingerprint =
                                                                        PaymentRules.digest(
                                                                                store.encode(
                                                                                        List.of(
                                                                                                key,
                                                                                                normalized)));
                                                                return periodGate.inOpenPeriod(
                                                                        project,
                                                                        now.toLocalDate(),
                                                                        replay(
                                                                                project,
                                                                                tenant,
                                                                                key,
                                                                                fingerprint,
                                                                                normalized),
                                                                        () ->
                                                                                lockAndVerify(
                                                                                        project,
                                                                                        tenant,
                                                                                        key,
                                                                                        fingerprint,
                                                                                        normalized,
                                                                                        now,
                                                                                        auth));
                                                            });
                                        }))
                .onErrorMap(DocumentIssueService::duplicate)
                .as(MonoTracer.create("property.finance.receipt.verify"));
    }

    private Mono<List<DocumentStore.Detail>> lockAndVerify(
            String project,
            String tenant,
            String key,
            String fingerprint,
            Input input,
            LocalDateTime now,
            Authentication auth) {
        // 外层已取得期间锁；主单按ID排序且不再取得公司/来源锁，作废仍在主单锁后复查核销。
        return Flux.fromIterable(input.items())
                .concatMap(
                        item ->
                                store.require(project, "RECEIPT", item.id(), tenant, true)
                                        .flatMap(
                                                row ->
                                                        store.lines(row, CrudAssetPermission.save)
                                                                .thenReturn(row)))
                .collectList()
                .flatMap(
                        rows ->
                                replay(project, tenant, key, fingerprint, input)
                                        .switchIfEmpty(
                                                Mono.defer(
                                                        () -> {
                                                            for (int i = 0; i < rows.size(); i++) {
                                                                var row = rows.get(i);
                                                                if (!Objects.equals(
                                                                                row.getVersion(),
                                                                                input.items()
                                                                                        .get(i)
                                                                                        .version())
                                                                        || !"ISSUED"
                                                                                .equals(
                                                                                        row
                                                                                                .getStatus())
                                                                        || Boolean.TRUE.equals(
                                                                                row.getVerified())
                                                                        || row
                                                                                        .getOriginalDocumentId()
                                                                                != null
                                                                        || now.isBefore(
                                                                                LocalDateTime.parse(
                                                                                        row
                                                                                                .getIssuedAt())))
                                                                    throw error(
                                                                            "receipt_verification_conflict",
                                                                            409);
                                                            }
                                                            return Flux.range(0, rows.size())
                                                                    .concatMap(
                                                                            index -> {
                                                                                var row =
                                                                                        rows.get(
                                                                                                index);
                                                                                var operation =
                                                                                        index == 0
                                                                                                ? key
                                                                                                : PaymentRules
                                                                                                        .digest(
                                                                                                                key
                                                                                                                        + ":"
                                                                                                                        + row
                                                                                                                                .getId());
                                                                                return write(
                                                                                        row,
                                                                                        operation,
                                                                                        fingerprint,
                                                                                        input
                                                                                                .remark(),
                                                                                        now,
                                                                                        auth);
                                                                            })
                                                                    .collectList();
                                                        })));
    }

    private Mono<DocumentStore.Detail> write(
            DocumentEntity row,
            String operation,
            String fingerprint,
            String remark,
            LocalDateTime now,
            Authentication auth) {
        var update =
                audit(documents.createUpdate(), auth, row.getVersion())
                        .set("verified", true)
                        .set("verifiedAt", now.toString())
                        .set("verifiedBy", auth.getUser().getId())
                        .set(
                                "verifiedName",
                                auth.getUser().getName() == null
                                                || auth.getUser().getName().isBlank()
                                        ? auth.getUser().getUsername()
                                        : auth.getUser().getName());
        if (remark == null) update.setNull("verificationRemark");
        else update.set("verificationRemark", remark);
        return update.where("id", row.getId())
                .and("version", row.getVersion())
                .and("status", "ISSUED")
                .execute()
                .flatMap(SubjectService::changed)
                .then(
                        store.operation(
                                operation,
                                row,
                                row.getId(),
                                "VERIFY",
                                fingerprint,
                                row.getStatus(),
                                row.getStatus(),
                                remark,
                                auth))
                .then(
                        store.require(
                                row.getProjectId(),
                                "RECEIPT",
                                row.getId(),
                                row.getTenantId(),
                                false))
                .flatMap(saved -> store.detail(saved, CrudAssetPermission.save));
    }

    private Mono<List<DocumentStore.Detail>> replay(
            String project, String tenant, String key, String fingerprint, Input input) {
        // 首张日志同时作为整批成功请求的身份；完整载荷指纹阻止改票号、版本或备注后复用请求号。
        return operations
                .createQuery()
                .where("id", key)
                .and("tenantId", tenant)
                .and("projectId", project)
                .and("documentType", "RECEIPT")
                .and("action", "VERIFY")
                .fetchOne()
                .flatMap(
                        operation -> {
                            if (!fingerprint.equals(operation.getRequestFingerprint()))
                                return Mono.error(error("document_request_conflict", 409));
                            return Flux.fromIterable(input.items())
                                    .concatMap(
                                            item ->
                                                    store.require(
                                                                    project, "RECEIPT", item.id(),
                                                                    tenant, false)
                                                            .flatMap(
                                                                    row ->
                                                                            store.detail(
                                                                                    row,
                                                                                    CrudAssetPermission
                                                                                            .save)))
                                    .collectList();
                        });
    }

    private static Input normalize(Input input, LocalDateTime now) {
        if (input == null
                || now == null
                || input.items() == null
                || input.items().isEmpty()
                || input.items().size() > 100
                || input.remark() != null && input.remark().trim().length() > 200)
            throw error("receipt_verification_invalid", 400);
        DocumentStore.validateRequest(input.requestId());
        var ids = new HashSet<String>();
        for (var item : input.items()) {
            if (item == null
                    || item.id() == null
                    || item.id().isBlank()
                    || item.id().length() > 64
                    || !item.id().equals(item.id().trim())
                    || item.version() == null
                    || item.version() < 0
                    || !ids.add(item.id())) throw error("receipt_verification_invalid", 400);
        }
        String remark =
                input.remark() == null || input.remark().isBlank() ? null : input.remark().trim();
        return new Input(
                input.requestId(),
                input.items().stream().sorted(Comparator.comparing(Item::id)).toList(),
                remark);
    }

    public record Item(String id, Long version) {}

    public record Input(String requestId, List<Item> items, String remark) {}
}
