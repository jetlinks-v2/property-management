package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.audit;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.document.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.PaymentRules;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** 发票纠错重开：原蓝/红/新蓝永久保留，次数、共享额度和链指针同事务，不支持收据或流程申请。 */
@Service
@RequiredArgsConstructor
public class DocumentReopenService {
    private final FinanceScope scope;
    private final DocumentRules rules;
    private final DocumentPreparationService preparation;
    private final DocumentReverseService reverse;
    private final DocumentStore store;
    private final InvoiceCompanyService companies;
    private final DocumentOccupationLedger ledger;
    private final ReactiveRepository<DocumentEntity, String> documents;
    private final ReactiveRepository<DocumentOccupationEntity, String> occupations;

    public Mono<DocumentPreparationService.Preview> preview(
            String project, String original, Input input, LocalDateTime now) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .then(
                        Mono.defer(
                                () -> {
                                    validate(original, input, now);
                                    return tenant().flatMap(
                                                    tenant ->
                                                            prepare(
                                                                    project, original, input,
                                                                    tenant, now, false));
                                }))
                .map(draft -> draft.prepared().view())
                .as(MonoTracer.create("property.finance.document.reopen.preview"));
    }

    @Transactional
    public Mono<DocumentStore.Detail> confirm(
            String project, String original, Submission request, LocalDateTime now) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            if (request == null
                                                    || request.token() == null
                                                    || !request.token().matches("[a-f0-9]{64}"))
                                                throw error("document_input_invalid", 400);
                                            validate(original, request.input(), now);
                                            DocumentStore.validateRequest(request.requestId());
                                            var fingerprint =
                                                    PaymentRules.digest(
                                                            store.encode(
                                                                    List.of(
                                                                            original,
                                                                            request.token(),
                                                                            request.input())));
                                            return tenant().flatMap(
                                                            tenant -> {
                                                                var id =
                                                                        DocumentStore.requestKey(
                                                                                tenant,
                                                                                project,
                                                                                auth,
                                                                                "INVOICE",
                                                                                "REOPEN",
                                                                                request
                                                                                        .requestId());
                                                                // 成功结果先恢复，不能因自己的后继、已消耗次数、跨日或新额度而重做红冲。
                                                                return store.replay(
                                                                                project,
                                                                                "INVOICE",
                                                                                id,
                                                                                tenant,
                                                                                fingerprint)
                                                                        .switchIfEmpty(
                                                                                Mono.defer(
                                                                                        () ->
                                                                                                lockAndConfirm(
                                                                                                        project,
                                                                                                        original,
                                                                                                        request,
                                                                                                        now,
                                                                                                        auth,
                                                                                                        tenant,
                                                                                                        id,
                                                                                                        fingerprint)));
                                                            });
                                        }))
                .onErrorMap(DocumentIssueService::duplicate)
                .as(MonoTracer.create("property.finance.document.reopen.confirm"));
    }

    private Mono<DocumentStore.Detail> lockAndConfirm(
            String project,
            String original,
            Submission request,
            LocalDateTime now,
            Authentication auth,
            String tenant,
            String id,
            String fingerprint) {
        return store.require(project, "INVOICE", original, tenant, false)
                .flatMap(row -> store.lines(row, CrudAssetPermission.save).thenReturn(row))
                .flatMap(row -> companies.company(row.getCompanyId(), true))
                .then(store.replay(project, "INVOICE", id, tenant, fingerprint))
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        prepare(
                                                        project,
                                                        original,
                                                        request.input(),
                                                        tenant,
                                                        now,
                                                        true)
                                                .flatMap(
                                                        draft -> {
                                                            if (!request.token()
                                                                    .equals(
                                                                            draft.prepared()
                                                                                    .view()
                                                                                    .token()))
                                                                throw error(
                                                                        "document_preview_changed",
                                                                        409);
                                                            // 与普通红冲共用反向账本。随后重读来源版本，禁止拿释放前的CAS对象占用新票。
                                                            Mono<?> reversed =
                                                                    "SIMULATED_ISSUED"
                                                                                    .equals(
                                                                                            draft.original()
                                                                                                    .getStatus())
                                                                            ? reverse.reverse(
                                                                                    project,
                                                                                    "INVOICE",
                                                                                    original,
                                                                                    new DocumentReverseService
                                                                                            .Input(
                                                                                            id,
                                                                                            draft.original()
                                                                                                    .getVersion(),
                                                                                            request.input()
                                                                                                    .reason()
                                                                                                    .trim()),
                                                                                    now)
                                                                            : Mono.empty();
                                                            return reversed.then(
                                                                            Mono.defer(
                                                                                    () ->
                                                                                            preparation
                                                                                                    .prepareReopen(
                                                                                                            project,
                                                                                                            draft.prepared()
                                                                                                                    .input(),
                                                                                                            now,
                                                                                                            true,
                                                                                                            Map
                                                                                                                    .of())))
                                                                    .flatMap(
                                                                            fresh ->
                                                                                    persist(
                                                                                            project,
                                                                                            tenant,
                                                                                            id,
                                                                                            fingerprint,
                                                                                            draft
                                                                                                    .original(),
                                                                                            fresh,
                                                                                            request.input()
                                                                                                    .reason()
                                                                                                    .trim(),
                                                                                            now,
                                                                                            auth));
                                                        })));
    }

    private Mono<Draft> prepare(
            String project,
            String original,
            Input input,
            String tenant,
            LocalDateTime now,
            boolean lock) {
        // 公司锁在调用方；先全部来源、规则、journal，最后才锁主单，保持与收退款和普通红冲同序。
        return store.require(project, "INVOICE", original, tenant, false)
                .flatMap(
                        row ->
                                store.lines(row, CrudAssetPermission.save)
                                        .flatMap(
                                                lines -> {
                                                    assertEligible(row, input, now);
                                                    var detail = store.view(row, lines);
                                                    var items =
                                                            detail.lines().stream()
                                                                    .map(
                                                                            line -> {
                                                                                var snapshot =
                                                                                        line
                                                                                                .snapshot();
                                                                                return new DocumentIssueInput
                                                                                        .Item(
                                                                                        snapshot
                                                                                                .sourceType(),
                                                                                        snapshot
                                                                                                .sourceId(),
                                                                                        snapshot
                                                                                                .amount(),
                                                                                        snapshot
                                                                                                .collectionMethod(),
                                                                                        snapshot
                                                                                                .differenceAmount(),
                                                                                        snapshot
                                                                                                .voucher());
                                                                            })
                                                                    .toList();
                                                    var issueInput =
                                                            new DocumentIssueInput(
                                                                    "INVOICE",
                                                                    row.getCompanyId(),
                                                                    input.buyer(),
                                                                    input.invoiceType(),
                                                                    input.remark(),
                                                                    items);
                                                    return credits(row, lines)
                                                            .flatMap(
                                                                    credits ->
                                                                            preparation
                                                                                    .prepareReopen(
                                                                                            project,
                                                                                            issueInput,
                                                                                            now,
                                                                                            lock,
                                                                                            credits)
                                                                                    .map(
                                                                                            prepared -> {
                                                                                                if (!rules
                                                                                                        .canReopen(
                                                                                                                row
                                                                                                                        .getReopenCount(),
                                                                                                                prepared.rule()
                                                                                                                        .maxReopenTimes()))
                                                                                                    throw error(
                                                                                                            "document_reopen_limit",
                                                                                                            409);
                                                                                                assertFrozenTax(
                                                                                                        detail,
                                                                                                        prepared);
                                                                                                var
                                                                                                        view =
                                                                                                                prepared
                                                                                                                        .view();
                                                                                                var
                                                                                                        token =
                                                                                                                PaymentRules
                                                                                                                        .digest(
                                                                                                                                store
                                                                                                                                        .encode(
                                                                                                                                                List
                                                                                                                                                        .of(
                                                                                                                                                                view
                                                                                                                                                                        .token(),
                                                                                                                                                                row
                                                                                                                                                                        .getId(),
                                                                                                                                                                row
                                                                                                                                                                        .getVersion(),
                                                                                                                                                                row
                                                                                                                                                                        .getStatus(),
                                                                                                                                                                row
                                                                                                                                                                        .getReopenCount(),
                                                                                                                                                                input)));
                                                                                                var
                                                                                                        bound =
                                                                                                                new DocumentPreparationService
                                                                                                                        .Preview(
                                                                                                                        token,
                                                                                                                        view
                                                                                                                                .documentType(),
                                                                                                                        view
                                                                                                                                .companyId(),
                                                                                                                        view
                                                                                                                                .companyName(),
                                                                                                                        view
                                                                                                                                .sellerTaxNum(),
                                                                                                                        view
                                                                                                                                .customerId(),
                                                                                                                        view
                                                                                                                                .customerName(),
                                                                                                                        view
                                                                                                                                .buyer(),
                                                                                                                        view
                                                                                                                                .invoiceType(),
                                                                                                                        view
                                                                                                                                .issueScene(),
                                                                                                                        view
                                                                                                                                .businessDate(),
                                                                                                                        view
                                                                                                                                .amount(),
                                                                                                                        view
                                                                                                                                .taxAmount(),
                                                                                                                        view
                                                                                                                                .netAmount(),
                                                                                                                        view
                                                                                                                                .rule(),
                                                                                                                        view
                                                                                                                                .lines());
                                                                                                return new Draft(
                                                                                                        row,
                                                                                                        new DocumentPreparationService
                                                                                                                .Prepared(
                                                                                                                prepared
                                                                                                                        .input(),
                                                                                                                prepared
                                                                                                                        .sources(),
                                                                                                                prepared
                                                                                                                        .rule(),
                                                                                                                bound));
                                                                                            }));
                                                }));
    }

    private Mono<Map<DocumentRules.CanonicalSource, BigDecimal>> credits(
            DocumentEntity row, List<DocumentLineEntity> lines) {
        var query = new QueryParamEntity();
        query.setPageSize(1001);
        return occupations
                .createQuery()
                .setParam(query)
                .where("projectId", row.getProjectId())
                .and("documentId", row.getId())
                .and("deleted", false)
                .fetch()
                .collectList()
                .map(
                        journal -> {
                            if (journal.size() != lines.size())
                                throw error("document_record_invalid", 409);
                            var expected = new HashMap<DocumentRules.CanonicalSource, BigDecimal>();
                            for (var line : lines)
                                expected.put(
                                        new DocumentRules.CanonicalSource(
                                                line.getSourceType(), line.getSourceId()),
                                        line.getAmount());
                            var active = "SIMULATED_ISSUED".equals(row.getStatus());
                            var credits = new HashMap<DocumentRules.CanonicalSource, BigDecimal>();
                            for (var entry : journal) {
                                var key =
                                        new DocumentRules.CanonicalSource(
                                                entry.getSourceType(), entry.getSourceId());
                                var amount = expected.remove(key);
                                if (amount == null
                                        || amount.compareTo(entry.getAmount()) != 0
                                        || !row.getCompanyId().equals(entry.getCompanyId())
                                        || !"INVOICE".equals(entry.getDocumentType())
                                        || !(active ? "ACTIVE" : "RELEASED")
                                                .equals(entry.getState()))
                                    throw error("document_occupation_conflict", 409);
                                if (active) credits.put(key, amount);
                            }
                            if (!expected.isEmpty()) throw error("document_record_invalid", 409);
                            return Map.copyOf(credits);
                        });
    }

    private Mono<DocumentStore.Detail> persist(
            String project,
            String tenant,
            String id,
            String fingerprint,
            DocumentEntity original,
            DocumentPreparationService.Prepared fresh,
            String reason,
            LocalDateTime now,
            Authentication auth) {
        var claims =
                fresh.view().lines().stream()
                        .map(
                                line ->
                                        new DocumentOccupationLedger.Claim(
                                                new DocumentRules.CanonicalSource(
                                                        line.sourceType(), line.sourceId()),
                                                new BigDecimal(line.amount())))
                        .toList();
        return ledger.occupy(project, id, "INVOICE", claims, fresh.sources(), auth)
                .then(store.require(project, "INVOICE", original.getId(), tenant, true))
                .flatMap(
                        locked -> {
                            if (!"SIMULATED_REVERSED".equals(locked.getStatus())
                                    || locked.getReopenDocumentId() != null)
                                throw error("document_reopen_conflict", 409);
                            return store.insertBlue(
                                            id,
                                            tenant,
                                            project,
                                            fingerprint,
                                            fresh,
                                            now.toString(),
                                            auth,
                                            locked)
                                    .flatMap(
                                            result ->
                                                    audit(
                                                                    documents.createUpdate(),
                                                                    auth,
                                                                    locked.getVersion())
                                                            .set("reopenDocumentId", id)
                                                            .where("id", locked.getId())
                                                            .and("version", locked.getVersion())
                                                            .and("status", "SIMULATED_REVERSED")
                                                            .isNull("reopenDocumentId")
                                                            .execute()
                                                            .flatMap(
                                                                    changed ->
                                                                            changed == 1
                                                                                    ? Mono.empty()
                                                                                    : Mono.error(
                                                                                            error(
                                                                                                    "document_reopen_conflict",
                                                                                                    409)))
                                                            .then(
                                                                    store.operation(
                                                                            PaymentRules.digest(
                                                                                    "reopen-log:"
                                                                                            + id),
                                                                            locked,
                                                                            id,
                                                                            "REOPEN",
                                                                            fingerprint,
                                                                            locked.getStatus(),
                                                                            locked.getStatus(),
                                                                            reason,
                                                                            auth))
                                                            .thenReturn(result));
                        });
    }

    private static void assertFrozenTax(
            DocumentStore.Detail original, DocumentPreparationService.Prepared prepared) {
        for (int index = 0; index < original.lines().size(); index++) {
            var old = original.lines().get(index).snapshot();
            var next = prepared.view().lines().get(index);
            if (!Objects.equals(old.taxItemId(), next.taxItemId())
                    || !Objects.equals(old.goodsCode(), next.goodsCode())
                    || !Objects.equals(old.goodsName(), next.goodsName())
                    || !Objects.equals(old.taxRate(), next.taxRate())
                    || !Objects.equals(old.includingTaxFlag(), next.includingTaxFlag())
                    || !Objects.equals(old.taxAmount(), next.taxAmount())
                    || !Objects.equals(old.netAmount(), next.netAmount()))
                throw error("document_source_snapshot_invalid", 409);
        }
    }

    private static void assertEligible(DocumentEntity row, Input input, LocalDateTime now) {
        if (!Objects.equals(row.getVersion(), input.version())
                || row.getOriginalDocumentId() != null
                || !Set.of("SIMULATED_ISSUED", "SIMULATED_REVERSED").contains(row.getStatus())
                || row.getReopenDocumentId() != null
                || now.isBefore(LocalDateTime.parse(row.getIssuedAt())))
            throw error("document_reopen_conflict", 409);
        if (row.getRootDocumentId() == null
                || row.getReopenCount() == null
                || row.getReopenCount() < 0
                || "SIMULATED_REVERSED".equals(row.getStatus())
                        && row.getReverseDocumentId() == null)
            throw error("document_record_invalid", 409);
    }

    private static void validate(String original, Input input, LocalDateTime now) {
        if (original == null
                || original.isBlank()
                || original.length() > 64
                || now == null
                || input == null
                || input.version() == null
                || input.version() < 0
                || input.reason() == null
                || input.reason().isBlank()
                || input.reason().trim().length() > 500) throw error("document_input_invalid", 400);
    }

    private static Mono<String> tenant() {
        return TenantHolder.currentId().defaultIfEmpty("");
    }

    private record Draft(DocumentEntity original, DocumentPreparationService.Prepared prepared) {}

    public record Input(
            Long version,
            DocumentIssueInput.Buyer buyer,
            String invoiceType,
            String remark,
            String reason) {}

    public record Submission(String requestId, String token, Input input) {}
}
