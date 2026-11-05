package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.springframework.stereotype.Component;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** 公司锁后按应收、来源行的固定顺序加锁；与收银和算费共用应收锁串行化资金变化。 */
@Component
@RequiredArgsConstructor
public class RefundSourceService {
    private final ReactiveRepository<PaymentAllocationEntity, String> lines;
    private final ReactiveRepository<PaymentEntity, String> payments;
    private final ReactiveRepository<ReceivableEntity, String> bills;

    Mono<List<Option>> options(String project, List<String> ids) {
        if (ids == null
                || ids.isEmpty()
                || ids.size() > 100
                || new HashSet<>(ids).size() != ids.size()
                || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 64))
            return Mono.error(error("refund_invalid", 400));
        var input = new RefundInput();
        input.setItems(ids.stream().map(id -> new RefundInput.Item(id, 0L)).toList());
        return sourceLines(project, input, false, Route.CASH)
                .flatMapMany(Flux::fromIterable)
                .concatMap(
                        line ->
                                payments.createQuery()
                                        .where("projectId", project)
                                        .and("id", line.getPaymentId())
                                        .and("deleted", false)
                                        .and("status", "SUCCEEDED")
                                        .fetchOne()
                                        .switchIfEmpty(Mono.error(error("payment_not_found", 404)))
                                        .map(
                                                payment ->
                                                        new Option(
                                                                line,
                                                                payment.getCompanyId(),
                                                                payment.getCompanyName(),
                                                                payment.getPaidAt(),
                                                                payment.getPayerName())))
                .collectList();
    }

    Mono<Sources> load(String project, RefundInput input, boolean lock) {
        return load(project, input, lock, Route.CASH);
    }

    Mono<Sources> loadBalance(String project, RefundInput input, boolean lock) {
        return load(project, input, lock, Route.BALANCE);
    }

    private Mono<Sources> load(String project, RefundInput input, boolean lock, Route route) {
        return sourceLines(project, input, false, route)
                .flatMap(
                        initial ->
                                billRows(project, initial, lock)
                                        .flatMap(
                                                rows ->
                                                        sourceLines(project, input, lock, route)
                                                                .flatMap(
                                                                        locked ->
                                                                                validatePayments(
                                                                                                project,
                                                                                                input,
                                                                                                locked)
                                                                                        .thenReturn(
                                                                                                new Sources(
                                                                                                        locked,
                                                                                                        rows)))));
    }

    private Mono<List<PaymentAllocationEntity>> sourceLines(
            String project, RefundInput input, boolean lock, Route route) {
        return Flux.fromIterable(
                        input.getItems().stream().map(RefundInput.Item::id).sorted().toList())
                .concatMap(
                        id -> {
                            var query = new QueryParamEntity();
                            query.setForUpdate(lock);
                            return lines.createQuery()
                                    .setParam(query)
                                    .where("id", id)
                                    .and("projectId", project)
                                    .and("deleted", false)
                                    .fetchOne()
                                    .switchIfEmpty(Mono.error(error("payment_not_found", 404)))
                                    .doOnNext(
                                            line -> {
                                                if (!("RECEIVABLE".equals(line.getSourceType())
                                                                && line.getReceivableId() != null)
                                                        && !("TEMPORARY"
                                                                        .equals(
                                                                                line
                                                                                        .getSourceType())
                                                                && line.getReceivableId() == null))
                                                    throw error("refund_source_blocked", 409);
                                                if (positive(line.getInvoiceOccupiedAmount()))
                                                    throw error("refund_source_blocked", 409);
                                                if (!route.methods.contains(line.getMethod()))
                                                    throw error(
                                                            route == Route.CASH
                                                                    ? "fund_offset_refund_required"
                                                                    : "refund_source_blocked",
                                                            409);
                                            })
                                    .flatMap(
                                            line ->
                                                    AssetsHolder.assertPermission(
                                                                    AssetType.of("space"),
                                                                    line.getSpaceId(),
                                                                    CrudAssetPermission.save)
                                                            .thenReturn(line));
                        })
                .collectList();
    }

    private Mono<List<ReceivableEntity>> billRows(
            String project, List<PaymentAllocationEntity> sources, boolean lock) {
        return Flux.fromIterable(
                        sources.stream()
                                .map(PaymentAllocationEntity::getReceivableId)
                                .filter(Objects::nonNull)
                                .distinct()
                                .sorted()
                                .toList())
                .concatMap(
                        id -> {
                            var query = new QueryParamEntity();
                            query.setForUpdate(lock);
                            return bills.createQuery()
                                    .setParam(query)
                                    .where("id", id)
                                    .and("projectId", project)
                                    .and("deleted", false)
                                    .fetchOne()
                                    .switchIfEmpty(Mono.error(error("receivable_not_found", 404)))
                                    .map(
                                            row -> {
                                                // 票据尚未红冲、支付或代收占用时不能直接抽走原收款。
                                                if (Boolean.TRUE.equals(row.getSplit())
                                                        || Boolean.TRUE.equals(
                                                                row.getPaymentLocked())
                                                        || Boolean.TRUE.equals(
                                                                row.getBankCollectionLocked())
                                                        || Boolean.TRUE.equals(
                                                                row.getDiscountLocked())
                                                        || Boolean.TRUE.equals(
                                                                row.getChargeLocked())
                                                        || positive(row.getInvoiceOccupiedAmount())
                                                        || positive(row.getPaymentOccupiedAmount()))
                                                    throw error("refund_source_blocked", 409);
                                                return row;
                                            });
                        })
                .collectList();
    }

    private Mono<Void> validatePayments(
            String project, RefundInput input, List<PaymentAllocationEntity> sources) {
        return Flux.fromIterable(
                        sources.stream()
                                .map(PaymentAllocationEntity::getPaymentId)
                                .distinct()
                                .sorted()
                                .toList())
                .concatMap(
                        id ->
                                payments.createQuery()
                                        .where("id", id)
                                        .and("projectId", project)
                                        .and("deleted", false)
                                        .fetchOne()
                                        .switchIfEmpty(Mono.error(error("payment_not_found", 404)))
                                        .doOnNext(
                                                payment -> {
                                                    if (sources.stream()
                                                                    .anyMatch(
                                                                            line ->
                                                                                    id.equals(
                                                                                                    line
                                                                                                            .getPaymentId())
                                                                                            && !Objects
                                                                                                    .equals(
                                                                                                            line
                                                                                                                    .getSourceType(),
                                                                                                            payment
                                                                                                                    .getSourceType()))
                                                            || !"SUCCEEDED"
                                                                    .equals(payment.getStatus())
                                                            || !input.getCompanyId()
                                                                    .equals(payment.getCompanyId()))
                                                        throw error("refund_source_blocked", 409);
                                                    if (LocalDateTime.parse(input.getRefundedAt())
                                                            .isBefore(
                                                                    LocalDateTime.parse(
                                                                            payment.getPaidAt())))
                                                        throw error("refund_before_payment", 400);
                                                }))
                .then();
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private enum Route {
        CASH(PaymentRules.MANUAL_METHODS),
        BALANCE(FundRules.TYPES);
        final Set<String> methods;

        Route(Set<String> methods) {
            this.methods = methods;
        }
    }

    record Sources(List<PaymentAllocationEntity> lines, List<ReceivableEntity> bills) {}

    public record Option(
            PaymentAllocationEntity line,
            String companyId,
            String companyName,
            String paidAt,
            String payerName) {}
}
