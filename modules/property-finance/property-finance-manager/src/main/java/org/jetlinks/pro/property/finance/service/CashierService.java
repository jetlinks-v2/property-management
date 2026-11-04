package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.init;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.jetlinks.pro.property.finance.receivable.ReceivableView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** 柜台人工收款；在线支付和余额支付不接受人工成功标记，后续由各自可核验通道进入账本。 */
@Service
@RequiredArgsConstructor
public class CashierService {
    private static final AssetType SPACE = AssetType.of("space");
    private final FinanceScope scope;
    private final CashierConfiguration configuration;
    private final HouseArchiveSource archive;
    private final PaymentRules rules;
    private final PaymentLedger ledger;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<PaymentEntity, String> payments;
    private final ReactiveRepository<PaymentAllocationEntity, String> lines;

    public Mono<List<CashierConfiguration.CompanyOption>> options(String project) {
        return scope.project(project, CrudAssetPermission.save)
                .then(configuration.options(project));
    }

    /** 家庭代缴候选仅来自当前同房屋有效关系；多房屋合并收款使用独立缴款人信息。 */
    public Mono<List<PayerOption>> payerOptions(String project, List<String> ids) {
        return scope.project(project, CrudAssetPermission.save)
                .then(
                        Mono.defer(
                                () -> {
                                    if (ids == null
                                            || ids.isEmpty()
                                            || ids.size() > 100
                                            || ids.stream()
                                                    .anyMatch(
                                                            id ->
                                                                    id == null
                                                                            || id.isBlank()
                                                                            || id.length() > 64)
                                            || new HashSet<>(ids).size() != ids.size())
                                        return Mono.error(error("cashier_invalid", 400));
                                    var input = new CashierInput();
                                    input.setItems(
                                            ids.stream()
                                                    .map(
                                                            id ->
                                                                    new CashierInput.Item(
                                                                            id, 0L, BigDecimal.ONE))
                                                    .toList());
                                    return rows(project, input, false)
                                            .flatMap(
                                                    rows -> {
                                                        var space = rows.get(0).getSpaceId();
                                                        if (rows.stream()
                                                                .anyMatch(
                                                                        row ->
                                                                                !space.equals(
                                                                                        row
                                                                                                .getSpaceId())))
                                                            return Mono.just(
                                                                    List.<PayerOption>of());
                                                        return archive.context(
                                                                        project, space, false)
                                                                .map(
                                                                        context ->
                                                                                context
                                                                                        .customers()
                                                                                        .stream()
                                                                                        .filter(
                                                                                                customer ->
                                                                                                        rows
                                                                                                                .stream()
                                                                                                                .allMatch(
                                                                                                                        row ->
                                                                                                                                familyMatches(
                                                                                                                                        row,
                                                                                                                                        customer
                                                                                                                                                .customerId(),
                                                                                                                                        customer
                                                                                                                                                .familyId())))
                                                                                        .map(
                                                                                                customer ->
                                                                                                        new PayerOption(
                                                                                                                customer
                                                                                                                        .relationshipId(),
                                                                                                                customer
                                                                                                                        .name(),
                                                                                                                customer
                                                                                                                        .familyName()))
                                                                                        .toList());
                                                    });
                                }));
    }

    public Mono<Preview> preview(String project, CashierInput input, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(
                        Mono.defer(
                                () -> {
                                    PaymentRules.validate(input, date);
                                    return payer(project, input, false)
                                            .flatMap(
                                                    payer ->
                                                            configuration
                                                                    .resolve(project, input, false)
                                                                    .flatMap(
                                                                            config ->
                                                                                    rows(
                                                                                                    project,
                                                                                                    input,
                                                                                                    false)
                                                                                            .map(
                                                                                                    rows ->
                                                                                                            preview(
                                                                                                                    rules
                                                                                                                            .prepare(
                                                                                                                                    rows,
                                                                                                                                    input,
                                                                                                                                    date),
                                                                                                                    config,
                                                                                                                    payer))));
                                }));
    }

    @Transactional
    public Mono<Detail> collect(String project, Submission request, LocalDate date) {
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
                                                return Mono.error(error("cashier_invalid", 400));
                                            var input = request.input();
                                            PaymentRules.validate(input, date);
                                            var id =
                                                    UUID.nameUUIDFromBytes(
                                                                    (project.length()
                                                                                    + ":"
                                                                                    + project
                                                                                    + request
                                                                                            .requestId())
                                                                            .getBytes(
                                                                                    StandardCharsets
                                                                                            .UTF_8))
                                                            .toString();
                                            var fingerprint = PaymentRules.fingerprint(input);
                                            return existing(project, id, fingerprint)
                                                    .switchIfEmpty(
                                                            Mono.defer(
                                                                    () ->
                                                                            // 家庭关系命令先锁基础项目，再锁公司和账单，避免与档案/拆分的锁顺序相反。
                                                                            payer(
                                                                                            project,
                                                                                            input,
                                                                                            true)
                                                                                    .flatMap(
                                                                                            payer ->
                                                                                                    configuration
                                                                                                            .resolve(
                                                                                                                    project,
                                                                                                                    input,
                                                                                                                    true)
                                                                                                            .flatMap(
                                                                                                                    config ->
                                                                                                                            rows(
                                                                                                                                            project,
                                                                                                                                            input,
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
                                                                                                                                                                                                    rows,
                                                                                                                                                                                                    config,
                                                                                                                                                                                                    payer,
                                                                                                                                                                                                    date,
                                                                                                                                                                                                    auth))))))));
                                        }))
                .onErrorMap(this::duplicate)
                .as(MonoTracer.create("/property/finance/cashier/collect"));
    }

    private Mono<Detail> write(
            String project,
            String id,
            String fingerprint,
            Submission request,
            List<ReceivableEntity> rows,
            CashierConfiguration.Resolved config,
            Payer payer,
            LocalDate date,
            Authentication auth) {
        var input = request.input();
        var result = rules.prepare(rows, input, date);
        var preview = preview(result, config, payer);
        if (!Objects.equals(preview.token(), request.token()))
            return Mono.error(error("cashier_preview_expired", 409));
        if (input.getPayerRelationshipId() != null && !input.getPayerRelationshipId().isBlank())
            assertFamily(rows, payer);
        var payment = new PaymentEntity();
        payment.setId(id);
        init(payment, auth);
        payment.setProjectId(project);
        payment.setCompanyId(config.company().getId());
        payment.setCompanyName(config.company().getCompanyName());
        payment.setRequestFingerprint(fingerprint);
        payment.setStatus("SUCCEEDED");
        payment.setPayerName(payer.name());
        payment.setPayerRelationshipId(payer.relationshipId());
        payment.setPayerUserId(payer.userId());
        payment.setPaidAt(input.getPaidAt());
        payment.setAmount(new BigDecimal(result.total()));
        payment.setRemark(input.getRemark());
        payment.setAttachmentName(input.getAttachmentName());
        payment.setAttachmentFileId(input.getAttachmentFileId());
        return ledger.persist(payment, rows, result, config, auth)
                .map(lines -> new Detail(payment, lines));
    }

    private Mono<List<ReceivableEntity>> rows(String project, CashierInput input, boolean lock) {
        return Flux.fromIterable(
                        input.getItems().stream()
                                .sorted(Comparator.comparing(CashierInput.Item::id))
                                .toList())
                .concatMap(
                        item -> {
                            var query = new QueryParamEntity();
                            query.setForUpdate(lock);
                            return bills.createQuery()
                                    .setParam(query)
                                    .where("id", item.id())
                                    .and("projectId", project)
                                    .and("deleted", false)
                                    .fetchOne()
                                    .switchIfEmpty(Mono.error(error("receivable_not_found", 404)))
                                    .flatMap(
                                            row ->
                                                    AssetsHolder.assertPermission(
                                                                    SPACE,
                                                                    row.getSpaceId(),
                                                                    CrudAssetPermission.save)
                                                            .thenReturn(row));
                        })
                .collectList();
    }

    private Mono<Detail> existing(String project, String id, String fingerprint) {
        return payments.createQuery()
                .where("id", id)
                .and("projectId", project)
                .fetchOne()
                .flatMap(
                        payment -> {
                            if (!Objects.equals(fingerprint, payment.getRequestFingerprint()))
                                return Mono.error(error("cashier_request_conflict", 409));
                            return detail(project, payment.getId());
                        });
    }

    public Mono<Detail> detail(String project, String id) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        payments.createQuery()
                                .where("id", id)
                                .and("projectId", project)
                                .and("deleted", false)
                                .fetchOne()
                                .switchIfEmpty(Mono.error(error("payment_not_found", 404))))
                .flatMap(
                        payment ->
                                lines.createQuery()
                                        .where("paymentId", id)
                                        .and("projectId", project)
                                        .and("deleted", false)
                                        .fetch()
                                        .concatMap(
                                                line ->
                                                        AssetsHolder.assertPermission(
                                                                        SPACE,
                                                                        line.getSpaceId(),
                                                                        CrudAssetPermission.read)
                                                                .thenReturn(line))
                                        .sort(
                                                Comparator.comparing(
                                                        PaymentAllocationEntity::getSortIndex))
                                        .collectList()
                                        .map(rows -> new Detail(payment, rows)));
    }

    private Mono<Payer> payer(String project, CashierInput input, boolean lock) {
        if (input.getPayerRelationshipId() == null || input.getPayerRelationshipId().isBlank())
            return Mono.just(
                    new Payer(null, null, input.getPayerName().trim(), null, null, null, null));
        return rows(project, input, false)
                .flatMap(
                        rows -> {
                            var space = rows.get(0).getSpaceId();
                            if (rows.stream().anyMatch(row -> !space.equals(row.getSpaceId())))
                                return Mono.error(error("cashier_payer_invalid", 400));
                            return archive.context(project, space, lock)
                                    .map(
                                            context -> {
                                                var customer =
                                                        context.customers().stream()
                                                                .filter(
                                                                        item ->
                                                                                input.getPayerRelationshipId()
                                                                                        .equals(
                                                                                                item
                                                                                                        .relationshipId()))
                                                                .findFirst()
                                                                .orElseThrow(
                                                                        () ->
                                                                                error(
                                                                                        "cashier_payer_invalid",
                                                                                        409));
                                                var payer =
                                                        new Payer(
                                                                customer.relationshipId(),
                                                                customer.userId(),
                                                                customer.name(),
                                                                customer.familyId(),
                                                                customer.customerId(),
                                                                customer.relationshipVersion(),
                                                                customer.customerVersion());
                                                assertFamily(rows, payer);
                                                return payer;
                                            });
                        });
    }

    private static void assertFamily(List<ReceivableEntity> rows, Payer payer) {
        for (var row : rows) {
            if (!familyMatches(row, payer.customerId(), payer.familyId()))
                throw error("cashier_payer_invalid", 409);
        }
    }

    private static boolean familyMatches(ReceivableEntity row, String customerId, String familyId) {
        var customer =
                com.alibaba
                        .fastjson
                        .JSON
                        .parseObject(row.getSnapshotJson())
                        .getJSONObject("customer");
        return customerId.equals(row.getCustomerId())
                || familyId != null
                        && customer != null
                        && familyId.equals(customer.getString("familyId"));
    }

    private Preview preview(
            PaymentRules.Result result, CashierConfiguration.Resolved config, Payer payer) {
        var token = PaymentRules.digest(result.token() + ":" + config.identity() + ":" + payer);
        return new Preview(
                token,
                result.businessDate(),
                result.total(),
                config.company().getCompanyName(),
                payer.name(),
                result.rows().stream().map(row -> ReceivableView.of(row, false)).toList(),
                result.allocations().stream()
                        .map(
                                line ->
                                        new Allocation(
                                                line.receivableId(),
                                                line.tenderIndex(),
                                                line.method(),
                                                config.funding().get(line.tenderIndex()).text(),
                                                config.funding().get(line.tenderIndex()).bankName(),
                                                line.amount().toPlainString()))
                        .toList());
    }

    private Throwable duplicate(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("cashier_request_conflict", 409);
        return failure;
    }

    private record Payer(
            String relationshipId,
            String userId,
            String name,
            String familyId,
            String customerId,
            Long relationshipVersion,
            Long customerVersion) {}

    public record Submission(String requestId, String token, CashierInput input) {}

    public record Allocation(
            String receivableId,
            int tenderIndex,
            String method,
            String methodText,
            String bankName,
            String amount) {}

    public record Preview(
            String token,
            String businessDate,
            String total,
            String companyName,
            String payerName,
            List<ReceivableView> rows,
            List<Allocation> allocations) {}

    public record Detail(PaymentEntity payment, List<PaymentAllocationEntity> lines) {}

    public record PayerOption(String relationshipId, String name, String familyName) {}
}
