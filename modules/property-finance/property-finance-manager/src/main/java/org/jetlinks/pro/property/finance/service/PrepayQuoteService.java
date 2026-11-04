package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.init;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.activity.*;
import org.jetlinks.pro.property.finance.entity.PrepayQuoteEntity;
import org.jetlinks.pro.property.finance.payment.PaymentRules;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** 报价保存十分钟供柜台核对及网络重试；报价本身绝不产生支付、优惠或活动消费。 */
@Service
@RequiredArgsConstructor
public class PrepayQuoteService {
    private static final long VALID_MILLIS = 600_000;
    private final FinanceScope scope;
    private final PrepayQuoteSource source;
    private final ReactiveRepository<PrepayQuoteEntity, String> quotes;

    @Transactional
    public Mono<View> create(String project, PrepayQuoteInput input, LocalDate date) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            validate(input, date);
                                            return TenantHolder.currentId()
                                                    .defaultIfEmpty("")
                                                    .flatMap(
                                                            tenant ->
                                                                    create(
                                                                            project, input, date,
                                                                            auth, tenant));
                                        }))
                .onErrorMap(PrepayQuoteService::duplicate)
                .as(MonoTracer.create("/property/finance/prepay/quote"));
    }

    private Mono<View> create(
            String project,
            PrepayQuoteInput input,
            LocalDate date,
            org.hswebframework.web.authorization.Authentication auth,
            String tenant) {
        String identity =
                ActivityCodec.encode(
                        List.of(tenant, project, auth.getUser().getId(), input.requestId()));
        String id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        String fingerprint = PaymentRules.digest(ActivityCodec.encode(input));
        return existing(project, id, auth.getUser().getId(), fingerprint)
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        source.prepare(project, input, date)
                                                .flatMap(
                                                        snapshot ->
                                                                save(
                                                                        project,
                                                                        id,
                                                                        fingerprint,
                                                                        snapshot,
                                                                        auth))));
    }

    private Mono<View> save(
            String project,
            String id,
            String fingerprint,
            PrepayQuoteSnapshot snapshot,
            org.hswebframework.web.authorization.Authentication auth) {
        // 基础和设置行锁串行化同房报价；等待者再次读取，确保同请求并发可恢复原结果。
        return existing(project, id, auth.getUser().getId(), fingerprint)
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    var row = new PrepayQuoteEntity();
                                    row.setId(id);
                                    init(row, auth);
                                    row.setProjectId(project);
                                    row.setSpaceId(
                                            com.alibaba
                                                    .fastjson
                                                    .JSON
                                                    .parseObject(snapshot.settingJson())
                                                    .getString("spaceId"));
                                    row.setSettingId(snapshot.input().settingId());
                                    row.setActivityId(snapshot.input().activityId());
                                    row.setCreatorId(auth.getUser().getId());
                                    row.setRequestFingerprint(fingerprint);
                                    row.setStatus("OPEN");
                                    row.setExpiresAt(row.getCreateTime() + VALID_MILLIS);
                                    row.setSnapshotJson(ActivityCodec.encode(snapshot));
                                    return quotes.insert(row).thenReturn(view(row));
                                }));
    }

    public Mono<View> detail(String project, String id) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(auth -> existing(project, id, auth.getUser().getId(), null))
                .switchIfEmpty(Mono.error(error("prepay_quote_not_found", 404)));
    }

    private Mono<View> existing(String project, String id, String owner, String fingerprint) {
        return quotes.createQuery()
                .where("id", id)
                .and("projectId", project)
                .and("creatorId", owner)
                .and("deleted", false)
                .fetchOne()
                .flatMap(
                        row ->
                                AssetsHolder.assertPermission(
                                                AssetType.of("space"),
                                                row.getSpaceId(),
                                                CrudAssetPermission.save)
                                        .then(
                                                Mono.fromSupplier(
                                                        () -> {
                                                            if (fingerprint != null
                                                                    && !fingerprint.equals(
                                                                            row
                                                                                    .getRequestFingerprint()))
                                                                throw error(
                                                                        "prepay_request_conflict",
                                                                        409);
                                                            return view(row);
                                                        })));
    }

    private static void validate(PrepayQuoteInput i, LocalDate date) {
        if (i == null
                || date == null
                || date.getYear() < 1
                || date.getYear() > 9999
                || i.requestId() == null
                || !i.requestId().matches("[A-Za-z0-9_-]{8,100}")
                || !id(i.settingId())
                || !id(i.payerRelationshipId())
                || i.activityId() != null && !id(i.activityId())
                || i.paidMonths() < 1
                || i.paidMonths() > 1200
                || i.giftMonths() < 0
                || i.giftMonths() > 1200
                || i.activityId() == null && i.giftMonths() != 0)
            throw error("prepay_invalid", 400);
    }

    private static boolean id(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 64
                && value.equals(value.trim());
    }

    private static View view(PrepayQuoteEntity row) {
        var snapshot = ActivityCodec.decode(row.getSnapshotJson(), PrepayQuoteSnapshot.class);
        String status =
                "OPEN".equals(row.getStatus()) && row.getExpiresAt() <= System.currentTimeMillis()
                        ? "EXPIRED"
                        : row.getStatus();
        return new View(
                row.getId(),
                row.getVersion(),
                status,
                row.getExpiresAt(),
                snapshot.businessDate(),
                snapshot.houseName(),
                snapshot.payerName(),
                snapshot.payAmount(),
                snapshot.originalAmount(),
                snapshot.periods().stream()
                        .map(
                                period ->
                                        new Line(
                                                period.type(),
                                                period.start(),
                                                period.end(),
                                                period.originalAmount(),
                                                period.paidAmount(),
                                                period.discountAmount()))
                        .toList());
    }

    private static Throwable duplicate(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("prepay_request_conflict", 409);
        return failure;
    }

    public record Line(
            String type,
            String start,
            String end,
            String originalAmount,
            String paidAmount,
            String discountAmount) {}

    public record View(
            String id,
            Long version,
            String status,
            Long expiresAt,
            String businessDate,
            String houseName,
            String payerName,
            String payAmount,
            String originalAmount,
            List<Line> lines) {}
}
