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

import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.*;

/** 红冲/作废只反向原票据的精确占用；负票、原票状态和操作审计同事务，不修改原快照。 */
@Service
@RequiredArgsConstructor
public class DocumentReverseService {
    private final FinanceScope scope;
    private final InvoiceCompanyService companies;
    private final DocumentSourceService sources;
    private final DocumentOccupationLedger ledger;
    private final DocumentStore store;
    private final ReactiveRepository<DocumentEntity, String> documents;

    @Transactional
    public Mono<DocumentStore.Detail> reverse(
            String project, String type, String id, Input input, LocalDateTime now) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            DocumentStore.validateType(type);
                                            if (id == null || id.isBlank() || id.length() > 64)
                                                return Mono.error(error("document_not_found", 404));
                                            if (input == null
                                                    || now == null
                                                    || input.version() == null
                                                    || input.version() < 0
                                                    || input.reason() == null
                                                    || input.reason().isBlank()
                                                    || input.reason().trim().length() > 500)
                                                return Mono.error(
                                                        error("document_reverse_invalid", 400));
                                            DocumentStore.validateRequest(input.requestId());
                                            var normalized =
                                                    new Input(
                                                            input.requestId(),
                                                            input.version(),
                                                            input.reason().trim());
                                            var fingerprint =
                                                    PaymentRules.digest(
                                                            store.encode(
                                                                    List.of(
                                                                            id,
                                                                            normalized.version(),
                                                                            normalized.reason())));
                                            return TenantHolder.currentId()
                                                    .defaultIfEmpty("")
                                                    .flatMap(
                                                            tenant ->
                                                                    reverse(
                                                                            project,
                                                                            type,
                                                                            id,
                                                                            tenant,
                                                                            normalized,
                                                                            fingerprint,
                                                                            auth,
                                                                            now));
                                        }))
                .onErrorMap(DocumentIssueService::duplicate)
                .as(MonoTracer.create("property.finance.document.reverse"));
    }

    private Mono<DocumentStore.Detail> reverse(
            String project,
            String type,
            String id,
            String tenant,
            Input input,
            String fingerprint,
            Authentication auth,
            LocalDateTime now) {
        var operation =
                DocumentStore.requestKey(tenant, project, auth, type, "REVERSE", input.requestId());
        return store.operationReplay(project, type, operation, id, tenant, fingerprint)
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        lockCompanyAndReverse(
                                                project,
                                                type,
                                                id,
                                                tenant,
                                                operation,
                                                fingerprint,
                                                input,
                                                now,
                                                auth)));
    }

    private Mono<DocumentStore.Detail> lockCompanyAndReverse(
            String project,
            String type,
            String id,
            String tenant,
            String operation,
            String fingerprint,
            Input input,
            LocalDateTime now,
            Authentication auth) {
        return store.require(project, type, id, tenant, false)
                .flatMap(row -> store.lines(row, CrudAssetPermission.save).thenReturn(row))
                .flatMap(row -> companies.company(row.getCompanyId(), true))
                .then(store.operationReplay(project, type, operation, id, tenant, fingerprint))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        releaseAndPersist(
                                                project,
                                                type,
                                                id,
                                                tenant,
                                                operation,
                                                fingerprint,
                                                input,
                                                now,
                                                auth)));
    }

    private Mono<DocumentStore.Detail> releaseAndPersist(
            String project,
            String type,
            String id,
            String tenant,
            String operation,
            String fingerprint,
            Input input,
            LocalDateTime now,
            Authentication auth) {
        return store.require(project, type, id, tenant, false)
                .flatMap(
                        blue -> {
                            assertOpen(blue, input, now);
                            return store.lines(blue, CrudAssetPermission.save)
                                    .flatMap(
                                            lines ->
                                                    releaseSources(
                                                            blue,
                                                            lines,
                                                            operation,
                                                            fingerprint,
                                                            input,
                                                            now,
                                                            auth));
                        });
    }

    private Mono<DocumentStore.Detail> releaseSources(
            DocumentEntity blue,
            List<DocumentLineEntity> lines,
            String operation,
            String fingerprint,
            Input input,
            LocalDateTime now,
            Authentication auth) {
        var refs =
                lines.stream()
                        .map(
                                line ->
                                        new DocumentSourceService.Reference(
                                                line.getSourceType(), line.getSourceId()))
                        .toList();
        return sources.load(blue.getProjectId(), refs, true)
                .flatMap(
                        current -> {
                            assertReceiptNotPaid(blue, lines, current);
                            // 先锁来源及journal，最后锁主单；末端审计失败必须回滚释放，不能单独提交额度。
                            return ledger.release(
                                            blue.getProjectId(),
                                            blue.getId(),
                                            input.reason(),
                                            current,
                                            auth)
                                    .then(
                                            store.require(
                                                    blue.getProjectId(),
                                                    blue.getDocumentType(),
                                                    blue.getId(),
                                                    blue.getTenantId(),
                                                    true))
                                    .flatMap(
                                            locked -> {
                                                assertOpen(locked, input, now);
                                                return persist(
                                                        locked,
                                                        lines,
                                                        operation,
                                                        fingerprint,
                                                        input.reason(),
                                                        now,
                                                        auth);
                                            });
                        });
    }

    private static void assertOpen(DocumentEntity row, Input input, LocalDateTime now) {
        var expected = "INVOICE".equals(row.getDocumentType()) ? "SIMULATED_ISSUED" : "ISSUED";
        if (!row.getVersion().equals(input.version())
                || !expected.equals(row.getStatus())
                || row.getOriginalDocumentId() != null
                || now.isBefore(LocalDateTime.parse(row.getIssuedAt())))
            throw error("document_reverse_conflict", 409);
    }

    private static void assertReceiptNotPaid(
            DocumentEntity row,
            List<DocumentLineEntity> lines,
            List<DocumentSourceService.Source> current) {
        if (!"RECEIPT".equals(row.getDocumentType())) return;
        // 保留旧收据付款关联保护，且阻止RECEIVABLE别名或先开后收绕过；普通发票红冲不套用此限制。
        if (lines.stream().anyMatch(line -> !"RECEIVABLE".equals(line.getOriginalSourceType()))
                || current.stream().anyMatch(source -> source.paidAmount().signum() > 0))
            throw error("document_receipt_payment_linked", 409);
    }

    private Mono<DocumentStore.Detail> persist(
            DocumentEntity blue,
            List<DocumentLineEntity> lines,
            String operation,
            String fingerprint,
            String reason,
            LocalDateTime now,
            Authentication auth) {
        boolean invoice = "INVOICE".equals(blue.getDocumentType());
        var after = invoice ? "SIMULATED_REVERSED" : "VOIDED";
        Mono<DocumentEntity> result =
                invoice
                        ? store.insertRed(
                                operation, fingerprint, blue, lines, now.toString(), reason, auth)
                        : Mono.just(blue);
        return result.flatMap(
                red -> {
                    var update =
                            audit(documents.createUpdate(), auth, blue.getVersion())
                                    .set("status", after);
                    if (invoice) update.set("reverseDocumentId", red.getId());
                    return update.where("id", blue.getId())
                            .and("version", blue.getVersion())
                            .and("status", blue.getStatus())
                            .execute()
                            .flatMap(
                                    changed ->
                                            changed == 1
                                                    ? Mono.empty()
                                                    : Mono.error(
                                                            error(
                                                                    "document_reverse_conflict",
                                                                    409)))
                            .then(
                                    store.operation(
                                            operation,
                                            blue,
                                            red.getId(),
                                            "REVERSE",
                                            fingerprint,
                                            blue.getStatus(),
                                            after,
                                            reason,
                                            auth))
                            .then(
                                    store.require(
                                            blue.getProjectId(),
                                            blue.getDocumentType(),
                                            red.getId(),
                                            blue.getTenantId(),
                                            false))
                            .flatMap(saved -> store.detail(saved, CrudAssetPermission.save));
                });
    }

    public record Input(String requestId, Long version, String reason) {}
}
