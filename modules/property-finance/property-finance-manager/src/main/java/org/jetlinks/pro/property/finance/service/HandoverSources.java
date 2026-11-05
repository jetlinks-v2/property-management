package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.audit;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.PaymentRules;
import org.springframework.stereotype.Component;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.util.*;

/** 只读取已落账的原始事实；整单展开与现金口径由后端确定，不接受客户端金额。 */
@Component
@RequiredArgsConstructor
public class HandoverSources {
    private final ReactiveRepository<PaymentEntity, String> payments;
    private final ReactiveRepository<PaymentAllocationEntity, String> paymentLines;
    private final ReactiveRepository<RefundEntity, String> refunds;
    private final ReactiveRepository<RefundAllocationEntity, String> refundLines;
    private final ReactiveRepository<FundEntryEntity, String> entries;
    private final ReactiveRepository<FundAccountEntity, String> accounts;
    private final ReactiveRepository<FundCreditEntity, String> credits;

    public record Item(String type, String id, Long version) {}

    public record Source(String type, String id, Long version, List<HandoverLineEntity> lines) {}

    Mono<List<Source>> load(
            String project, List<Item> input, boolean lock, CrudAssetPermission permission) {
        // 所有账户先按ID排序；随后原主单按type/id排序，交账与原存入撤销共用账户锁。
        var lockAccounts =
                !lock
                        ? Mono.<Void>empty()
                        : Flux.fromIterable(input)
                                .filter(i -> "FUND".equals(i.type()))
                                .concatMap(
                                        i ->
                                                entries.createQuery()
                                                        .where("id", i.id())
                                                        .and("projectId", project)
                                                        .and("deleted", false)
                                                        .fetchOne()
                                                        .switchIfEmpty(
                                                                Mono.error(
                                                                        error(
                                                                                "handover_source_missing",
                                                                                404))))
                                .map(FundEntryEntity::getAccountId)
                                .distinct()
                                .sort()
                                .concatMap(
                                        id ->
                                                accounts.createQuery()
                                                        .setParam(param(true))
                                                        .where("id", id)
                                                        .and("projectId", project)
                                                        .and("deleted", false)
                                                        .fetchOne()
                                                        .switchIfEmpty(
                                                                Mono.error(
                                                                        error(
                                                                                "handover_source_missing",
                                                                                404))))
                                .then();
        return lockAccounts
                .thenMany(
                        Flux.fromIterable(input).concatMap(i -> load(project, i, lock, permission)))
                .collectList();
    }

    Mono<Source> load(String project, Item item, boolean lock, CrudAssetPermission permission) {
        Mono<Source> source =
                switch (item.type()) {
                    case "PAYMENT" -> payment(project, item.id(), lock);
                    case "REFUND" -> refund(project, item.id(), lock);
                    case "FUND" -> fund(project, item.id(), lock);
                    default -> Mono.error(error("handover_invalid", 400));
                };
        return source.flatMap(
                s -> {
                    if (s.lines().isEmpty() || s.lines().size() > 10000)
                        return Mono.error(error("handover_source_invalid", 409));
                    return authorize(s.lines(), permission).thenReturn(s);
                });
    }

    Mono<Void> authorize(List<HandoverLineEntity> rows, CrudAssetPermission permission) {
        return AssetsHolder.currentReactive()
                .switchIfEmpty(Mono.error(error("permission_denied", 403)))
                .flatMap(
                        holder ->
                                holder.assertPermission(
                                        "space",
                                        rows.stream()
                                                .map(HandoverLineEntity::getSpaceId)
                                                .distinct()
                                                .toList(),
                                        permission));
    }

    private Mono<Source> payment(String project, String id, boolean lock) {
        return payments.createQuery()
                .setParam(param(lock))
                .where("id", id)
                .and("projectId", project)
                .and("deleted", false)
                .and("status", "SUCCEEDED")
                .fetchOne()
                .switchIfEmpty(Mono.error(error("handover_source_missing", 404)))
                .flatMap(
                        p ->
                                paymentLines
                                        .createQuery()
                                        .setParam(limit())
                                        .where("paymentId", id)
                                        .and("projectId", project)
                                        .and("deleted", false)
                                        .fetch()
                                        .collectList()
                                        .map(
                                                rows -> {
                                                    var result =
                                                            rows.stream()
                                                                    .map(
                                                                            r -> {
                                                                                var line =
                                                                                        line(
                                                                                                "PAYMENT",
                                                                                                p
                                                                                                        .getId(),
                                                                                                r
                                                                                                        .getId(),
                                                                                                r
                                                                                                        .getSpaceId(),
                                                                                                r
                                                                                                        .getFullName(),
                                                                                                r
                                                                                                        .getCustomerName(),
                                                                                                p
                                                                                                        .getCompanyId(),
                                                                                                p
                                                                                                        .getCompanyName(),
                                                                                                r
                                                                                                        .getSubjectName(),
                                                                                                r
                                                                                                        .getMethodText(),
                                                                                                r
                                                                                                        .getBankName(),
                                                                                                p
                                                                                                        .getPaidAt(),
                                                                                                r
                                                                                                        .getAmount());
                                                                                if (!PaymentRules
                                                                                                .MANUAL_METHODS
                                                                                                .contains(
                                                                                                        r
                                                                                                                .getMethod())
                                                                                        && !Set.of(
                                                                                                        "ADVANCE",
                                                                                                        "DEPOSIT")
                                                                                                .contains(
                                                                                                        r
                                                                                                                .getMethod()))
                                                                                    throw error(
                                                                                            "handover_source_invalid",
                                                                                            409);
                                                                                line
                                                                                        .setBusinessType(
                                                                                                r
                                                                                                        .getSourceType());
                                                                                line
                                                                                        .setCalcStartDate(
                                                                                                r
                                                                                                        .getCalcStartDate());
                                                                                line.setCalcEndDate(
                                                                                        r
                                                                                                .getCalcEndDate());
                                                                                line
                                                                                        .setReceivableId(
                                                                                                r
                                                                                                        .getReceivableId());
                                                                                line.setCashAmount(
                                                                                        PaymentRules
                                                                                                        .MANUAL_METHODS
                                                                                                        .contains(
                                                                                                                r
                                                                                                                        .getMethod())
                                                                                                ? r
                                                                                                        .getAmount()
                                                                                                : BigDecimal
                                                                                                        .ZERO
                                                                                                        .setScale(
                                                                                                                2));
                                                                                return line;
                                                                            })
                                                                    .toList();
                                                    if (sum(result, false).compareTo(p.getAmount())
                                                            != 0)
                                                        throw error("handover_source_invalid", 409);
                                                    return new Source(
                                                            "PAYMENT", id, p.getVersion(), result);
                                                }));
    }

    private Mono<Source> refund(String project, String id, boolean lock) {
        return refunds.createQuery()
                .setParam(param(lock))
                .where("id", id)
                .and("projectId", project)
                .and("deleted", false)
                .and("status", "SUCCEEDED")
                .fetchOne()
                .switchIfEmpty(Mono.error(error("handover_source_missing", 404)))
                .flatMap(
                        p ->
                                refundLines
                                        .createQuery()
                                        .setParam(limit())
                                        .where("refundId", id)
                                        .and("projectId", project)
                                        .and("deleted", false)
                                        .fetch()
                                        .collectList()
                                        .map(
                                                rows -> {
                                                    var result =
                                                            rows.stream()
                                                                    .map(
                                                                            r -> {
                                                                                var line =
                                                                                        line(
                                                                                                "REFUND",
                                                                                                id,
                                                                                                r
                                                                                                        .getId(),
                                                                                                r
                                                                                                        .getSpaceId(),
                                                                                                r
                                                                                                        .getFullName(),
                                                                                                r
                                                                                                        .getCustomerName(),
                                                                                                p
                                                                                                        .getCompanyId(),
                                                                                                p
                                                                                                        .getCompanyName(),
                                                                                                r
                                                                                                        .getSubjectName(),
                                                                                                p
                                                                                                        .getMethodText(),
                                                                                                p
                                                                                                        .getBankName(),
                                                                                                p
                                                                                                        .getRefundedAt(),
                                                                                                r.getAmount()
                                                                                                        .negate());
                                                                                line
                                                                                        .setBusinessType(
                                                                                                "REFUND");
                                                                                line.setCashAmount(
                                                                                        "CASH"
                                                                                                        .equals(
                                                                                                                p
                                                                                                                        .getDestination())
                                                                                                ? line
                                                                                                        .getAmount()
                                                                                                : BigDecimal
                                                                                                        .ZERO
                                                                                                        .setScale(
                                                                                                                2));
                                                                                return line;
                                                                            })
                                                                    .toList();
                                                    if (sum(result, false)
                                                                    .compareTo(
                                                                            p.getAmount().negate())
                                                            != 0)
                                                        throw error("handover_source_invalid", 409);
                                                    return new Source(
                                                            "REFUND", id, p.getVersion(), result);
                                                }));
    }

    private Mono<Source> fund(String project, String id, boolean lock) {
        return entries.createQuery()
                .setParam(param(lock))
                .where("id", id)
                .and("projectId", project)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("handover_source_missing", 404)))
                .map(
                        e -> {
                            // 冲抵/冲抵退回/实收转预存已有PAYMENT或REFUND事实，不能重复登记第二次现金或金额。
                            if (e.getPaymentId() != null || e.getRefundId() != null)
                                throw error("handover_source_invalid", 409);
                            var line =
                                    line(
                                            "FUND",
                                            id,
                                            id,
                                            e.getSpaceId(),
                                            e.getFullName(),
                                            e.getCustomerName(),
                                            e.getCompanyId(),
                                            e.getCompanyName(),
                                            e.getSubjectName(),
                                            e.getMethodText(),
                                            e.getBankName(),
                                            e.getOccurredAt(),
                                            e.getAmount());
                            line.setBusinessType(e.getAction());
                            line.setCalcStartDate(e.getCalcStartDate());
                            line.setCalcEndDate(e.getCalcEndDate());
                            line.setCashAmount(e.getCashAmount());
                            return new Source("FUND", id, e.getVersion(), List.of(line));
                        });
    }

    Mono<Void> markCredit(String project, Source s, boolean closed, Authentication auth) {
        if (!"FUND".equals(s.type())) return Mono.empty();
        return credits.createQuery()
                .where("projectId", project)
                .and("entryId", s.id())
                .and("deleted", false)
                .fetchOne()
                .flatMap(
                        c -> {
                            if (Boolean.TRUE.equals(c.getMonthClosed())
                                    || (closed && Boolean.TRUE.equals(c.getDayClosed())))
                                return Mono.error(error("handover_state_conflict", 409));
                            return audit(credits.createUpdate(), auth, c.getVersion())
                                    .set("dayClosed", closed)
                                    .where("id", c.getId())
                                    .and("version", c.getVersion())
                                    .and("deleted", false)
                                    .execute()
                                    .flatMap(SubjectService::changed);
                        })
                .then();
    }

    static BigDecimal sum(List<HandoverLineEntity> lines, boolean cash) {
        return lines.stream()
                .map(l -> cash ? l.getCashAmount() : l.getAmount())
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private static HandoverLineEntity line(
            String type,
            String source,
            String original,
            String space,
            String full,
            String customer,
            String company,
            String companyName,
            String subject,
            String method,
            String bank,
            String date,
            BigDecimal amount) {
        var l = new HandoverLineEntity();
        l.setSourceType(type);
        l.setSourceId(source);
        l.setOriginalLineId(original);
        l.setSpaceId(space);
        l.setFullName(full);
        l.setCustomerName(customer);
        l.setCompanyId(company);
        l.setCompanyName(companyName);
        l.setSubjectName(subject);
        l.setMethodText(method);
        l.setBankName(bank);
        l.setOccurredAt(date);
        l.setAmount(amount);
        return l;
    }

    static QueryParamEntity param(boolean lock) {
        var q = new QueryParamEntity();
        q.setPaging(false);
        q.setForUpdate(lock);
        return q;
    }

    static QueryParamEntity limit() {
        var q = new QueryParamEntity();
        q.setPageSize(10001);
        q.orderBy("id");
        return q;
    }
}
