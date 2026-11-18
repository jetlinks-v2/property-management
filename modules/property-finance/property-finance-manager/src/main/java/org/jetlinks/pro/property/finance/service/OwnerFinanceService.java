package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.setting.SettingRules;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.*;

/** 房屋共同账单限本人被指定的费用；预收账户及收款记录限本人，不泄露其他付款人的资料。 */
@Service
@RequiredArgsConstructor
public class OwnerFinanceService {
    private final OwnerHouseSource houses;
    private final FinanceScope scope;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<FundAccountEntity, String> accounts;
    private final ReactiveRepository<FundEntryEntity, String> entries;
    private final ReactiveRepository<PaymentEntity, String> payments;
    private final ReactiveRepository<PaymentAllocationEntity, String> lines;

    public Mono<BillPage> bills(String project, String space, boolean paid, int page, int size) {
        return bills(project, space, paid, page, size, null);
    }

    public Mono<BillPage> bills(String project, String space, boolean paid, int page, int size, String energyType) {
        validatePage(page, size);
        if (energyType != null && !Set.of("water", "electricity", "gas").contains(energyType))
            return Mono.error(error("owner_query_invalid",400));
        return houses.require(project, space)
                .flatMap(
                        owner -> {
                            var payer = owner.context().customers().get(0);
                            // 冻结快照支持多收费对象；有界读取后按可信客户身份筛选，避免仅查主客户漏掉共同付款人。
                            return bills.createQuery()
                                    .setParam(sorted("periodStart"))
                                    .where("projectId", project)
                                    .and("spaceId", space)
                                    .and("deleted", false)
                                    .and("reviewState", "APPROVED")
                                    .fetch()
                                    .take(10001)
                                    .collectList()
                                    .map(
                                            rows -> {
                                                limit(rows, 10000);
                                                var visible =
                                                        rows.stream()
                                                                .filter(
                                                                        row ->
                                                                                !Boolean.TRUE
                                                                                        .equals(
                                                                                                row
                                                                                                        .getSplit()))
                                                                .filter(
                                                                        row ->
                                                                                SettingRules
                                                                                        .selectedCustomer(
                                                                                                row,
                                                                                                payer
                                                                                                        .customerId()))
                                                                .filter(
                                                                        row ->
                                                                                paid
                                                                                        ? amount(
                                                                                                                        row
                                                                                                                                .getArrears())
                                                                                                                .signum()
                                                                                                        <= 0
                                                                                                && amount(
                                                                                                                        row
                                                                                                                                .getPaidAmount())
                                                                                                                .signum()
                                                                                                        > 0
                                                                                        : amount(
                                                                                                                row
                                                                                                                        .getArrears())
                                                                                                        .signum()
                                                                                                > 0)
                                                                .map(OwnerFinanceService::bill)
                                                                .filter(row -> energyType == null || energyType.equals(row.energyType()))
                                                                .toList();
                                                var selected = page(visible, page, size);
                                                var totalAmount =
                                                        visible.stream()
                                                                .map(
                                                                        b ->
                                                                                new BigDecimal(
                                                                                        b
                                                                                                .arrears()))
                                                                .reduce(
                                                                        BigDecimal.ZERO,
                                                                        BigDecimal::add);
                                                return new BillPage(
                                                        selected.total(),
                                                        page,
                                                        size,
                                                        money(totalAmount),
                                                        selected.data());
                                            });
                        });
    }

    public Mono<List<Account>> accounts(String project, String space) {
        return houses.require(project, space)
                .flatMap(
                        owner ->
                                accounts.createQuery()
                                        .setParam(sorted("subjectName"))
                                        .where("projectId", project)
                                        .and("spaceId", space)
                                        .and(
                                                "customerId",
                                                owner.context().customers().get(0).customerId())
                                        .and("type", "ADVANCE")
                                        .and("deleted", false)
                                        .fetch()
                                        .take(501)
                                        .collectList()
                                        .map(
                                                rows -> {
                                                    limit(rows, 500);
                                                    return rows.stream()
                                                            .map(
                                                                    row ->
                                                                            new Account(
                                                                                    row.getId(),
                                                                                    money(
                                                                                            row
                                                                                                    .getBalance()),
                                                                                    money(
                                                                                            amount(
                                                                                                            row
                                                                                                                    .getBalance())
                                                                                                    .subtract(
                                                                                                            amount(
                                                                                                                    row
                                                                                                                            .getRefundReservedAmount()))),
                                                                                    row.getSubjectId(), row.getSubjectName(), row.getStandardId(), row.getStandardName()))
                                                            .toList();
                                                }));
    }

    /** 按房屋及可信付款人核验单账，不能用可猜测的账单ID跨房屋读取。 */
    public Mono<Bill> bill(String project, String space, String id) {
        if (id == null || id.isBlank() || id.length() > 64) return Mono.error(error("owner_query_invalid",400));
        return houses.require(project, space).flatMap(owner -> bills.createQuery()
            .where("id",id).and("projectId",project).and("spaceId",space).and("deleted",false)
            .and("reviewState","APPROVED").fetchOne()
            .filter(row -> !Boolean.TRUE.equals(row.getSplit()) && visible(row, owner.context().customers().get(0).customerId()))
            .switchIfEmpty(Mono.error(error("owner_bill_not_found",404))).map(OwnerFinanceService::bill));
    }

    public Mono<Page<Entry>> entries(
            String project, String space, String account, int page, int size) {
        validatePage(page, size);
        return houses.require(project, space)
                .flatMap(
                        owner ->
                                accounts.createQuery()
                                        .where("id", account)
                                        .and("projectId", project)
                                        .and("spaceId", space)
                                        .and(
                                                "customerId",
                                                owner.context().customers().get(0).customerId())
                                        .and("type", "ADVANCE")
                                        .and("deleted", false)
                                        .fetchOne()
                                        .switchIfEmpty(Mono.error(error("owner_room_denied", 403)))
                                        .flatMap(
                                                row ->
                                                        entryPage(
                                                                project,
                                                                space,
                                                                List.of(account),
                                                                page,
                                                                size)));
    }

    /** 本人当前房屋各预收账户的统一流水；账户范围仍由服务端关系派生。 */
    public Mono<Page<Entry>> roomEntries(String project, String space, int page, int size) {
        validatePage(page, size);
        return accounts(project, space)
                .flatMap(
                        rows ->
                                entryPage(
                                        project,
                                        space,
                                        rows.stream().map(Account::id).toList(),
                                        page,
                                        size));
    }

    private Mono<Page<Entry>> entryPage(
            String project, String space, List<String> accountIds, int page, int size) {
        if (accountIds.isEmpty()) return Mono.just(new Page<>(0, page, size, List.of()));
        var q = new QueryParamEntity();
        q.setPageIndex(page);
        q.setPageSize(size);
        q.setPaging(true);
        q.toQuery().orderByDesc("occurredAt").orderByDesc("id");
        return entries.createQuery()
                .where("projectId", project)
                .and("spaceId", space)
                .in("accountId", accountIds)
                .and("deleted", false)
                .count()
                .flatMap(
                        total ->
                                entries.createQuery()
                                        .setParam(q)
                                        .where("projectId", project)
                                        .and("spaceId", space)
                                        .in("accountId", accountIds)
                                        .and("deleted", false)
                                        .fetch()
                                        .map(
                                                row ->
                                                        new Entry(
                                                                row.getId(),
                                                                row.getAction(),
                                                                row.getOccurredAt(),
                                                                money(row.getAmount()),
                                                                money(row.getBeforeBalance()),
                                                                money(row.getAfterBalance()), row.getSubjectName(), row.getStandardName()))
                                        .collectList()
                                        .map(rows -> new Page<>(total, page, size, rows)));
    }

    public Mono<Page<Record>> records(String project, String space, int page, int size) {
        validatePage(page, size);
        return houses.require(project, space)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                payments.createQuery()
                                        .setParam(sorted("paidAt"))
                                        .where("projectId", project)
                                        .and("payerUserId", auth.getUser().getId())
                                        .and("deleted", false)
                                        .fetch()
                                        .take(10001)
                                        .collectList()
                                        .flatMap(
                                                found -> {
                                                    limit(found, 10000);
                                                    if (found.isEmpty())
                                                        return Mono.just(
                                                                new Page<Record>(
                                                                        0, page, size, List.of()));
                                                    var byId = new HashMap<String, PaymentEntity>();
                                                    found.forEach(
                                                            row -> byId.put(row.getId(), row));
                                                    return lines.createQuery()
                                                            .setParam(sorted("createTime"))
                                                            .where("projectId", project)
                                                            .and("spaceId", space)
                                                            .and("deleted", false)
                                                            .in("paymentId", byId.keySet())
                                                            .fetch()
                                                            .take(10001)
                                                            .collectList()
                                                            .map(
                                                                    rows -> {
                                                                        limit(rows, 10000);
                                                                        return page(
                                                                                rows.stream()
                                                                                        .map(
                                                                                                row -> {
                                                                                                    var
                                                                                                            payment =
                                                                                                                    byId
                                                                                                                            .get(
                                                                                                                                    row
                                                                                                                                            .getPaymentId());
                                                                                                    return new Record(
                                                                                                            row
                                                                                                                    .getId(),
                                                                                                            row
                                                                                                                    .getCalcStartDate(),
                                                                                                            row
                                                                                                                    .getCalcEndDate(),
                                                                                                            payment
                                                                                                                    .getPaidAt(),
                                                                                                            row
                                                                                                                    .getMethodText(),
                                                                                                            payment
                                                                                                                    .getStatus(),
                                                                                                            money(
                                                                                                                    row
                                                                                                                            .getAmount()),
                                                                                                            money(
                                                                                                                    row
                                                                                                                            .getRefundedAmount()));
                                                                                                })
                                                                                        .toList(),
                                                                                page,
                                                                                size);
                                                                    });
                                                }));
    }

    static boolean visible(ReceivableEntity row, String customer) {
        return SettingRules.selectedCustomer(row, customer);
    }

    static void validatePage(int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 50)
            throw error("owner_query_invalid", 400);
    }

    private static QueryParamEntity paging(int page, int size) {
        var q = new QueryParamEntity();
        q.setPageIndex(page);
        q.setPageSize(size);
        q.setPaging(true);
        q.toQuery().orderByDesc("createTime").orderByDesc("id");
        return q;
    }

    private static QueryParamEntity sorted(String field) {
        var q = new QueryParamEntity();
        q.setPaging(false);
        q.toQuery().orderByDesc(field).orderByAsc("id");
        return q;
    }

    private static void limit(List<?> rows, int max) {
        if (rows.size() > max) throw error("owner_range_too_large", 400);
    }

    static <T> Page<T> page(List<T> rows, int page, int size) {
        return new Page<>(
                rows.size(),
                page,
                size,
                rows.stream().skip((long) page * size).limit(size).toList());
    }

    private static BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    static String money(BigDecimal value) {
        return amount(value).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static Bill bill(ReceivableEntity row) {
        return new Bill(
                row.getId(),
                row.getSubjectName(),
                row.getPeriodStart(),
                row.getPeriodEnd(),
                row.getDueDate(),
                row.getPeriodStatus(),
                money(row.getActualReceivableAmount()),
                money(row.getPaidAmount()),
                money(row.getArrears()),
                money(row.getFeeReductionAmount()),
                money(row.getFinalLateFeeAmount()), row.getStandardName(), row.getReceivableDate(), row.getReviewedOn(),
                row.getSourceType(), meterUsage(row), meterPrice(row), SettingRules.energyType(SettingRules.standard(row.getSnapshotJson())));
    }

    private static String meterUsage(ReceivableEntity row) {
        if (!"METER".equals(row.getSourceType())) return null;
        var quantity = com.alibaba.fastjson.JSON.parseObject(row.getSnapshotJson()).getBigDecimal("quantity");
        return quantity == null ? null : quantity.toPlainString();
    }

    private static String meterPrice(ReceivableEntity row) {
        return "METER".equals(row.getSourceType()) ? money(SettingRules.standard(row.getSnapshotJson()).getPrice()) : null;
    }

    public record Page<T>(long total, int pageIndex, int pageSize, List<T> data) {}

    public record BillPage(
            long total, int pageIndex, int pageSize, String totalAmount, List<Bill> data) {}

    public record Bill(
            String id,
            String subjectName,
            String periodStart,
            String periodEnd,
            String dueDate,
            String periodStatus,
            String amount,
            String paidAmount,
            String arrears,
            String discount,
            String lateFee, String standardName, String receivableDate, String confirmedDate, String sourceType,
            String usage, String price, String energyType) {}

    public record Account(String id, String balance, String available, String subjectId, String subjectName, String standardId, String standardName) {}

    public record Entry(
            String id,
            String action,
            String occurredAt,
            String amount,
            String beforeBalance,
            String afterBalance, String subjectName, String standardName) {}

    public record Record(
            String id,
            String start,
            String end,
            String paidAt,
            String method,
            String status,
            String amount,
            String refunded) {}
}
