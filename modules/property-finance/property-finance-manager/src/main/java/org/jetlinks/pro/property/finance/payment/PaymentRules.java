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
        for (var row : originals) {
            if (!"APPROVED".equals(row.getReviewState()))
                throw error("cashier_review_required", 409);
            var company = SettingRules.standard(row.getSnapshotJson()).getCompanyId();
            // 旧历史账单无公司快照时沿用项目公司配置；已有快照绝不可任意换公司收款。
            if (company != null && !company.isBlank() && !company.equals(input.getCompanyId()))
                throw error("cashier_company_mismatch", 409);
        }
        return calculate(originals, input, date);
    }

    /** 内部余额结转入口，人工收款接口仍只接受MANUAL_METHODS。 */
    public Result prepareBalance(
            List<ReceivableEntity> originals, CashierInput input, LocalDate date) {
        validate(input, date, Set.of("ADVANCE", "DEPOSIT"));
        if (input.getReduction() != null) throw error("cashier_reduction_invalid", 400);
        return calculate(originals, input, date);
    }

    private Result calculate(List<ReceivableEntity> originals, CashierInput input, LocalDate date) {
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
            rows.add(row);
        }
        var reductions = CashierReductionRules.allocate(rows, input.getReduction());
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var discount = reductions.get(index);
            row.setFeeReductionAmount(row.getFeeReductionAmount().add(discount));
            row.setActualReceivableAmount(row.getActualReceivableAmount().subtract(discount));
            row.setArrears(row.getArrears().subtract(discount));
            var amount = itemMoney(input.getItems().get(index).amount(), input);
            if (amount.signum() == 0 && discount.signum() == 0) throw error("cashier_invalid", 400);
            if (amount.compareTo(row.getArrears()) > 0) throw error("cashier_exceeds_balance", 409);
            row.setPaidAmount(row.getPaidAmount().add(amount));
            row.setArrears(row.getActualReceivableAmount().subtract(row.getPaidAmount()));
            row.setPaidDate(LocalDateTime.parse(input.getPaidAt()).toLocalDate().toString());
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
        validate(input, date, MANUAL_METHODS);
    }

    private static void validate(CashierInput input, LocalDate date, Set<String> methods) {
        validateFunding(input, date, methods);
        if (input.getItems() == null || input.getItems().isEmpty() || input.getItems().size() > 100)
            throw error("cashier_invalid", 400);
        CashierReductionRules.validate(input.getReduction());
        var ids = new HashSet<String>();
        for (var item : input.getItems()) {
            if (item == null
                    || blank(item.id())
                    || item.id().length() > 64
                    || item.version() == null
                    || item.version() < 0
                    || !ids.add(item.id())) throw error("cashier_invalid", 400);
            itemMoney(item.amount(), input);
        }
    }

    /** 人工支付字段校验由应收和临时收款共用，不要求伪造账单。 */
    public static void validateFunding(CashierInput input, LocalDate date) {
        validateFunding(input, date, MANUAL_METHODS);
    }

    private static void validateFunding(CashierInput input, LocalDate date, Set<String> methods) {
        if (input == null
                || date == null
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
        var tenders = new HashSet<String>();
        for (var tender : input.getTenders()) {
            if (tender == null
                    || !methods.contains(Objects.toString(tender.method(), ""))
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
    static List<Allocation> allocate(CashierInput input) {
        var result = new ArrayList<Allocation>();
        int tenderIndex = 0;
        var remaining = money(input.getTenders().get(0).amount());
        for (var item : input.getItems()) {
            var unpaid = itemMoney(item.amount(), input);
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
                                                itemMoney(item.amount(), input).toPlainString()))
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
        var fields =
                new ArrayList<Object>(
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
        // 已保存的普通收款恢复请求保持原指纹，仅有减免时扩展契约。
        if (input.getReduction() != null)
            fields.add(
                    List.of(
                            input.getReduction().mode(),
                            input.getReduction().value().toPlainString(),
                            input.getReduction().reason().trim()));
        return JSON.toJSONString(fields);
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

    private static BigDecimal itemMoney(BigDecimal value, CashierInput input) {
        if (input.getReduction() != null
                && value != null
                && value.signum() == 0
                && value.scale() <= 2) return BigDecimal.ZERO.setScale(2);
        return money(value);
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
