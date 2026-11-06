package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.document.DocumentRules;
import org.jetlinks.pro.property.finance.document.DocumentRules.CanonicalSource;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.setting.SettingRules;
import org.springframework.stereotype.Component;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** 票据使用历史账务与税目快照。锁定调用须处于事务内，且由调用方先取得开票公司锁。 */
@Component
@RequiredArgsConstructor
public class DocumentSourceService {
    private final FinanceScope scope;
    private final DocumentRules rules;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<PaymentAllocationEntity, String> lines;
    private final ReactiveRepository<PaymentEntity, String> payments;
    private final ReactiveRepository<FundEntryEntity, String> fundEntries;
    private final ReactiveRepository<FundAccountEntity, String> fundAccounts;
    private final ReactiveRepository<FundCreditEntity, String> fundCredits;

    Mono<List<Source>> load(String project, List<Reference> references, boolean lock) {
        return scope.project(project, CrudAssetPermission.save)
                .then(Mono.defer(() -> loadSources(project, references, lock)))
                .as(MonoTracer.create("property.finance.document.sources"));
    }

    private Mono<List<Source>> loadSources(
            String project, List<Reference> references, boolean lock) {
        if (references == null
                || references.isEmpty()
                || references.size() > 1000
                || references.stream().anyMatch(Objects::isNull))
            return Mono.error(error("document_source_invalid", 400));
        return Flux.fromIterable(references)
                .concatMap(ref -> resolve(project, ref))
                .collectList()
                .flatMap(keys -> loadCanonical(project, keys, lock));
    }

    private Mono<List<Source>> loadCanonical(
            String project, List<CanonicalSource> keys, boolean lock) {
        rules.assertDistinct(keys);
        var ordered =
                keys.stream()
                        .sorted(
                                Comparator.comparing(CanonicalSource::type)
                                        .thenComparing(CanonicalSource::id))
                        .toList();
        // 公司锁之后：全部应收→实收行→按ID排序的资金账户→原存入；不按外部输入交替申请锁。
        return billRows(project, ordered, lock)
                .flatMap(
                        rows ->
                                Flux.fromIterable(ordered)
                                        .filter(key -> !"FUND_TOPUP".equals(key.type()))
                                        .concatMap(
                                                key ->
                                                        "RECEIVABLE".equals(key.type())
                                                                ? receivable(
                                                                        project,
                                                                        key,
                                                                        rows.get(key.id()),
                                                                        lock)
                                                                : temporary(project, key, lock))
                                        .collectList())
                .flatMap(
                        loaded ->
                                lockFundAccounts(project, ordered, lock)
                                        .thenMany(
                                                Flux.fromIterable(ordered)
                                                        .filter(
                                                                key ->
                                                                        "FUND_TOPUP"
                                                                                .equals(key.type()))
                                                        .concatMap(
                                                                key ->
                                                                        originalFund(
                                                                                project, key,
                                                                                lock)))
                                        .collectList()
                                        .map(
                                                funds -> {
                                                    var all = new ArrayList<>(loaded);
                                                    all.addAll(funds);
                                                    return all;
                                                }))
                .map(
                        loaded -> {
                            var indexed = new HashMap<CanonicalSource, Source>();
                            for (var source : loaded) indexed.put(source.canonical(), source);
                            // 对外结果与输入对应，内部加锁仍按规范来源排序，不能将金额串到其他行。
                            return keys.stream().map(indexed::get).toList();
                        });
    }

    private Mono<CanonicalSource> resolve(String project, Reference reference) {
        if (reference.type() == null
                || reference.id() == null
                || reference.id().isBlank()
                || reference.id().length() > 64
                || !reference.id().equals(reference.id().trim()))
            return Mono.error(error("document_source_invalid", 400));
        return switch (reference.type()) {
            case "FUND_TOPUP" ->
                    Mono.just(rules.canonical("FUND_TOPUP", reference.id(), null, null));
            case "RECEIVABLE" ->
                    Mono.just(rules.canonical("RECEIVABLE", reference.id(), null, null));
            case "RECEIVED" ->
                    lines.createQuery()
                            .where("id", reference.id())
                            .and("projectId", project)
                            .and("deleted", false)
                            .fetchOne()
                            .switchIfEmpty(Mono.error(error("document_source_not_found", 404)))
                            .flatMap(
                                    line ->
                                            payment(project, line.getPaymentId())
                                                    .map(
                                                            payment -> {
                                                                boolean regular =
                                                                        "RECEIVABLE"
                                                                                        .equals(
                                                                                                line
                                                                                                        .getSourceType())
                                                                                && line
                                                                                                .getReceivableId()
                                                                                        != null;
                                                                boolean temporary =
                                                                        "TEMPORARY"
                                                                                        .equals(
                                                                                                line
                                                                                                        .getSourceType())
                                                                                && line
                                                                                                .getReceivableId()
                                                                                        == null;
                                                                if ((!regular && !temporary)
                                                                        || !line.getSourceType()
                                                                                .equals(
                                                                                        payment
                                                                                                .getSourceType()))
                                                                    throw error(
                                                                            "document_source_invalid",
                                                                            409);
                                                                return rules.canonical(
                                                                        "RECEIVED",
                                                                        line.getId(),
                                                                        line.getReceivableId(),
                                                                        line.getPaymentId());
                                                            }));
            case "TEMP_PAYMENT", "TEMPORARY" ->
                    payment(project, reference.id())
                            .map(
                                    payment -> {
                                        if (!"TEMPORARY".equals(payment.getSourceType()))
                                            throw error("document_source_invalid", 409);
                                        return rules.canonical(
                                                "TEMP_PAYMENT", payment.getId(), null, null);
                                    });
            default -> Mono.error(error("document_source_invalid", 400));
        };
    }

    private Mono<Map<String, ReceivableEntity>> billRows(
            String project, List<CanonicalSource> keys, boolean lock) {
        return Flux.fromIterable(keys)
                .filter(key -> "RECEIVABLE".equals(key.type()))
                .concatMap(
                        key -> {
                            var query = new QueryParamEntity();
                            query.setForUpdate(lock);
                            return bills.createQuery()
                                    .setParam(query)
                                    .where("id", key.id())
                                    .and("projectId", project)
                                    .and("deleted", false)
                                    .fetchOne()
                                    .switchIfEmpty(
                                            Mono.error(error("document_source_not_found", 404)))
                                    .flatMap(
                                            bill ->
                                                    AssetsHolder.assertPermission(
                                                                    AssetType.of("space"),
                                                                    bill.getSpaceId(),
                                                                    CrudAssetPermission.save)
                                                            .thenReturn(bill))
                                    .doOnNext(
                                            bill -> {
                                                if (Boolean.TRUE.equals(bill.getSplit()))
                                                    throw error("document_source_blocked", 409);
                                            });
                        })
                .collectMap(ReceivableEntity::getId);
    }

    private Mono<Source> receivable(
            String project, CanonicalSource key, ReceivableEntity row, boolean lock) {
        return sourceLines(project, "receivableId", key.id(), lock)
                .flatMap(
                        receipts -> {
                            var standard = frozenStandard(row.getSnapshotJson());
                            var tax = tax(standard);
                            if (!Objects.equals(row.getStandardId(), standard.getId())
                                    || !Objects.equals(row.getSubjectId(), standard.getSubjectId()))
                                throw error("document_source_snapshot_invalid", 409);
                            var occupied = nonnegative(row.getInvoiceOccupiedAmount());
                            var allowed = nonnegative(row.getActualReceivableAmount());
                            var paid = nonnegative(row.getPaidAmount());
                            var net = BigDecimal.ZERO;
                            for (var line : receipts) {
                                assertBillLine(row, line);
                                net = net.add(remaining(line));
                            }
                            // 历史导入和真实核销必须一致；缺少实收行不能伪装成另一份可开票来源。
                            if (paid.compareTo(net) != 0)
                                throw error("document_source_snapshot_invalid", 409);
                            return latestPayment(project, receipts, paid, tax)
                                    .map(
                                            paidAt ->
                                                    new Source(
                                                            key,
                                                            project,
                                                            row.getSpaceId(),
                                                            row.getFullName(),
                                                            row.getCustomerId(),
                                                            row.getCustomerName(),
                                                            row.getSubjectId(),
                                                            row.getSubjectName(),
                                                            row.getStandardId(),
                                                            row.getStandardName(),
                                                            row.getCalcStartDate(),
                                                            row.getCalcEndDate(),
                                                            tax,
                                                            allowed,
                                                            paid,
                                                            occupied,
                                                            rules.available(allowed, occupied),
                                                            paidAt.orElse(null),
                                                            row,
                                                            receipts,
                                                            row.getSnapshotJson(),
                                                            null));
                        });
    }

    private Mono<Source> temporary(String project, CanonicalSource key, boolean lock) {
        return payment(project, key.id())
                .flatMap(
                        payment -> {
                            if (!"TEMPORARY".equals(payment.getSourceType()))
                                return Mono.error(error("document_source_invalid", 409));
                            return sourceLines(project, "paymentId", key.id(), lock)
                                    .flatMap(
                                            receipts ->
                                                    temporarySource(
                                                            project, key, payment, receipts));
                        });
    }

    private Mono<Source> temporarySource(
            String project,
            CanonicalSource key,
            PaymentEntity payment,
            List<PaymentAllocationEntity> receipts) {
        if (receipts.isEmpty()) return Mono.error(error("document_source_snapshot_invalid", 409));
        var first = receipts.get(0);
        for (var line : receipts) assertTemporaryLine(first, line);
        var standard = frozenStandard(payment.getSourceSnapshotJson());
        var tax = tax(standard);
        if (!Objects.equals(payment.getCompanyId(), tax.companyId())
                || !Objects.equals(first.getStandardId(), standard.getId())
                || !Objects.equals(first.getSubjectId(), standard.getSubjectId()))
            return Mono.error(error("document_source_snapshot_invalid", 409));
        var original = BigDecimal.ZERO;
        var allowed = BigDecimal.ZERO;
        var occupied = BigDecimal.ZERO;
        for (var line : receipts) {
            original = original.add(nonnegative(line.getAmount()));
            allowed = allowed.add(remaining(line));
            occupied = occupied.add(nonnegative(line.getInvoiceOccupiedAmount()));
        }
        if (original.compareTo(nonnegative(payment.getAmount())) != 0)
            return Mono.error(error("document_source_snapshot_invalid", 409));
        var source =
                new Source(
                        key,
                        project,
                        first.getSpaceId(),
                        first.getFullName(),
                        first.getCustomerId(),
                        first.getCustomerName(),
                        first.getSubjectId(),
                        first.getSubjectName(),
                        first.getStandardId(),
                        standard.getStandardName(),
                        first.getCalcStartDate(),
                        first.getCalcEndDate(),
                        tax,
                        allowed.setScale(2),
                        allowed.setScale(2),
                        occupied.setScale(2),
                        rules.available(allowed, occupied),
                        paidAt(payment),
                        null,
                        receipts,
                        payment.getSourceSnapshotJson(),
                        null);
        return AssetsHolder.assertPermission(
                        AssetType.of("space"), first.getSpaceId(), CrudAssetPermission.save)
                .thenReturn(source);
    }

    /** 原存入只用持久快照，客户搬离、标准改名或停用都不替换历史。账户锁和资金动作共用。 */
    private Mono<Source> originalFund(String project, CanonicalSource key, boolean lock) {
        return fundEntries
                .createQuery()
                .where("id", key.id())
                .and("projectId", project)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("document_source_not_found", 404)))
                .flatMap(
                        entry -> {
                            if (!"TOPUP".equals(entry.getAction())
                                    || entry.getAmount().signum() <= 0
                                    || entry.getAmount().compareTo(entry.getCashAmount()) != 0
                                    || !org.jetlinks.pro.property.finance.payment.PaymentRules
                                            .MANUAL_METHODS
                                            .contains(entry.getMethod()))
                                return Mono.error(error("document_source_blocked", 409));
                            var q = new QueryParamEntity();
                            q.setForUpdate(lock);
                            var creditQuery = new QueryParamEntity();
                            creditQuery.setForUpdate(lock);
                            return AssetsHolder.assertPermission(
                                            AssetType.of("space"),
                                            entry.getSpaceId(),
                                            CrudAssetPermission.save)
                                    .then(
                                            fundAccounts
                                                    .createQuery()
                                                    .setParam(q)
                                                    .where("id", entry.getAccountId())
                                                    .and("projectId", project)
                                                    .and("deleted", false)
                                                    .fetchOne())
                                    .switchIfEmpty(
                                            Mono.error(
                                                    error("document_source_snapshot_invalid", 409)))
                                    .flatMap(
                                            account ->
                                                    fundCredits
                                                            .createQuery()
                                                            .setParam(creditQuery)
                                                            .where("id", entry.getId())
                                                            .and("entryId", entry.getId())
                                                            .and("accountId", account.getId())
                                                            .and("projectId", project)
                                                            .and("deleted", false)
                                                            .fetchOne()
                                                            .switchIfEmpty(
                                                                    Mono.error(
                                                                            error(
                                                                                    "document_source_snapshot_invalid",
                                                                                    409)))
                                                            .map(
                                                                    credit ->
                                                                            fundSource(
                                                                                    key, entry,
                                                                                    account,
                                                                                    credit)));
                        });
    }

    /** 不同存入可归属同一账户，账户锁必须按账户ID排序，不能由票据选择顺序决定。 */
    private Mono<Void> lockFundAccounts(String project, List<CanonicalSource> keys, boolean lock) {
        if (!lock) return Mono.empty();
        return Flux.fromIterable(keys)
                .filter(key -> "FUND_TOPUP".equals(key.type()))
                .concatMap(
                        key ->
                                fundEntries
                                        .createQuery()
                                        .where("id", key.id())
                                        .and("projectId", project)
                                        .and("deleted", false)
                                        .fetchOne()
                                        .switchIfEmpty(
                                                Mono.error(
                                                        error("document_source_not_found", 404))))
                .map(FundEntryEntity::getAccountId)
                .distinct()
                .sort()
                .concatMap(
                        id -> {
                            var q = new QueryParamEntity();
                            q.setForUpdate(true);
                            return fundAccounts
                                    .createQuery()
                                    .setParam(q)
                                    .where("id", id)
                                    .and("projectId", project)
                                    .and("deleted", false)
                                    .fetchOne()
                                    .switchIfEmpty(
                                            Mono.error(
                                                    error("document_source_snapshot_invalid", 409)))
                                    .flatMap(
                                            account ->
                                                    AssetsHolder.assertPermission(
                                                            AssetType.of("space"),
                                                            account.getSpaceId(),
                                                            CrudAssetPermission.save));
                        })
                .then();
    }

    private Source fundSource(
            CanonicalSource key,
            FundEntryEntity entry,
            FundAccountEntity account,
            FundCreditEntity credit) {
        var standard = frozenStandard(entry.getDocumentSnapshotJson());
        var tax = tax(standard);
        text(standard.getId());
        text(standard.getSubjectId());
        String type, customer;
        try {
            var snapshot = JSON.parseObject(entry.getDocumentSnapshotJson());
            type = text(snapshot.getString("fundType"));
            customer = text(snapshot.getString("customerId"));
        } catch (RuntimeException invalid) {
            throw error("document_source_snapshot_invalid", 409);
        }
        var amount = nonnegative(entry.getAmount());
        var occupied = nonnegative(credit.getInvoiceOccupiedAmount());
        if (!Set.of("ADVANCE", "DEPOSIT").contains(type)
                || !type.equals(account.getType())
                || !customer.equals(account.getCustomerId())
                || !entry.getSpaceId().equals(account.getSpaceId())
                || !entry.getCompanyId().equals(account.getCompanyId())
                || !entry.getCompanyId().equals(tax.companyId())
                || !standard.getSubjectId().equals(account.getSubjectId())
                || amount.compareTo(nonnegative(credit.getOriginalAmount())) != 0
                || occupied.compareTo(amount) > 0
                || occupied.compareTo(nonnegative(account.getInvoiceOccupiedAmount())) > 0)
            throw error("document_source_snapshot_invalid", 409);
        // 消费、退款、划转后不得把账户其他存入当作本笔可开额度；历史占用仍可准确释放。
        if (amount.compareTo(nonnegative(credit.getRemainingAmount())) != 0)
            throw error("document_source_blocked", 409);
        LocalDateTime paidAt;
        try {
            paidAt = LocalDateTime.parse(entry.getOccurredAt());
        } catch (RuntimeException invalid) {
            throw error("document_source_snapshot_invalid", 409);
        }
        return new Source(
                key,
                entry.getProjectId(),
                entry.getSpaceId(),
                entry.getFullName(),
                customer,
                entry.getCustomerName(),
                standard.getSubjectId(),
                entry.getSubjectName(),
                standard.getId(),
                entry.getStandardName(),
                null,
                null,
                tax,
                amount,
                amount,
                occupied,
                rules.available(amount, occupied),
                paidAt,
                null,
                List.of(),
                entry.getDocumentSnapshotJson(),
                new FundSource(entry, account, credit));
    }

    private static void assertBillLine(ReceivableEntity bill, PaymentAllocationEntity line) {
        if (!"RECEIVABLE".equals(line.getSourceType())
                || !Objects.equals(bill.getId(), line.getReceivableId())
                || !Objects.equals(bill.getSpaceId(), line.getSpaceId())
                || !Objects.equals(bill.getCustomerId(), line.getCustomerId())
                || !Objects.equals(bill.getSubjectId(), line.getSubjectId())
                || !Objects.equals(bill.getStandardId(), line.getStandardId())
                || !Objects.equals(bill.getCalcStartDate(), line.getCalcStartDate())
                || !Objects.equals(bill.getCalcEndDate(), line.getCalcEndDate()))
            throw error("document_source_snapshot_invalid", 409);
    }

    private static void assertTemporaryLine(
            PaymentAllocationEntity first, PaymentAllocationEntity line) {
        if (!"TEMPORARY".equals(line.getSourceType())
                || line.getReceivableId() != null
                || !Objects.equals(first.getSpaceId(), line.getSpaceId())
                || !Objects.equals(first.getCustomerId(), line.getCustomerId())
                || !Objects.equals(first.getStandardId(), line.getStandardId())
                || !Objects.equals(first.getSubjectId(), line.getSubjectId())
                || !Objects.equals(first.getCalcStartDate(), line.getCalcStartDate())
                || !Objects.equals(first.getCalcEndDate(), line.getCalcEndDate()))
            throw error("document_source_snapshot_invalid", 409);
    }

    private BigDecimal remaining(PaymentAllocationEntity line) {
        var remaining =
                nonnegative(line.getAmount()).subtract(nonnegative(line.getRefundedAmount()));
        if (remaining.signum() < 0) throw error("document_source_snapshot_invalid", 409);
        return remaining;
    }

    private Mono<List<PaymentAllocationEntity>> sourceLines(
            String project, String field, String value, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        query.setPaging(true);
        query.setPageSize(1001);
        query.orderBy("id");
        return lines.createQuery()
                .setParam(query)
                .where(field, value)
                .and("projectId", project)
                .and("deleted", false)
                .fetch()
                .collectList()
                .map(
                        rows -> {
                            if (rows.size() > 1000) throw error("document_source_limit", 409);
                            return rows;
                        });
    }

    private Mono<Optional<LocalDateTime>> latestPayment(
            String project, List<PaymentAllocationEntity> rows, BigDecimal paid, Tax tax) {
        if (rows.isEmpty())
            return paid.signum() == 0
                    ? Mono.just(Optional.empty())
                    : Mono.error(error("document_source_snapshot_invalid", 409));
        return Flux.fromIterable(
                        rows.stream()
                                .map(PaymentAllocationEntity::getPaymentId)
                                .distinct()
                                .sorted()
                                .toList())
                .concatMap(
                        id ->
                                payment(project, id)
                                        .doOnNext(
                                                payment -> {
                                                    if (!"RECEIVABLE"
                                                                    .equals(payment.getSourceType())
                                                            || !Objects.equals(
                                                                    tax.companyId(),
                                                                    payment.getCompanyId()))
                                                        throw error(
                                                                "document_source_snapshot_invalid",
                                                                409);
                                                }))
                .map(DocumentSourceService::paidAt)
                .collectList()
                .map(
                        dates ->
                                paid.signum() == 0
                                        ? Optional.empty()
                                        : dates.stream().max(Comparator.naturalOrder()));
    }

    private Mono<PaymentEntity> payment(String project, String id) {
        return payments.createQuery()
                .where("id", id)
                .and("projectId", project)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("document_source_not_found", 404)))
                .doOnNext(
                        row -> {
                            if (!"SUCCEEDED".equals(row.getStatus()))
                                throw error("document_source_blocked", 409);
                        });
    }

    private static StandardEntity frozenStandard(String snapshot) {
        try {
            var standard = SettingRules.standard(snapshot);
            if (standard == null
                    || standard.getCompanyId() == null
                    || standard.getTaxSnapshotJson() == null)
                throw error("document_source_snapshot_invalid", 409);
            return standard;
        } catch (RuntimeException invalid) {
            throw error("document_source_snapshot_invalid", 409);
        }
    }

    private Tax tax(StandardEntity standard) {
        try {
            var snapshot = JSON.parseObject(standard.getTaxSnapshotJson());
            if (snapshot == null
                    || !standard.getCompanyId().equals(snapshot.getString("companyId"))
                    || !Objects.equals(standard.getTaxItemId(), snapshot.getString("taxItemId"))
                    || !Set.of(0, 1).contains(snapshot.getInteger("includingTaxFlag")))
                throw error("document_source_snapshot_invalid", 409);
            var rate = snapshot.getString("taxRate");
            rules.tax("1.00", rate, "FULL", "0");
            return new Tax(
                    standard.getCompanyId(),
                    text(snapshot.getString("companyName")),
                    text(snapshot.getString("sellerTaxNum")),
                    text(snapshot.getString("taxItemId")),
                    text(snapshot.getString("goodsName")),
                    text(snapshot.getString("goodsCode")),
                    rate,
                    snapshot.getInteger("includingTaxFlag"),
                    standard.getTaxSnapshotJson());
        } catch (RuntimeException invalid) {
            throw error("document_source_snapshot_invalid", 409);
        }
    }

    private BigDecimal nonnegative(BigDecimal amount) {
        if (amount == null) throw error("document_source_snapshot_invalid", 409);
        return rules.amount(amount.toPlainString(), true);
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) throw error("document_source_snapshot_invalid", 409);
        return value;
    }

    private static LocalDateTime paidAt(PaymentEntity payment) {
        try {
            return LocalDateTime.parse(payment.getPaidAt());
        } catch (RuntimeException invalid) {
            throw error("document_source_snapshot_invalid", 409);
        }
    }

    public record Reference(String type, String id) {}

    record Tax(
            String companyId,
            String companyName,
            String sellerTaxNum,
            String taxItemId,
            String goodsName,
            String goodsCode,
            String taxRate,
            Integer includingTaxFlag,
            String snapshotJson) {}

    record Source(
            CanonicalSource canonical,
            String projectId,
            String spaceId,
            String fullName,
            String customerId,
            String customerName,
            String subjectId,
            String subjectName,
            String standardId,
            String standardName,
            String startDate,
            String endDate,
            Tax tax,
            BigDecimal allowedAmount,
            BigDecimal paidAmount,
            BigDecimal occupiedAmount,
            BigDecimal availableAmount,
            LocalDateTime paidAt,
            ReceivableEntity bill,
            List<PaymentAllocationEntity> paymentLines,
            String snapshotJson,
            FundSource fund) {}

    record FundSource(FundEntryEntity entry, FundAccountEntity account, FundCreditEntity credit) {}
}
