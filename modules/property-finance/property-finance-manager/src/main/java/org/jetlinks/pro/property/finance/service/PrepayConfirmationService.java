package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.activity.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.jetlinks.pro.property.finance.setting.SettingRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** 柜台预缴统一事务：来源再验证、逐期优惠、实收、参与资格和报价确认全部提交或全部回滚。 */
@Service
@RequiredArgsConstructor
public class PrepayConfirmationService {
    private final FinanceScope scope;
    private final FinancialPeriodGate periodGate;
    private final HouseArchiveSource archive;
    private final CashierConfiguration configuration;
    private final CashierService cashier;
    private final PrepayQuoteSource source;
    private final PrepayBillStore billStore;
    private final PrepayPostingRules rules;
    private final PaymentLedger ledger;
    private final ReactiveRepository<PrepayQuoteEntity, String> quotes;
    private final ReactiveRepository<SubjectEntity, String> subjects;
    private final ReactiveRepository<HouseSettingEntity, String> settings;
    private final ReactiveRepository<PrepayConsumptionEntity, String> consumption;

    @Transactional
    public Mono<Detail> confirm(
            String project, String id, PrepayConfirmationInput input, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            validate(input, date);
                                            String fingerprint =
                                                    PaymentRules.digest(
                                                            ActivityCodec.encode(input));
                                            return quote(project, id, auth, false)
                                                    .flatMap(
                                                            row ->
                                                                    periodGate.inOpenPeriod(
                                                                            project,
                                                                            LocalDateTime.parse(
                                                                                            input
                                                                                                    .paidAt())
                                                                                    .toLocalDate(),
                                                                            quote(
                                                                                            project,
                                                                                            id,
                                                                                            auth,
                                                                                            false)
                                                                                    .flatMap(
                                                                                            current ->
                                                                                                    resume(
                                                                                                            current,
                                                                                                            fingerprint)),
                                                                            () ->
                                                                                    prepare(
                                                                                            row,
                                                                                            input,
                                                                                            date,
                                                                                            fingerprint,
                                                                                            auth)));
                                        }))
                .onErrorMap(PrepayConfirmationService::duplicate)
                .as(MonoTracer.create("/property/finance/prepay/confirm"));
    }

    private Mono<Detail> prepare(
            PrepayQuoteEntity quote,
            PrepayConfirmationInput input,
            LocalDate date,
            String fingerprint,
            Authentication auth) {
        checkOpen(quote, input, date);
        var snapshot = ActivityCodec.decode(quote.getSnapshotJson(), PrepayQuoteSnapshot.class);
        var setting = JSON.parseObject(snapshot.settingJson(), HouseSettingEntity.class);
        if (!Objects.equals(
                input.companyId(), SettingRules.standard(setting.getSnapshotJson()).getCompanyId()))
            return Mono.error(error("cashier_configuration_invalid", 409));
        var funding = funding(input, snapshot);
        PaymentRules.validateFunding(funding, date);
        if (input.tenders().stream()
                        .map(CashierInput.Tender::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .compareTo(new BigDecimal(snapshot.payAmount()))
                != 0) return Mono.error(error("cashier_total_mismatch", 400));
        // 与普通柜台、资金结转共用锁序：基础→公司→科目/设置→活动→应收→报价/支付。
        return archive.context(quote.getProjectId(), quote.getSpaceId(), true)
                .then(configuration.resolve(quote.getProjectId(), funding, true))
                .flatMap(
                        config ->
                                lockReferences(setting)
                                        .then(
                                                quote(
                                                        quote.getProjectId(),
                                                        quote.getId(),
                                                        auth,
                                                        false))
                                        .flatMap(
                                                current ->
                                                        resume(current, fingerprint)
                                                                .switchIfEmpty(
                                                                        Mono.defer(
                                                                                () ->
                                                                                        revalidate(
                                                                                                current,
                                                                                                input,
                                                                                                funding,
                                                                                                snapshot,
                                                                                                config,
                                                                                                fingerprint,
                                                                                                date,
                                                                                                auth)))));
    }

    private Mono<Void> lockReferences(HouseSettingEntity setting) {
        var subjectQuery = new QueryParamEntity();
        subjectQuery.setForUpdate(true);
        var settingQuery = new QueryParamEntity();
        settingQuery.setForUpdate(true);
        return subjects.createQuery()
                .setParam(subjectQuery)
                .where("id", setting.getSubjectId())
                .and("deleted", false)
                .and("enabled", true)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("prepay_source_changed", 409)))
                .then(
                        settings.createQuery()
                                .setParam(settingQuery)
                                .where("id", setting.getId())
                                .and("projectId", setting.getProjectId())
                                .and("deleted", false)
                                .fetchOne()
                                .switchIfEmpty(Mono.error(error("prepay_source_changed", 409))))
                .then();
    }

    private Mono<Detail> revalidate(
            PrepayQuoteEntity quote,
            PrepayConfirmationInput input,
            CashierInput funding,
            PrepayQuoteSnapshot frozen,
            CashierConfiguration.Resolved config,
            String fingerprint,
            LocalDate date,
            Authentication auth) {
        checkOpen(quote, input, date);
        return source.prepare(quote.getProjectId(), frozen.input(), date)
                .flatMap(
                        current -> {
                            if (!quote.getSnapshotJson().equals(ActivityCodec.encode(current)))
                                return Mono.error(error("prepay_source_changed", 409));
                            return billStore
                                    .prepare(frozen, auth)
                                    .flatMap(
                                            rows ->
                                                    quote(
                                                                    quote.getProjectId(),
                                                                    quote.getId(),
                                                                    auth,
                                                                    true)
                                                            .flatMap(
                                                                    locked -> {
                                                                        checkOpen(
                                                                                locked, input,
                                                                                date);
                                                                        return write(
                                                                                locked,
                                                                                frozen,
                                                                                funding,
                                                                                rows,
                                                                                config,
                                                                                fingerprint,
                                                                                date,
                                                                                input.requestId(),
                                                                                auth);
                                                                    }));
                        });
    }

    private Mono<Detail> write(
            PrepayQuoteEntity quote,
            PrepayQuoteSnapshot snapshot,
            CashierInput input,
            List<ReceivableEntity> rows,
            CashierConfiguration.Resolved config,
            String fingerprint,
            LocalDate date,
            String requestId,
            Authentication auth) {
        var result = rules.prepare(snapshot, rows, input, date);
        var payment = new PaymentEntity();
        payment.setId(
                UUID.nameUUIDFromBytes(
                                ActivityCodec.encode(
                                                List.of(
                                                        "prepay",
                                                        quote.getProjectId(),
                                                        auth.getUser().getId(),
                                                        requestId))
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString());
        init(payment, auth);
        payment.setProjectId(quote.getProjectId());
        payment.setCompanyId(config.company().getId());
        payment.setCompanyName(config.company().getCompanyName());
        payment.setRequestFingerprint(PaymentRules.digest(quote.getId() + ":" + fingerprint));
        payment.setStatus("SUCCEEDED");
        payment.setPayerName(snapshot.payerName());
        payment.setPayerRelationshipId(snapshot.input().payerRelationshipId());
        var selected =
                SettingRules.customers(
                        JSON.parseObject(snapshot.settingJson()).getString("snapshotJson"));
        selected.stream()
                .filter(c -> snapshot.payerCustomerId().equals(c.get("customerId")))
                .findFirst()
                .ifPresent(c -> payment.setPayerUserId((String) c.get("userId")));
        payment.setPaidAt(input.getPaidAt());
        payment.setAmount(new BigDecimal(result.total()));
        payment.setRemark(input.getRemark());
        payment.setAttachmentName(input.getAttachmentName());
        payment.setAttachmentFileId(input.getAttachmentFileId());
        payment.setSourceSnapshotJson(
                ActivityCodec.encode(
                        Map.of(
                                "prepayQuoteId",
                                quote.getId(),
                                "activityId",
                                Objects.toString(quote.getActivityId(), ""))));
        return ledger.persistPrepay(payment, rows, result, config, auth)
                .flatMap(
                        lines ->
                                recordConsumption(quote, payment, snapshot, result.rows(), auth)
                                        .then(
                                                audit(
                                                                quotes.createUpdate(),
                                                                auth,
                                                                quote.getVersion())
                                                        .set("status", "CONFIRMED")
                                                        .set("paymentId", payment.getId())
                                                        .set("confirmationFingerprint", fingerprint)
                                                        .where("id", quote.getId())
                                                        .and("projectId", quote.getProjectId())
                                                        .and("version", quote.getVersion())
                                                        .and("status", "OPEN")
                                                        .execute()
                                                        .flatMap(SubjectService::changed))
                                        .thenReturn(
                                                new Detail(
                                                        quote.getId(),
                                                        new CashierService.Detail(
                                                                payment,
                                                                lines,
                                                                CashierService.snapshot(
                                                                        payment)))));
    }

    private Mono<Void> recordConsumption(
            PrepayQuoteEntity quote,
            PaymentEntity payment,
            PrepayQuoteSnapshot snapshot,
            List<ReceivableEntity> rows,
            Authentication auth) {
        if (quote.getActivityId() == null) return Mono.empty();
        var entity = new PrepayConsumptionEntity();
        entity.setId(UUID.randomUUID().toString());
        init(entity, auth);
        entity.setProjectId(quote.getProjectId());
        entity.setSpaceId(quote.getSpaceId());
        entity.setActivityId(quote.getActivityId());
        entity.setQuoteId(quote.getId());
        entity.setPaymentId(payment.getId());
        var periods = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < rows.size(); index++) {
            var period = snapshot.periods().get(index);
            periods.add(
                    Map.of(
                            "receivableId",
                            rows.get(index).getId(),
                            "start",
                            period.start(),
                            "end",
                            period.end(),
                            "originalAmount",
                            period.originalAmount(),
                            "paidAmount",
                            period.paidAmount(),
                            "discountAmount",
                            period.discountAmount()));
        }
        entity.setPeriodsJson(ActivityCodec.encode(periods));
        return consumption.insert(entity).then();
    }

    private Mono<PrepayQuoteEntity> quote(
            String project, String id, Authentication auth, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return quotes.createQuery()
                .setParam(query)
                .where("id", id)
                .and("projectId", project)
                .and("creatorId", auth.getUser().getId())
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("prepay_quote_not_found", 404)))
                .flatMap(
                        row ->
                                AssetsHolder.assertPermission(
                                                AssetType.of("space"),
                                                row.getSpaceId(),
                                                CrudAssetPermission.save)
                                        .thenReturn(row));
    }

    private Mono<Detail> resume(PrepayQuoteEntity quote, String fingerprint) {
        if (!"CONFIRMED".equals(quote.getStatus())) return Mono.empty();
        if (!Objects.equals(quote.getConfirmationFingerprint(), fingerprint))
            return Mono.error(error("prepay_request_conflict", 409));
        return cashier.detail(quote.getProjectId(), quote.getPaymentId())
                .map(receipt -> new Detail(quote.getId(), receipt));
    }

    private static void checkOpen(
            PrepayQuoteEntity quote, PrepayConfirmationInput input, LocalDate date) {
        if (!Objects.equals(quote.getVersion(), input.version()))
            throw error("version_conflict", 409);
        if (!"OPEN".equals(quote.getStatus())
                || quote.getExpiresAt() <= System.currentTimeMillis()
                || !ActivityCodec.decode(quote.getSnapshotJson(), PrepayQuoteSnapshot.class)
                        .businessDate()
                        .equals(date.toString())) throw error("prepay_quote_expired", 409);
    }

    private static CashierInput funding(PrepayConfirmationInput i, PrepayQuoteSnapshot snapshot) {
        var result = new CashierInput();
        result.setCompanyId(i.companyId());
        result.setPaidAt(i.paidAt());
        result.setTenders(i.tenders());
        result.setPayerName(snapshot.payerName());
        result.setPayerRelationshipId(snapshot.input().payerRelationshipId());
        result.setRemark(i.remark());
        result.setAttachmentName(i.attachmentName());
        result.setAttachmentFileId(i.attachmentFileId());
        return result;
    }

    private static void validate(PrepayConfirmationInput input, LocalDate date) {
        if (input == null
                || date == null
                || input.requestId() == null
                || !input.requestId().matches("[A-Za-z0-9_-]{8,100}")
                || input.version() == null
                || input.version() < 0) throw error("prepay_invalid", 400);
    }

    private static Throwable duplicate(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("prepay_request_conflict", 409);
        return failure;
    }

    public record Detail(String quoteId, CashierService.Detail receipt) {}
}
