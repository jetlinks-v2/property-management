package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.init;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** 临时收款唯一写入口；主单、逐方式流水同事务，不开放修改或删除资金事实。 */
@Service
@RequiredArgsConstructor
public class TemporaryService {
    private final FinanceScope scope;
    private final FinancialPeriodGate periodGate;
    private final TemporarySourceService sources;
    private final TemporaryLedger ledger;
    private final CashierConfiguration configuration;
    private final CashierService cashier;
    private final PaymentRecordService records;
    private final ReactiveRepository<PaymentEntity, String> payments;

    public Mono<Preview> preview(String project, TemporaryInput input, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(
                        Mono.defer(
                                () -> {
                                    TemporaryRules.validate(input, date);
                                    return sources.context(project, input, false)
                                            .flatMap(
                                                    context ->
                                                            configuration
                                                                    .resolve(
                                                                            project,
                                                                            input.fundingInput(),
                                                                            false)
                                                                    .flatMap(
                                                                            config ->
                                                                                    sources.prepare(
                                                                                                    project,
                                                                                                    input,
                                                                                                    context,
                                                                                                    false)
                                                                                            .map(
                                                                                                    source ->
                                                                                                            preview(
                                                                                                                    input,
                                                                                                                    date,
                                                                                                                    source,
                                                                                                                    config))));
                                }));
    }

    @Transactional
    public Mono<Detail> collect(String project, Submission request, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(auth -> Mono.defer(() -> collectAuthorized(project, request, date, auth)))
                .onErrorMap(this::duplicate)
                .as(MonoTracer.create("/property/finance/temporary/collect"));
    }

    private Mono<Detail> collectAuthorized(
            String project, Submission request, LocalDate date, Authentication auth) {
        if (request == null
                || request.requestId() == null
                || !request.requestId().matches("[A-Za-z0-9_-]{8,100}")
                || request.token() == null
                || !request.token().matches("[a-f0-9]{64}"))
            return Mono.error(error("temporary_invalid", 400));
        TemporaryRules.validate(request.input(), date);
        var fingerprint = TemporaryRules.fingerprint(request.input());
        var id =
                UUID.nameUUIDFromBytes(
                                ("temporary:"
                                                + project.length()
                                                + ":"
                                                + project
                                                + request.requestId())
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString();
        return periodGate.inOpenPeriod(
                project,
                LocalDateTime.parse(request.input().getPaidAt()).toLocalDate(),
                existing(project, id, fingerprint),
                () -> commit(project, id, fingerprint, request, date, auth));
    }

    private Mono<Detail> commit(
            String project,
            String id,
            String fingerprint,
            Submission request,
            LocalDate date,
            Authentication auth) {
        var input = request.input();
        // 与基础档案/其他柜台操作统一锁序：基础上下文→公司→科目/下发→标准。
        return sources.context(project, input, true)
                .flatMap(
                        context ->
                                configuration
                                        .resolve(project, input.fundingInput(), true)
                                        .flatMap(
                                                config ->
                                                        sources.prepare(
                                                                        project, input, context,
                                                                        true)
                                                                .flatMap(
                                                                        source ->
                                                                                existing(
                                                                                                project,
                                                                                                id,
                                                                                                fingerprint)
                                                                                        .switchIfEmpty(
                                                                                                Mono
                                                                                                        .defer(
                                                                                                                () ->
                                                                                                                        write(
                                                                                                                                project,
                                                                                                                                id,
                                                                                                                                fingerprint,
                                                                                                                                request,
                                                                                                                                date,
                                                                                                                                source,
                                                                                                                                config,
                                                                                                                                auth))))));
    }

    private Mono<Detail> write(
            String project,
            String id,
            String fingerprint,
            Submission request,
            LocalDate date,
            TemporarySourceService.Prepared source,
            CashierConfiguration.Resolved config,
            Authentication auth) {
        var input = request.input();
        if (!preview(input, date, source, config).token().equals(request.token()))
            return Mono.error(error("temporary_preview_expired", 409));
        var payment = new PaymentEntity();
        payment.setId(id);
        init(payment, auth);
        payment.setSourceType("TEMPORARY");
        payment.setProjectId(project);
        payment.setCompanyId(config.company().getId());
        payment.setCompanyName(config.company().getCompanyName());
        payment.setRequestFingerprint(fingerprint);
        payment.setStatus("SUCCEEDED");
        payment.setAmount(TemporaryRules.money(input.getAmount()));
        payment.setPayerName(input.getPayerName().trim());
        payment.setPayerRelationshipId(source.customer().relationshipId());
        payment.setPayerUserId(source.customer().userId());
        payment.setPaidAt(input.getPaidAt());
        payment.setRemark(input.getRemark());
        payment.setAttachmentName(input.getAttachmentName());
        payment.setAttachmentFileId(input.getAttachmentFileId());
        payment.setSourceSnapshotJson(source.snapshot());
        return ledger.persist(payment, input, source, config, auth)
                .map(lines -> new Detail(payment, lines, CashierService.snapshot(payment)));
    }

    private Preview preview(
            TemporaryInput input,
            LocalDate date,
            TemporarySourceService.Prepared source,
            CashierConfiguration.Resolved config) {
        var token =
                PaymentRules.digest(
                        TemporaryRules.fingerprint(input)
                                + ":"
                                + date
                                + ":"
                                + source.snapshot()
                                + ":"
                                + config.identity());
        var funding = new ArrayList<Funding>();
        for (int index = 0; index < input.getTenders().size(); index++) {
            var method = config.funding().get(index);
            funding.add(
                    new Funding(
                            method.text(),
                            method.bankName(),
                            TemporaryRules.money(input.getTenders().get(index).amount())
                                    .toPlainString()));
        }
        return new Preview(
                token,
                TemporaryRules.money(input.getAmount()).toPlainString(),
                source.house().fullName(),
                source.customer().name(),
                source.standard().getSubjectName(),
                source.standard().getStandardName(),
                config.company().getCompanyName(),
                List.copyOf(funding));
    }

    private Mono<Detail> existing(String project, String id, String fingerprint) {
        return payments.createQuery()
                .where("id", id)
                .and("projectId", project)
                .fetchOne()
                .flatMap(
                        payment -> {
                            if (!fingerprint.equals(payment.getRequestFingerprint()))
                                return Mono.error(error("cashier_request_conflict", 409));
                            return detail(project, id);
                        });
    }

    public Mono<Detail> detail(String project, String id) {
        return cashier.detail(project, id)
                .filter(detail -> "TEMPORARY".equals(detail.payment().getSourceType()))
                .switchIfEmpty(Mono.error(error("payment_not_found", 404)))
                .map(detail -> new Detail(detail.payment(), detail.lines(), detail.snapshot()));
    }

    public Mono<PagerResult<PaymentRecordService.View>> query(
            String project, QueryParamEntity input) {
        var query = SubjectService.page(input);
        query.toNestQuery(n -> n.and("sourceType", "TEMPORARY"));
        return records.query(project, query);
    }

    private Throwable duplicate(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("cashier_request_conflict", 409);
        return failure;
    }

    public record Submission(String requestId, String token, TemporaryInput input) {}

    public record Funding(String methodText, String bankName, String amount) {}

    public record Preview(
            String token,
            String total,
            String fullName,
            String customerName,
            String subjectName,
            String standardName,
            String companyName,
            List<Funding> funding) {}

    public record Detail(
            PaymentEntity payment,
            List<PaymentAllocationEntity> lines,
            Map<String, Object> snapshot) {}
}
