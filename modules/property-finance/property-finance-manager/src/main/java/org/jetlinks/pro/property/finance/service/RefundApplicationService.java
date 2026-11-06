package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** 申请、占用、按钮审核与真实资金执行共用项目事务锁，不引入工作流。 */
@Service
@RequiredArgsConstructor
public class RefundApplicationService {
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final FinanceScope scope;
    private final FinancialPeriodGate gate;
    private final RefundService payments;
    private final RefundSourceService paymentSources;
    private final FundsService funds;
    private final FundSourceService fundSources;
    private final CashierConfiguration configuration;
    private final ReactiveRepository<RefundApplicationEntity, String> applications;
    private final ReactiveRepository<RefundApplicationLineEntity, String> lines;
    private final ReactiveRepository<RefundApplicationOperationEntity, String> logs;
    private final ReactiveRepository<PaymentAllocationEntity, String> paymentLines;
    private final ReactiveRepository<FundAccountEntity, String> accounts;

    @Transactional
    public Mono<RefundApplicationEntity> submitPayment(
            String project, RefundService.Submission request, LocalDate date) {
        return authorized(project)
                .flatMap(
                        auth -> {
                            if (request == null) return Mono.error(error("refund_invalid", 400));
                            RefundRules.validate(request.input(), date);
                            var id = identity(project, request.requestId());
                            var fingerprint = RefundRules.fingerprint(request.input());
                            return gate.lock(project)
                                    .then(existing(project, id, fingerprint))
                                    .switchIfEmpty(
                                            Mono.defer(
                                                    () ->
                                                            configuration
                                                                    .resolve(
                                                                            project,
                                                                            request.input()
                                                                                    .fundingInput(),
                                                                            true)
                                                                    .then(
                                                                            paymentSources.load(
                                                                                    project,
                                                                                    request.input(),
                                                                                    true))
                                                                    .flatMap(
                                                                            source ->
                                                                                    payments.preview(
                                                                                                    project,
                                                                                                    request
                                                                                                            .input(),
                                                                                                    date)
                                                                                            .flatMap(
                                                                                                    preview -> {
                                                                                                        if (!Objects
                                                                                                                .equals(
                                                                                                                        preview
                                                                                                                                .token(),
                                                                                                                        request
                                                                                                                                .token()))
                                                                                                            return Mono
                                                                                                                    .error(
                                                                                                                            error(
                                                                                                                                    "refund_preview_expired",
                                                                                                                                    409));
                                                                                                        var
                                                                                                                row =
                                                                                                                        application(
                                                                                                                                project,
                                                                                                                                id,
                                                                                                                                "PAYMENT",
                                                                                                                                fingerprint,
                                                                                                                                mapper.valueToTree(
                                                                                                                                                request
                                                                                                                                                        .input())
                                                                                                                                        .toString(),
                                                                                                                                request.input()
                                                                                                                                        .getCompanyId(),
                                                                                                                                preview
                                                                                                                                        .companyName(),
                                                                                                                                request.input()
                                                                                                                                        .getReceiverName(),
                                                                                                                                request.input()
                                                                                                                                        .getRefundedAt(),
                                                                                                                                preview
                                                                                                                                        .methodText(),
                                                                                                                                request.input()
                                                                                                                                        .getReason(),
                                                                                                                                request.input()
                                                                                                                                        .getAmount(),
                                                                                                                                auth);
                                                                                                        var
                                                                                                                amounts =
                                                                                                                        RefundRules
                                                                                                                                .allocate(
                                                                                                                                        request
                                                                                                                                                .input(),
                                                                                                                                        source
                                                                                                                                                .lines(),
                                                                                                                                        date);
                                                                                                        var
                                                                                                                sourceMap =
                                                                                                                        new HashMap<
                                                                                                                                String,
                                                                                                                                PaymentAllocationEntity>();
                                                                                                        source.lines()
                                                                                                                .forEach(
                                                                                                                        line ->
                                                                                                                                sourceMap
                                                                                                                                        .put(
                                                                                                                                                line
                                                                                                                                                        .getId(),
                                                                                                                                                line));
                                                                                                        return applications
                                                                                                                .insert(
                                                                                                                        row)
                                                                                                                .thenMany(
                                                                                                                        Flux
                                                                                                                                .fromIterable(
                                                                                                                                        amounts)
                                                                                                                                .concatMap(
                                                                                                                                        share -> {
                                                                                                                                            var
                                                                                                                                                    original =
                                                                                                                                                            sourceMap
                                                                                                                                                                    .get(
                                                                                                                                                                            share
                                                                                                                                                                                    .sourceId());
                                                                                                                                            var
                                                                                                                                                    line =
                                                                                                                                                            line(
                                                                                                                                                                    row,
                                                                                                                                                                    original
                                                                                                                                                                            .getId(),
                                                                                                                                                                    original
                                                                                                                                                                            .getSpaceId(),
                                                                                                                                                                    original
                                                                                                                                                                            .getFullName(),
                                                                                                                                                                    original
                                                                                                                                                                            .getCustomerName(),
                                                                                                                                                                    original
                                                                                                                                                                            .getSubjectName(),
                                                                                                                                                                    share
                                                                                                                                                                            .amount(),
                                                                                                                                                                    auth);
                                                                                                                                            line
                                                                                                                                                    .setCalcStartDate(
                                                                                                                                                            original
                                                                                                                                                                    .getCalcStartDate());
                                                                                                                                            line
                                                                                                                                                    .setCalcEndDate(
                                                                                                                                                            original
                                                                                                                                                                    .getCalcEndDate());
                                                                                                                                            return reservePayment(
                                                                                                                                                            original,
                                                                                                                                                            share
                                                                                                                                                                    .amount(),
                                                                                                                                                            auth)
                                                                                                                                                    .then(
                                                                                                                                                            lines
                                                                                                                                                                    .insert(
                                                                                                                                                                            line));
                                                                                                                                        }))
                                                                                                                .then(
                                                                                                                        log(
                                                                                                                                row,
                                                                                                                                "SUBMIT",
                                                                                                                                row
                                                                                                                                        .getReason(),
                                                                                                                                auth))
                                                                                                                .thenReturn(
                                                                                                                        row);
                                                                                                    }))));
                        })
                .as(MonoTracer.create("property.finance.refund.submit"));
    }

    @Transactional
    public Mono<RefundApplicationEntity> submitFund(
            String project, FundsService.Submission request, LocalDate date) {
        return authorized(project)
                .flatMap(
                        auth -> {
                            if (request == null
                                    || request.input() == null
                                    || !"REFUND".equals(request.input().getAction()))
                                return Mono.error(error("fund_invalid", 400));
                            FundRules.validate(request.input(), date);
                            var id = identity(project, request.requestId());
                            var fingerprint = FundRules.fingerprint(request.input());
                            return gate.lock(project)
                                    .then(existing(project, id, fingerprint))
                                    .switchIfEmpty(
                                            Mono.defer(
                                                    () ->
                                                            configuration
                                                                    .balanceCompany(
                                                                            project,
                                                                            request.input()
                                                                                    .getCompanyId(),
                                                                            true)
                                                                    .then(
                                                                            fundSources.account(
                                                                                    project,
                                                                                    request.input()
                                                                                            .getAccountId(),
                                                                                    true))
                                                                    .flatMap(
                                                                            account ->
                                                                                    funds.preview(
                                                                                                    project,
                                                                                                    request
                                                                                                            .input(),
                                                                                                    date)
                                                                                            .flatMap(
                                                                                                    preview -> {
                                                                                                        if (!Objects
                                                                                                                .equals(
                                                                                                                        preview
                                                                                                                                .token(),
                                                                                                                        request
                                                                                                                                .token()))
                                                                                                            return Mono
                                                                                                                    .error(
                                                                                                                            error(
                                                                                                                                    "fund_preview_expired",
                                                                                                                                    409));
                                                                                                        var
                                                                                                                row =
                                                                                                                        application(
                                                                                                                                project,
                                                                                                                                id,
                                                                                                                                account
                                                                                                                                        .getType(),
                                                                                                                                fingerprint,
                                                                                                                                mapper.valueToTree(
                                                                                                                                                request
                                                                                                                                                        .input())
                                                                                                                                        .toString(),
                                                                                                                                account
                                                                                                                                        .getCompanyId(),
                                                                                                                                preview
                                                                                                                                        .companyName(),
                                                                                                                                request.input()
                                                                                                                                        .getPayerName(),
                                                                                                                                request.input()
                                                                                                                                        .getOccurredAt(),
                                                                                                                                preview
                                                                                                                                        .methodText(),
                                                                                                                                request.input()
                                                                                                                                        .getReason(),
                                                                                                                                request.input()
                                                                                                                                        .getAmount(),
                                                                                                                                auth);
                                                                                                        var
                                                                                                                line =
                                                                                                                        line(
                                                                                                                                row,
                                                                                                                                account
                                                                                                                                        .getId(),
                                                                                                                                account
                                                                                                                                        .getSpaceId(),
                                                                                                                                account
                                                                                                                                        .getFullName(),
                                                                                                                                account
                                                                                                                                        .getCustomerName(),
                                                                                                                                account
                                                                                                                                        .getSubjectName(),
                                                                                                                                row
                                                                                                                                        .getAmount(),
                                                                                                                                auth);
                                                                                                        return applications
                                                                                                                .insert(
                                                                                                                        row)
                                                                                                                .then(
                                                                                                                        reserveFund(
                                                                                                                                account,
                                                                                                                                row
                                                                                                                                        .getAmount(),
                                                                                                                                auth))
                                                                                                                .then(
                                                                                                                        lines
                                                                                                                                .insert(
                                                                                                                                        line))
                                                                                                                .then(
                                                                                                                        log(
                                                                                                                                row,
                                                                                                                                "SUBMIT",
                                                                                                                                row
                                                                                                                                        .getReason(),
                                                                                                                                auth))
                                                                                                                .thenReturn(
                                                                                                                        row);
                                                                                                    }))));
                        })
                .as(MonoTracer.create("property.finance.refund.fund.submit"));
    }

    /** 项目锁先于来源锁。重试仅重放同一动作结果，不再次写退款事实。 */
    @Transactional
    public Mono<RefundApplicationEntity> act(
            String project, String id, String action, Action input, LocalDate date) {
        return authorized(project)
                .flatMap(
                        auth -> {
                            validateAction(action, input);
                            return gate.lock(project)
                                    .then(detail(project, id))
                                    .flatMap(
                                            detail -> {
                                                var row = detail.application();
                                                if (Objects.equals(
                                                                row.getActionRequestId(),
                                                                input.requestId())
                                                        && (("APPROVE".equals(action)
                                                                        && "SUCCEEDED"
                                                                                .equals(
                                                                                        row
                                                                                                .getStatus()))
                                                                || ("REJECT".equals(action)
                                                                        && "REJECTED"
                                                                                .equals(
                                                                                        row
                                                                                                .getStatus())))) {
                                                    if (!Objects.equals(
                                                            row.getReviewReason(),
                                                            input.reason().trim()))
                                                        return Mono.error(
                                                                error(
                                                                        "refund_request_conflict",
                                                                        409));
                                                    return Mono.just(row);
                                                }
                                                if (!"PENDING".equals(row.getStatus())
                                                        || !Objects.equals(
                                                                row.getVersion(), input.version()))
                                                    return Mono.error(
                                                            error("version_conflict", 409));
                                                return Flux.fromIterable(detail.lines())
                                                        .concatMap(
                                                                line ->
                                                                        AssetsHolder
                                                                                .assertPermission(
                                                                                        AssetType
                                                                                                .of(
                                                                                                        "space"),
                                                                                        line
                                                                                                .getSpaceId(),
                                                                                        CrudAssetPermission
                                                                                                .save))
                                                        .then()
                                                        .then(release(row, detail.lines(), auth))
                                                        .then(
                                                                "APPROVE".equals(action)
                                                                        ? execute(row, date)
                                                                        : Mono.just(""))
                                                        .flatMap(
                                                                result ->
                                                                        finish(
                                                                                row, action, input,
                                                                                result, auth));
                                            });
                        })
                .as(MonoTracer.create("property.finance.refund.review"));
    }

    private Mono<String> execute(RefundApplicationEntity row, LocalDate date) {
        if ("PAYMENT".equals(row.getSourceType())) {
            // 使用平台 Jackson 映射器保存并还原含 record 的公开命令快照。
            var input =
                    mapper.convertValue(JSON.parseObject(row.getInputJson()), RefundInput.class);
            return Flux.fromIterable(input.getItems())
                    .concatMap(
                            item ->
                                    paymentLines
                                            .findById(item.id())
                                            .map(
                                                    line ->
                                                            new RefundInput.Item(
                                                                    line.getId(),
                                                                    line.getVersion())))
                    .collectList()
                    .flatMap(
                            items -> {
                                input.setItems(items);
                                return payments.preview(row.getProjectId(), input, date)
                                        .flatMap(
                                                preview ->
                                                        payments.confirm(
                                                                row.getProjectId(),
                                                                new RefundService.Submission(
                                                                        row.getId(),
                                                                        preview.token(),
                                                                        input),
                                                                date));
                            })
                    .map(done -> done.refund().getId());
        }
        var input = mapper.convertValue(JSON.parseObject(row.getInputJson()), FundsInput.class);
        return fundSources
                .account(row.getProjectId(), input.getAccountId(), true)
                .flatMap(
                        account -> {
                            input.setVersion(account.getVersion());
                            return funds.preview(row.getProjectId(), input, date)
                                    .flatMap(
                                            preview ->
                                                    funds.execute(
                                                            row.getProjectId(),
                                                            new FundsService.Submission(
                                                                    row.getId(),
                                                                    preview.token(),
                                                                    input),
                                                            date));
                        })
                .map(done -> done.entry().getId());
    }

    private Mono<Void> release(
            RefundApplicationEntity row,
            List<RefundApplicationLineEntity> items,
            Authentication auth) {
        return Flux.fromIterable(
                        items.stream()
                                .sorted(
                                        Comparator.comparing(
                                                RefundApplicationLineEntity::getSourceId))
                                .toList())
                .concatMap(
                        line ->
                                "PAYMENT".equals(row.getSourceType())
                                        ? paymentLines
                                                .findById(line.getSourceId())
                                                .flatMap(
                                                        source ->
                                                                reservePayment(
                                                                        source,
                                                                        line.getAmount().negate(),
                                                                        auth))
                                        : fundSources
                                                .account(
                                                        row.getProjectId(),
                                                        line.getSourceId(),
                                                        true)
                                                .flatMap(
                                                        account ->
                                                                reserveFund(
                                                                        account,
                                                                        line.getAmount().negate(),
                                                                        auth)))
                .then();
    }

    private Mono<Void> reservePayment(
            PaymentAllocationEntity source, BigDecimal delta, Authentication auth) {
        var amount = source.getRefundReservedAmount().add(delta);
        if (amount.signum() < 0
                || amount.compareTo(source.getAmount().subtract(source.getRefundedAmount())) > 0)
            return Mono.error(error("refund_exceeds_balance", 409));
        return audit(paymentLines.createUpdate(), auth, source.getVersion())
                .set("refundReservedAmount", amount)
                .where("id", source.getId())
                .and("projectId", source.getProjectId())
                .and("version", source.getVersion())
                .and("deleted", false)
                .execute()
                .flatMap(SubjectService::changed)
                .then();
    }

    private Mono<Void> reserveFund(
            FundAccountEntity account, BigDecimal delta, Authentication auth) {
        var amount = account.getRefundReservedAmount().add(delta);
        if (amount.signum() < 0 || amount.compareTo(account.getBalance()) > 0)
            return Mono.error(error("fund_insufficient", 409));
        return audit(accounts.createUpdate(), auth, account.getVersion())
                .set("refundReservedAmount", amount)
                .where("id", account.getId())
                .and("projectId", account.getProjectId())
                .and("version", account.getVersion())
                .and("deleted", false)
                .execute()
                .flatMap(SubjectService::changed)
                .then();
    }

    private Mono<RefundApplicationEntity> finish(
            RefundApplicationEntity row,
            String action,
            Action input,
            String result,
            Authentication auth) {
        var status = "APPROVE".equals(action) ? "SUCCEEDED" : "REJECTED";
        return audit(applications.createUpdate(), auth, row.getVersion())
                .set("status", status)
                .set("resultId", result)
                .set("reviewedBy", auth.getUser().getId())
                .set("reviewerName", auth.getUser().getUsername())
                .set("reviewedAt", System.currentTimeMillis())
                .set("reviewReason", input.reason().trim())
                .set("actionRequestId", input.requestId())
                .where("id", row.getId())
                .and("projectId", row.getProjectId())
                .and("status", "PENDING")
                .and("version", row.getVersion())
                .execute()
                .flatMap(SubjectService::changed)
                .then(
                        lines.createUpdate()
                                .set("status", status)
                                .where("projectId", row.getProjectId())
                                .and("applicationId", row.getId())
                                .execute())
                .then(applications.findById(row.getId()))
                .flatMap(saved -> log(saved, action, input.reason(), auth).thenReturn(saved));
    }

    /** 修改仅接受待审核申请；先释放旧占用、完整重校验，再重建占用，失败由事务恢复原状。 */
    @Transactional
    public Mono<RefundApplicationEntity> edit(
            String project, String id, Edit input, LocalDate date) {
        return authorized(project)
                .flatMap(
                        auth -> {
                            if (input == null || input.version() == null)
                                return Mono.error(error("refund_invalid", 400));
                            return gate.lock(project)
                                    .then(detail(project, id))
                                    .flatMap(
                                            detail -> {
                                                var row = detail.application();
                                                if (!"PENDING".equals(row.getStatus())
                                                        || !Objects.equals(
                                                                row.getVersion(), input.version()))
                                                    return Mono.error(
                                                            error("version_conflict", 409));
                                                return Flux.fromIterable(detail.lines())
                                                        .concatMap(
                                                                line ->
                                                                        AssetsHolder
                                                                                .assertPermission(
                                                                                        AssetType
                                                                                                .of(
                                                                                                        "space"),
                                                                                        line
                                                                                                .getSpaceId(),
                                                                                        CrudAssetPermission
                                                                                                .save))
                                                        .then()
                                                        .then(release(row, detail.lines(), auth))
                                                        .then(
                                                                replaceReservation(
                                                                        row, input, date, auth))
                                                        .flatMap(
                                                                json ->
                                                                        audit(
                                                                                        applications
                                                                                                .createUpdate(),
                                                                                        auth,
                                                                                        row
                                                                                                .getVersion())
                                                                                .set(
                                                                                        "inputJson",
                                                                                        json)
                                                                                .set(
                                                                                        "amount",
                                                                                        input
                                                                                                .amount())
                                                                                .set(
                                                                                        "receiverName",
                                                                                        input.receiverName()
                                                                                                .trim())
                                                                                .set(
                                                                                        "refundedAt",
                                                                                        input
                                                                                                .refundedAt())
                                                                                .set(
                                                                                        "reason",
                                                                                        input.reason()
                                                                                                .trim())
                                                                                .where("id", id)
                                                                                .and(
                                                                                        "projectId",
                                                                                        project)
                                                                                .and(
                                                                                        "status",
                                                                                        "PENDING")
                                                                                .and(
                                                                                        "version",
                                                                                        input
                                                                                                .version())
                                                                                .execute()
                                                                                .flatMap(
                                                                                        SubjectService
                                                                                                ::changed))
                                                        .then(applications.findById(id))
                                                        .flatMap(
                                                                saved ->
                                                                        log(
                                                                                        saved,
                                                                                        "EDIT",
                                                                                        input
                                                                                                .reason(),
                                                                                        auth)
                                                                                .thenReturn(saved));
                                            });
                        })
                .as(MonoTracer.create("property.finance.refund.edit"));
    }

    private Mono<String> replaceReservation(
            RefundApplicationEntity row, Edit edit, LocalDate date, Authentication auth) {
        String project = row.getProjectId();
        if ("PAYMENT".equals(row.getSourceType())) {
            var input =
                    mapper.convertValue(JSON.parseObject(row.getInputJson()), RefundInput.class);
            input.setAmount(edit.amount());
            input.setReceiverName(edit.receiverName());
            input.setRefundedAt(edit.refundedAt());
            input.setReason(edit.reason());
            return Flux.fromIterable(input.getItems())
                    .concatMap(
                            item ->
                                    paymentLines
                                            .findById(item.id())
                                            .switchIfEmpty(
                                                    Mono.error(error("refund_not_found", 404)))
                                            .map(
                                                    source ->
                                                            new RefundInput.Item(
                                                                    source.getId(),
                                                                    source.getVersion())))
                    .collectList()
                    .flatMap(
                            items -> {
                                input.setItems(items);
                                return payments.preview(project, input, date)
                                        .flatMap(
                                                preview ->
                                                        paymentSources.load(project, input, true))
                                        .flatMap(
                                                source -> {
                                                    var shares =
                                                            RefundRules.allocate(
                                                                    input, source.lines(), date);
                                                    var sourceMap =
                                                            new HashMap<
                                                                    String,
                                                                    PaymentAllocationEntity>();
                                                    source.lines()
                                                            .forEach(
                                                                    line ->
                                                                            sourceMap.put(
                                                                                    line.getId(),
                                                                                    line));
                                                    return lines.createUpdate()
                                                            .set("deleted", true)
                                                            .where("projectId", project)
                                                            .and("applicationId", row.getId())
                                                            .and("deleted", false)
                                                            .execute()
                                                            .thenMany(
                                                                    Flux.fromIterable(shares)
                                                                            .concatMap(
                                                                                    share -> {
                                                                                        var
                                                                                                original =
                                                                                                        sourceMap
                                                                                                                .get(
                                                                                                                        share
                                                                                                                                .sourceId());
                                                                                        var line =
                                                                                                line(
                                                                                                        row,
                                                                                                        original
                                                                                                                .getId(),
                                                                                                        original
                                                                                                                .getSpaceId(),
                                                                                                        original
                                                                                                                .getFullName(),
                                                                                                        original
                                                                                                                .getCustomerName(),
                                                                                                        original
                                                                                                                .getSubjectName(),
                                                                                                        share
                                                                                                                .amount(),
                                                                                                        auth);
                                                                                        line
                                                                                                .setCalcStartDate(
                                                                                                        original
                                                                                                                .getCalcStartDate());
                                                                                        line
                                                                                                .setCalcEndDate(
                                                                                                        original
                                                                                                                .getCalcEndDate());
                                                                                        return reservePayment(
                                                                                                        original,
                                                                                                        share
                                                                                                                .amount(),
                                                                                                        auth)
                                                                                                .then(
                                                                                                        lines
                                                                                                                .insert(
                                                                                                                        line));
                                                                                    }))
                                                            .then(
                                                                    Mono.fromSupplier(
                                                                            () ->
                                                                                    mapper.valueToTree(
                                                                                                    input)
                                                                                            .toString()));
                                                });
                            });
        }
        var input = mapper.convertValue(JSON.parseObject(row.getInputJson()), FundsInput.class);
        input.setAmount(edit.amount());
        input.setPayerName(edit.receiverName());
        input.setOccurredAt(edit.refundedAt());
        input.setReason(edit.reason());
        return fundSources
                .account(project, input.getAccountId(), true)
                .flatMap(
                        account -> {
                            input.setVersion(account.getVersion());
                            return funds.preview(project, input, date)
                                    .then(reserveFund(account, edit.amount(), auth))
                                    .then(
                                            lines.createUpdate()
                                                    .set("amount", edit.amount())
                                                    .where("projectId", project)
                                                    .and("applicationId", row.getId())
                                                    .and("deleted", false)
                                                    .execute())
                                    .then(
                                            Mono.fromSupplier(
                                                    () -> mapper.valueToTree(input).toString()));
                        });
    }

    public Mono<List<RefundApplicationOperationEntity>> logs(String project, String id) {
        var query = new QueryParamEntity();
        query.toQuery()
                .and("projectId", project)
                .and("applicationId", id)
                .and("deleted", false)
                .orderByAsc("createTime")
                .orderByAsc("id");
        return detail(project, id)
                .thenMany(logs.createQuery().setParam(query).fetch())
                .collectList();
    }

    private Mono<Void> log(
            RefundApplicationEntity row, String action, String reason, Authentication auth) {
        var log = new RefundApplicationOperationEntity();
        log.setId(UUID.randomUUID().toString());
        init(log, auth);
        log.setProjectId(row.getProjectId());
        log.setApplicationId(row.getId());
        log.setAction(action);
        log.setReason(reason);
        log.setSnapshotJson(
                JSON.toJSONString(
                        Map.of(
                                "amount",
                                row.getAmount(),
                                "receiverName",
                                row.getReceiverName(),
                                "refundedAt",
                                row.getRefundedAt(),
                                "status",
                                row.getStatus(),
                                "methodText",
                                row.getMethodText())));
        return logs.insert(log).then();
    }

    public Mono<Detail> detail(String project, String id) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        applications
                                .createQuery()
                                .where("id", id)
                                .and("projectId", project)
                                .and("deleted", false)
                                .fetchOne()
                                .switchIfEmpty(Mono.error(error("refund_not_found", 404))))
                .flatMap(
                        row ->
                                lines.createQuery()
                                        .where("applicationId", id)
                                        .and("projectId", project)
                                        .and("deleted", false)
                                        .fetch()
                                        .concatMap(
                                                line ->
                                                        AssetsHolder.assertPermission(
                                                                        AssetType.of("space"),
                                                                        line.getSpaceId(),
                                                                        CrudAssetPermission.read)
                                                                .thenReturn(line))
                                        .collectList()
                                        .map(items -> new Detail(row, items)));
    }

    /** 列表按授权房屋明细分页，不向部分有权用户暴露整张申请的总金额。 */
    public Mono<PagerResult<View>> query(String project, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        Mono.defer(
                                () -> {
                                    var query = SubjectService.page(input);
                                    query.toNestQuery(
                                            n -> n.and("projectId", project).and("deleted", false));
                                    if (query.getSorts().isEmpty())
                                        query.toQuery().orderByDesc("createTime").orderByDesc("id");
                                    return FinanceSpaceQuery.inject(
                                                    query, lines, CrudAssetPermission.read)
                                            .flatMap(
                                                    secured ->
                                                            QueryHelper.transformPageResult(
                                                                    QueryHelper.queryPager(
                                                                            secured,
                                                                            lines::createQuery),
                                                                    rows ->
                                                                            Flux.fromIterable(rows)
                                                                                    .concatMap(
                                                                                            line ->
                                                                                                    applications
                                                                                                            .findById(
                                                                                                                    line
                                                                                                                            .getApplicationId())
                                                                                                            .map(
                                                                                                                    app ->
                                                                                                                            new View(
                                                                                                                                    line,
                                                                                                                                    app
                                                                                                                                            .getNumber(),
                                                                                                                                    app
                                                                                                                                            .getStatus(),
                                                                                                                                    app
                                                                                                                                            .getSourceType(),
                                                                                                                                    app
                                                                                                                                            .getReceiverName(),
                                                                                                                                    app
                                                                                                                                            .getMethodText(),
                                                                                                                                    app
                                                                                                                                            .getRefundedAt(),
                                                                                                                                    app
                                                                                                                                            .getModifierName(),
                                                                                                                                    app
                                                                                                                                            .getVersion())))
                                                                                    .collectList()));
                                }));
    }

    private Mono<Authentication> authorized(String project) {
        return scope.project(project, CrudAssetPermission.save).then(scope.authenticated());
    }

    private Mono<RefundApplicationEntity> existing(String project, String id, String fingerprint) {
        return applications
                .createQuery()
                .where("projectId", project)
                .and("id", id)
                .fetchOne()
                .flatMap(
                        row -> {
                            if (!Objects.equals(fingerprint, row.getFingerprint()))
                                return Mono.error(error("refund_request_conflict", 409));
                            return detail(project, id).map(Detail::application);
                        });
    }

    private String identity(String project, String request) {
        if (request == null || !request.matches("[A-Za-z0-9_-]{8,100}"))
            throw error("refund_invalid", 400);
        return UUID.nameUUIDFromBytes(
                        ("refund-application:" + project.length() + ":" + project + request)
                                .getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private void validateAction(String action, Action input) {
        if (!Set.of("APPROVE", "REJECT").contains(action)
                || input == null
                || input.version() == null
                || input.requestId() == null
                || !input.requestId().matches("[A-Za-z0-9_-]{8,100}")
                || input.reason() == null
                || input.reason().isBlank()
                || input.reason().length() > 1000) throw error("refund_invalid", 400);
    }

    private RefundApplicationEntity application(
            String project,
            String id,
            String type,
            String fingerprint,
            String json,
            String company,
            String companyName,
            String receiver,
            String date,
            String method,
            String reason,
            BigDecimal amount,
            Authentication auth) {
        var row = new RefundApplicationEntity();
        row.setId(id);
        init(row, auth);
        row.setProjectId(project);
        row.setNumber("TK" + id.replace("-", "").toUpperCase(Locale.ROOT));
        row.setSourceType(type);
        row.setStatus("PENDING");
        row.setFingerprint(fingerprint);
        row.setInputJson(json);
        row.setCompanyId(company);
        row.setCompanyName(companyName);
        row.setReceiverName(receiver);
        row.setRefundedAt(date);
        row.setMethodText(method);
        row.setReason(reason);
        row.setAmount(amount.setScale(2));
        return row;
    }

    private RefundApplicationLineEntity line(
            RefundApplicationEntity app,
            String source,
            String space,
            String fullName,
            String customer,
            String subject,
            BigDecimal amount,
            Authentication auth) {
        var row = new RefundApplicationLineEntity();
        row.setId(UUID.randomUUID().toString());
        init(row, auth);
        row.setProjectId(app.getProjectId());
        row.setApplicationId(app.getId());
        row.setSourceId(source);
        row.setSpaceId(space);
        row.setFullName(fullName);
        row.setCustomerName(customer);
        row.setSubjectName(subject);
        row.setAmount(amount);
        return row;
    }

    public record Action(String requestId, Long version, String reason) {}

    public record Edit(
            Long version,
            BigDecimal amount,
            String receiverName,
            String refundedAt,
            String reason) {}

    public record Detail(
            RefundApplicationEntity application, List<RefundApplicationLineEntity> lines) {}

    public record View(
            RefundApplicationLineEntity line,
            String number,
            String status,
            String sourceType,
            String receiverName,
            String methodText,
            String refundedAt,
            String operatorName,
            Long version) {}
}
