package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.finance.activity.*;
import org.jetlinks.pro.property.finance.entity.PrepayQuoteEntity;
import org.jetlinks.pro.property.finance.payment.PaymentRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.*;

/** 业主预存只试算及复核；没有到账确认入口，不生成应收、实收、减免或活动消费。 */
@Service
@RequiredArgsConstructor
public class OwnerPrepayService {
    private final OwnerHouseSource houses;
    private final FinanceScope scope;
    private final PrepayOptionsService options;
    private final PrepayQuoteSource pricing;
    private final ReactiveRepository<PrepayQuoteEntity, String> quotes;

    public Mono<List<PrepayOptionsService.Setting>> options(
            String project, String space, LocalDate date) {
        return houses.require(project, space).flatMap(owner -> options.ownerOptions(owner, date));
    }

    @Transactional
    public Mono<PrepayQuoteService.View> quote(
            String project, String space, Request input, LocalDate date) {
        if (input == null
                || input.settingId() == null
                || input.settingId().length() > 64
                || input.paidMonths() < 1
                || input.paidMonths() > 12
                || input.giftMonths() < 0
                || input.giftMonths() > 1200
                || input.activityId() != null && input.activityId().length() > 64
                || input.activityId() == null && input.giftMonths() != 0)
            return Mono.error(error("prepay_invalid", 400));
        return houses.require(project, space)
                .flatMap(
                        owner ->
                                scope.authenticated()
                                        .flatMap(
                                                auth -> {
                                                    // 缴费身份由本人命令派生，绝不接收客户端的客户/关系ID。
                                                    var payer = owner.context().customers().get(0);
                                                    var request =
                                                            new PrepayQuoteInput(
                                                                    UUID.randomUUID().toString(),
                                                                    input.settingId(),
                                                                    payer.relationshipId(),
                                                                    input.activityId(),
                                                                    input.paidMonths(),
                                                                    input.giftMonths());
                                                    return pricing.prepareOwner(
                                                                    project, request, date, owner)
                                                            .flatMap(
                                                                    snapshot -> {
                                                                        var row =
                                                                                new PrepayQuoteEntity();
                                                                        row.setId(
                                                                                UUID.randomUUID()
                                                                                        .toString());
                                                                        InvoiceCompanyService.init(
                                                                                row, auth);
                                                                        row.setProjectId(project);
                                                                        row.setSpaceId(space);
                                                                        row.setSettingId(
                                                                                input.settingId());
                                                                        row.setActivityId(
                                                                                input.activityId());
                                                                        row.setCreatorId(
                                                                                auth.getUser()
                                                                                        .getId());
                                                                        row.setRequestFingerprint(
                                                                                PaymentRules.digest(
                                                                                        ActivityCodec
                                                                                                .encode(
                                                                                                        request)));
                                                                        row.setStatus("OPEN");
                                                                        row.setExpiresAt(
                                                                                System
                                                                                                .currentTimeMillis()
                                                                                        + 600_000);
                                                                        row.setSnapshotJson(
                                                                                ActivityCodec
                                                                                        .encode(
                                                                                                snapshot));
                                                                        return quotes.insert(row)
                                                                                .thenReturn(
                                                                                        PrepayQuoteService
                                                                                                .view(
                                                                                                        row));
                                                                    });
                                                }))
                .as(MonoTracer.create("/property/finance/owner/prepay/quote"));
    }

    @Transactional
    public Mono<PrepayQuoteService.View> revalidate(
            String project, String space, String id, LocalDate date) {
        return houses.require(project, space)
                .flatMap(
                        owner ->
                                scope.authenticated()
                                        .flatMap(
                                                auth ->
                                                        quotes.createQuery()
                                                                .where("id", id)
                                                                .and("projectId", project)
                                                                .and("spaceId", space)
                                                                .and(
                                                                        "creatorId",
                                                                        auth.getUser().getId())
                                                                .and("deleted", false)
                                                                .fetchOne()
                                                                .switchIfEmpty(
                                                                        Mono.error(
                                                                                error(
                                                                                        "prepay_quote_not_found",
                                                                                        404)))
                                                                .flatMap(
                                                                        row -> {
                                                                            if (!"OPEN"
                                                                                            .equals(
                                                                                                    row
                                                                                                            .getStatus())
                                                                                    || row
                                                                                                    .getExpiresAt()
                                                                                            <= System
                                                                                                    .currentTimeMillis())
                                                                                return Mono.error(
                                                                                        error(
                                                                                                "owner_quote_expired",
                                                                                                409));
                                                                            var previous =
                                                                                    ActivityCodec
                                                                                            .decode(
                                                                                                    row
                                                                                                            .getSnapshotJson(),
                                                                                                    PrepayQuoteSnapshot
                                                                                                            .class);
                                                                            return pricing.prepareOwner(
                                                                                            project,
                                                                                            previous
                                                                                                    .input(),
                                                                                            date,
                                                                                            owner)
                                                                                    .map(
                                                                                            current -> {
                                                                                                if (!ActivityCodec
                                                                                                        .encode(
                                                                                                                previous)
                                                                                                        .equals(
                                                                                                                ActivityCodec
                                                                                                                        .encode(
                                                                                                                                current)))
                                                                                                    throw error(
                                                                                                            "owner_quote_changed",
                                                                                                            409);
                                                                                                return PrepayQuoteService
                                                                                                        .view(
                                                                                                                row);
                                                                                            });
                                                                        })));
    }

    public record Request(String settingId, String activityId, int paidMonths, int giftMonths) {}
}
