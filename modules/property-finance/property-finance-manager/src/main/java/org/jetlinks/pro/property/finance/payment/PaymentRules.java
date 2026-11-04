package org.jetlinks.pro.property.finance.payment;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.jetlinks.pro.property.finance.entity.ReceivableEntity;
import org.jetlinks.pro.property.finance.service.ReceivableCalculationService;
import org.jetlinks.pro.property.finance.setting.BillingTaskKind;
import org.jetlinks.pro.property.finance.setting.SettingRules;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** 试算和正式入账共用同一纯规则：先按今日刷新，再校验可收额，保留原账单快照。 */
@Component
@RequiredArgsConstructor
public class PaymentRules {
    public static final Set<String> MANUAL_METHODS = Set.of("1", "2", "3", "4");
    private final ReceivableCalculationService calculation;

    public Result prepare(List<ReceivableEntity> originals, CashierInput input, LocalDate date) {
        validate(input, date);
        var indexed = new HashMap<String, ReceivableEntity>();
        originals.forEach(row -> indexed.put(row.getId(), row));
        var rows = new ArrayList<ReceivableEntity>();
        var seen = new HashSet<String>();
        var total = BigDecimal.ZERO;
        for (var item : input.getItems()) {
            if (item == null || item.id() == null || !seen.add(item.id()) || item.version() == null)
                throw error("cashier_invalid", 400);
            var original = indexed.get(item.id());
            if (original == null) throw error("receivable_not_found", 404);
            if (!Objects.equals(item.version(), original.getVersion()))
                throw error("version_conflict", 409);
            assertCollectable(original);
            if (original.getCalculatedAsOfDate() != null
                    && date.isBefore(LocalDate.parse(original.getCalculatedAsOfDate())))
                throw error("receivable_date_regression", 409);
            var row = new ReceivableEntity();
            BeanUtils.copyProperties(original, row);
            // 财务月结锁定原应收金额；旧收银仍允许收历史欠费，缴款进入本次收款流水。
            if (!Boolean.TRUE.equals(row.getFinancialClosed()))
                calculation.calculate(
                        row,
                        SettingRules.standard(row.getSnapshotJson()),
                        date,
                        BillingTaskKind.ALL);
            var amount = money(item.amount());
            if (amount.compareTo(row.getArrears()) > 0) throw error("cashier_exceeds_balance", 409);
            row.setPaidAmount(row.getPaidAmount().add(amount));
            row.setArrears(row.getActualReceivableAmount().subtract(row.getPaidAmount()));
            row.setPaidDate(LocalDateTime.parse(input.getPaidAt()).toLocalDate().toString());
            rows.add(row);
            total = total.add(amount);
        }
        money(total);
        var tenderTotal =
                input.getTenders().stream()
                        .map(tender -> money(tender.amount()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(tenderTotal) != 0) throw error("cashier_total_mismatch", 400);
        var token = hash(JSON.toJSONString(List.of(date.toString(), canonical(input), rows)));
        return new Result(
                List.copyOf(rows),
                allocate(input),
                total.setScale(2).toPlainString(),
                date.toString(),
                token);
    }

    public static void validate(CashierInput input, LocalDate date) {
        if (input == null
                || date == null
                || input.getItems() == null
                || input.getItems().isEmpty()
                || input.getItems().size() > 100
                || input.getTenders() == null
                || input.getTenders().isEmpty()
                || input.getTenders().size() > 10
                || blank(input.getCompanyId())
                || input.getCompanyId().length() > 64
                || blank(input.getPayerName())
                || input.getPayerName().length() > 128
                || tooLong(input.getPayerRelationshipId(), 64)
                || tooLong(input.getRemark(), 1000)
                || tooLong(input.getAttachmentName(), 255)
                || tooLong(input.getAttachmentFileId(), 64)) throw error("cashier_invalid", 400);
        try {
            if (LocalDateTime.parse(input.getPaidAt()).toLocalDate().isAfter(date))
                throw error("cashier_invalid", 400);
        } catch (java.time.format.DateTimeParseException | NullPointerException e) {
            throw error("cashier_invalid", 400);
        }
        if (blank(input.getAttachmentFileId()) != blank(input.getAttachmentName()))
            throw error("cashier_invalid", 400);
        var ids = new HashSet<String>();
        for (var item : input.getItems()) {
            if (item == null
                    || blank(item.id())
                    || item.id().length() > 64
                    || item.version() == null
                    || item.version() < 0
                    || !ids.add(item.id())) throw error("cashier_invalid", 400);
            money(item.amount());
        }
        var tenders = new HashSet<String>();
        for (var tender : input.getTenders()) {
            if (tender == null
                    || !MANUAL_METHODS.contains(Objects.toString(tender.method(), ""))
                    || !"1".equals(tender.method()) && blank(tender.recordedId())
                    || "1".equals(tender.method()) && !blank(tender.recordedId())
                    || tooLong(tender.recordedId(), 64)
                    || !tenders.add(
                            tender.method() + ":" + Objects.toString(tender.recordedId(), "")))
                throw error("cashier_invalid", 400);
            money(tender.amount());
        }
    }

    private static void assertCollectable(ReceivableEntity row) {
        if (Boolean.TRUE.equals(row.getDeleted())
                || Boolean.TRUE.equals(row.getSplit())
                || Boolean.TRUE.equals(row.getFrozen())
                || Boolean.TRUE.equals(row.getPaymentLocked())
                || Boolean.TRUE.equals(row.getBankCollectionLocked())
                || Boolean.TRUE.equals(row.getChargeLocked())
                || Boolean.TRUE.equals(row.getDiscountLocked())
                || positive(row.getPaymentOccupiedAmount())
                || positive(row.getPaidAmount()) && !positive(row.getArrears()))
            throw error("cashier_bill_blocked", 409);
    }

    /** 沿旧TypeDutyAddDTO逐笔消耗支付额度，直接按分取最小值，不按浮点比例分配。 */
    private static List<Allocation> allocate(CashierInput input) {
        var result = new ArrayList<Allocation>();
        int tenderIndex = 0;
        var remaining = money(input.getTenders().get(0).amount());
        for (var item : input.getItems()) {
            var unpaid = money(item.amount());
            while (unpaid.signum() > 0) {
                var tender = input.getTenders().get(tenderIndex);
                var amount = unpaid.min(remaining);
                result.add(
                        new Allocation(
                                item.id(),
                                tenderIndex,
                                tender.method(),
                                tender.recordedId(),
                                amount));
                unpaid = unpaid.subtract(amount);
                remaining = remaining.subtract(amount);
                if (remaining.signum() == 0 && tenderIndex + 1 < input.getTenders().size())
                    remaining = money(input.getTenders().get(++tenderIndex).amount());
            }
        }
        return List.copyOf(result);
    }

    public static String fingerprint(CashierInput input) {
        return hash(canonical(input));
    }

    public static String digest(String value) {
        return hash(value);
    }

    private static String canonical(CashierInput input) {
        // Fastjson不能正确序列化record访问器，按显式字段构造幂等输入，不让所有明细退化成空对象。
        var items =
                input.getItems().stream()
                        .map(
                                item ->
                                        List.of(
                                                item.id(),
                                                item.version(),
                                                money(item.amount()).toPlainString()))
                        .toList();
        var tenders =
                input.getTenders().stream()
                        .map(
                                tender ->
                                        List.of(
                                                tender.method(),
                                                Objects.toString(tender.recordedId(), ""),
                                                money(tender.amount()).toPlainString()))
                        .toList();
        return JSON.toJSONString(
                List.of(
                        input.getCompanyId(),
                        input.getPayerName().trim(),
                        Objects.toString(input.getPayerRelationshipId(), ""),
                        input.getPaidAt(),
                        Objects.toString(input.getRemark(), ""),
                        Objects.toString(input.getAttachmentName(), ""),
                        Objects.toString(input.getAttachmentFileId(), ""),
                        items,
                        tenders));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null
                || value.signum() <= 0
                || value.scale() > 2
                || value.precision() - value.scale() > 18) throw error("cashier_invalid", 400);
        return value.setScale(2);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    public record Allocation(
            String receivableId,
            int tenderIndex,
            String method,
            String recordedId,
            BigDecimal amount) {}

    public record Result(
            List<ReceivableEntity> rows,
            List<Allocation> allocations,
            String total,
            String businessDate,
            String token) {}
}
