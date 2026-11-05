package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.init;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** 人工退款确认入口；线上原路退款需通道确认，不接受客户端传成功状态。 */
@Service
@RequiredArgsConstructor
public class RefundService {
    private final FinanceScope scope;
    private final FinancialPeriodGate periodGate;
    private final CashierConfiguration configuration;
    private final RefundSourceService sources;
    private final RefundLedger ledger;
    private final ReactiveRepository<RefundEntity, String> refunds;
    private final ReactiveRepository<RefundAllocationEntity, String> lines;

    public Mono<List<CashierConfiguration.CompanyOption>> options(String project) {
        return scope.project(project, CrudAssetPermission.save)
                .then(configuration.options(project));
    }

    /** 只返回已授权来源的摘要，不要求访问原整单中其他房屋。 */
    public Mono<List<RefundSourceService.Option>> sourceOptions(String project, List<String> ids) {
        return scope.project(project, CrudAssetPermission.save)
                .then(Mono.defer(() -> sources.options(project, ids)));
    }

    public Mono<Preview> preview(String project, RefundInput input, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(
                        Mono.defer(
                                () -> {
                                    RefundRules.validate(input, date);
                                    return configuration
                                            .resolve(project, input.fundingInput(), false)
                                            .flatMap(
                                                    config ->
                                                            sources.load(project, input, false)
                                                                    .map(
                                                                            rows ->
                                                                                    prepare(
                                                                                            input,
                                                                                            date,
                                                                                            config,
                                                                                            rows)));
                                }));
    }

    @Transactional
    public Mono<Detail> confirm(String project, Submission request, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            if (request == null
                                                    || request.requestId() == null
                                                    || !request.requestId()
                                                            .matches("[A-Za-z0-9_-]{8,100}")
                                                    || request.token() == null
                                                    || !request.token().matches("[a-f0-9]{64}"))
                                                return Mono.error(error("refund_invalid", 400));
                                            RefundRules.validate(request.input(), date);
                                            var id =
                                                    UUID.nameUUIDFromBytes(
                                                                    ("refund:"
                                                                                    + project
                                                                                            .length()
                                                                                    + ":"
                                                                                    + project
                                                                                    + request
                                                                                            .requestId())
                                                                            .getBytes(
                                                                                    StandardCharsets
                                                                                            .UTF_8))
                                                            .toString();
                                            var fingerprint =
                                                    RefundRules.fingerprint(request.input());
                                            return periodGate.inOpenPeriod(
                                                    project,
                                                    LocalDateTime.parse(
                                                                    request.input().getRefundedAt())
                                                            .toLocalDate(),
                                                    existing(project, id, fingerprint),
                                                    () ->
                                                            configuration
                                                                    .resolve(
                                                                            project,
                                                                            request.input()
                                                                                    .fundingInput(),
                                                                            true)
                                                                    .flatMap(
                                                                            config ->
                                                                                    sources.load(
                                                                                                    project,
                                                                                                    request
                                                                                                            .input(),
                                                                                                    true)
                                                                                            .flatMap(
                                                                                                    rows ->
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
                                                                                                                                                            config,
                                                                                                                                                            rows,
                                                                                                                                                            auth))))));
                                        }))
                .onErrorMap(this::duplicate)
                .as(MonoTracer.create("/property/finance/refund/confirm"));
    }

    private Mono<Detail> write(
            String project,
            String id,
            String fingerprint,
            Submission request,
            LocalDate date,
            CashierConfiguration.Resolved config,
            RefundSourceService.Sources sources,
            Authentication auth) {
        var preview = prepare(request.input(), date, config, sources);
        if (!preview.token().equals(request.token()))
            return Mono.error(error("refund_preview_expired", 409));
        var input = request.input();
        var funding = config.funding().get(0);
        var row = new RefundEntity();
        row.setId(id);
        init(row, auth);
        row.setProjectId(project);
        row.setCompanyId(config.company().getId());
        row.setCompanyName(config.company().getCompanyName());
        row.setRequestFingerprint(fingerprint);
        row.setStatus("SUCCEEDED");
        row.setReceiverName(input.getReceiverName().trim());
        row.setRefundedAt(input.getRefundedAt());
        row.setAmount(RefundRules.money(input.getAmount()));
        row.setMethod(funding.method());
        row.setMethodText(funding.text());
        row.setRecordedId(funding.recordedId());
        row.setBankId(funding.bankId());
        row.setBankName(funding.bankName());
        row.setBankAccount(funding.bankAccount());
        row.setReason(input.getReason().trim());
        row.setAttachmentFileId(input.getAttachmentFileId());
        row.setAttachmentName(input.getAttachmentName());
        return ledger.persist(
                        row, sources, RefundRules.allocate(input, sources.lines(), date), auth)
                .map(lines -> new Detail(row, lines));
    }

    private Preview prepare(
            RefundInput input,
            LocalDate date,
            CashierConfiguration.Resolved config,
            RefundSourceService.Sources sources) {
        var shares = RefundRules.allocate(input, sources.lines(), date);
        var byId = new HashMap<String, PaymentAllocationEntity>();
        sources.lines().forEach(line -> byId.put(line.getId(), line));
        var allocations =
                shares.stream()
                        .map(
                                share -> {
                                    var source = byId.get(share.sourceId());
                                    return new Allocation(
                                            source.getId(),
                                            source.getPaymentId(),
                                            source.getSourceType(),
                                            source.getReceivableId(),
                                            source.getFullName(),
                                            source.getCustomerName(),
                                            source.getSubjectName(),
                                            source.getAmount().toPlainString(),
                                            source.getRefundedAmount().toPlainString(),
                                            share.amount().toPlainString());
                                })
                        .toList();
        var token =
                PaymentRules.digest(
                        RefundRules.fingerprint(input)
                                + ":"
                                + date
                                + ":"
                                + config.identity()
                                + ":"
                                + JSON.toJSONString(sources.lines())
                                + ":"
                                + JSON.toJSONString(sources.bills()));
        var funding = config.funding().get(0);
        return new Preview(
                token,
                date.toString(),
                RefundRules.money(input.getAmount()).toPlainString(),
                config.company().getCompanyName(),
                funding.text(),
                funding.bankName(),
                allocations);
    }

    private Mono<Detail> existing(String project, String id, String fingerprint) {
        return refunds.createQuery()
                .where("projectId", project)
                .and("id", id)
                .fetchOne()
                .flatMap(
                        row -> {
                            if (!Objects.equals(fingerprint, row.getRequestFingerprint()))
                                return Mono.error(error("refund_request_conflict", 409));
                            return detail(project, id);
                        });
    }

    public Mono<Detail> detail(String project, String id) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        refunds.createQuery()
                                .where("projectId", project)
                                .and("id", id)
                                .and("deleted", false)
                                .fetchOne()
                                .switchIfEmpty(Mono.error(error("refund_not_found", 404))))
                .flatMap(
                        refund ->
                                lines.createQuery()
                                        .where("projectId", project)
                                        .and("refundId", id)
                                        .and("deleted", false)
                                        .fetch()
                                        .concatMap(
                                                line ->
                                                        AssetsHolder.assertPermission(
                                                                        AssetType.of("space"),
                                                                        line.getSpaceId(),
                                                                        CrudAssetPermission.read)
                                                                .thenReturn(line))
                                        .sort(
                                                Comparator.comparing(
                                                        RefundAllocationEntity::getSortIndex))
                                        .collectList()
                                        .map(lines -> new Detail(refund, lines)));
    }

    private Throwable duplicate(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("refund_request_conflict", 409);
        return failure;
    }

    public record Submission(String requestId, String token, RefundInput input) {}

    public record Detail(RefundEntity refund, List<RefundAllocationEntity> lines) {}

    public record Allocation(
            String sourceLineId,
            String paymentId,
            String sourceType,
            String receivableId,
            String fullName,
            String customerName,
            String subjectName,
            String originalAmount,
            String refundedAmount,
            String amount) {}

    public record Preview(
            String token,
            String businessDate,
            String total,
            String companyName,
            String methodText,
            String bankName,
            List<Allocation> allocations) {}
}
