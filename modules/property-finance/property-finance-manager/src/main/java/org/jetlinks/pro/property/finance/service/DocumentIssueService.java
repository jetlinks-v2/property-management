package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.document.*;
import org.jetlinks.pro.property.finance.payment.PaymentRules;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** 独立开具入口：确认后占用与票据、冻结税务/凭证和审计全部在同一响应式事务内提交。 */
@Service
@RequiredArgsConstructor
public class DocumentIssueService {
    private final FinanceScope scope;
    private final DocumentInputRules rules;
    private final DocumentPreparationService preparation;
    private final InvoiceCompanyService companies;
    private final DocumentOccupationLedger ledger;
    private final DocumentStore store;

    @Transactional
    public Mono<DocumentStore.Detail> confirm(
            String project, Submission submission, LocalDateTime now) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            if (submission == null
                                                    || now == null
                                                    || submission.token() == null
                                                    || !submission.token().matches("[a-f0-9]{64}"))
                                                return Mono.error(
                                                        error("document_input_invalid", 400));
                                            DocumentStore.validateRequest(submission.requestId());
                                            var input = rules.normalize(submission.input());
                                            var fingerprint =
                                                    PaymentRules.digest(
                                                            store.encode(
                                                                    List.of(
                                                                            submission.token(),
                                                                            input)));
                                            return TenantHolder.currentId()
                                                    .defaultIfEmpty("")
                                                    .flatMap(
                                                            tenant ->
                                                                    issue(
                                                                            project,
                                                                            submission,
                                                                            input,
                                                                            fingerprint,
                                                                            tenant,
                                                                            auth,
                                                                            now));
                                        }))
                .onErrorMap(DocumentIssueService::duplicate)
                .as(MonoTracer.create("property.finance.document.issue"));
    }

    private Mono<DocumentStore.Detail> issue(
            String project,
            Submission request,
            DocumentIssueInput input,
            String fingerprint,
            String tenant,
            Authentication auth,
            LocalDateTime now) {
        var id =
                DocumentStore.requestKey(
                        tenant, project, auth, input.documentType(), "ISSUE", request.requestId());
        // 必须先恢复成功事实，否则本请求自己的占用会使重试预览失败。公司锁后二查消除并发窗口。
        return store.replay(project, input.documentType(), id, tenant, fingerprint)
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        companies
                                                .company(input.companyId(), true)
                                                .then(
                                                        store.replay(
                                                                project,
                                                                input.documentType(),
                                                                id,
                                                                tenant,
                                                                fingerprint))
                                                .switchIfEmpty(
                                                        Mono.defer(
                                                                () ->
                                                                        prepareAndPersist(
                                                                                project,
                                                                                id,
                                                                                tenant,
                                                                                request.token(),
                                                                                input,
                                                                                fingerprint,
                                                                                auth,
                                                                                now)))));
    }

    private Mono<DocumentStore.Detail> prepareAndPersist(
            String project,
            String id,
            String tenant,
            String token,
            DocumentIssueInput input,
            String fingerprint,
            Authentication auth,
            LocalDateTime now) {
        // 全链共用公司→全部应收/实收行→规则→占用journal→票据的锁序。
        return preparation
                .prepare(project, input, now, true)
                .flatMap(
                        prepared -> {
                            if (!token.equals(prepared.view().token()))
                                return Mono.error(error("document_preview_changed", 409));
                            var claims = new ArrayList<DocumentOccupationLedger.Claim>();
                            for (int index = 0; index < prepared.sources().size(); index++) {
                                claims.add(
                                        new DocumentOccupationLedger.Claim(
                                                prepared.sources().get(index).canonical(),
                                                new BigDecimal(
                                                        prepared.input()
                                                                .items()
                                                                .get(index)
                                                                .amount())));
                            }
                            return ledger.occupy(
                                            project,
                                            id,
                                            input.documentType(),
                                            claims,
                                            prepared.sources(),
                                            auth)
                                    .then(
                                            store.insertBlue(
                                                    id,
                                                    tenant,
                                                    project,
                                                    fingerprint,
                                                    prepared,
                                                    now.toString(),
                                                    auth));
                        });
    }

    public Mono<DocumentStore.Detail> detail(String project, String type, String id) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        Mono.defer(
                                () -> {
                                    DocumentStore.validateType(type);
                                    return TenantHolder.currentId()
                                            .defaultIfEmpty("")
                                            .flatMap(
                                                    tenant ->
                                                            store.require(
                                                                    project, type, id, tenant,
                                                                    false))
                                            .flatMap(
                                                    row ->
                                                            store.detail(
                                                                    row, CrudAssetPermission.read));
                                }));
    }

    static Throwable duplicate(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("document_request_conflict", 409);
        return failure;
    }

    public record Submission(String requestId, String token, DocumentIssueInput input) {}
}
