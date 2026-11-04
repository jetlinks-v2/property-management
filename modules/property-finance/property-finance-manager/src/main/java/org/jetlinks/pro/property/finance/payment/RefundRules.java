package org.jetlinks.pro.property.finance.payment;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import org.jetlinks.pro.property.finance.entity.PaymentAllocationEntity;

import java.math.*;
import java.time.*;
import java.util.*;

/** 退款以剩余可退金额为权重，全部用整数分运算；同余数按输入顺序补分。 */
public final class RefundRules {
    private RefundRules() {}

    public static void validate(RefundInput input, LocalDate date) {
        validate(input, date, PaymentRules.MANUAL_METHODS);
    }

    public static void validateBalance(RefundInput input, LocalDate date) {
        validate(input, date, FundRules.TYPES);
    }

    public static void validateToAdvance(RefundInput input, LocalDate date) {
        validate(input, date, Set.of("TO_ADVANCE"));
    }

    private static void validate(RefundInput input, LocalDate date, Set<String> methods) {
        if (input == null
                || date == null
                || input.getItems() == null
                || input.getItems().isEmpty()
                || input.getItems().size() > 100
                || blank(input.getCompanyId())
                || input.getCompanyId().length() > 64
                || blank(input.getReceiverName())
                || input.getReceiverName().length() > 128
                || blank(input.getReason())
                || input.getReason().length() > 1000
                || !methods.contains(Objects.toString(input.getMethod(), ""))
                || "1".equals(input.getMethod()) != blank(input.getRecordedId())
                || tooLong(input.getRecordedId(), 64)
                || tooLong(input.getAttachmentFileId(), 64)
                || tooLong(input.getAttachmentName(), 255)
                || blank(input.getAttachmentFileId()) != blank(input.getAttachmentName()))
            throw error("refund_invalid", 400);
        money(input.getAmount());
        var seen = new HashSet<String>();
        for (var item : input.getItems()) {
            if (item == null
                    || blank(item.id())
                    || item.id().length() > 64
                    || item.version() == null
                    || item.version() < 0
                    || !seen.add(item.id())) throw error("refund_invalid", 400);
        }
        try {
            if (LocalDateTime.parse(input.getRefundedAt()).toLocalDate().isAfter(date))
                throw error("refund_invalid", 400);
        } catch (java.time.format.DateTimeParseException | NullPointerException failure) {
            throw error("refund_invalid", 400);
        }
    }

    public static List<Share> allocate(
            RefundInput input, List<PaymentAllocationEntity> sources, LocalDate date) {
        return allocate(input, sources, date, PaymentRules.MANUAL_METHODS);
    }

    public static List<Share> allocateBalance(
            RefundInput input, List<PaymentAllocationEntity> sources, LocalDate date) {
        return allocate(input, sources, date, FundRules.TYPES);
    }

    public static List<Share> allocateToAdvance(
            RefundInput input, List<PaymentAllocationEntity> sources, LocalDate date) {
        return allocate(input, sources, date, Set.of("TO_ADVANCE"));
    }

    private static List<Share> allocate(
            RefundInput input,
            List<PaymentAllocationEntity> sources,
            LocalDate date,
            Set<String> methods) {
        validate(input, date, methods);
        var indexed = new HashMap<String, PaymentAllocationEntity>();
        sources.forEach(source -> indexed.put(source.getId(), source));
        var remaining = new ArrayList<BigInteger>();
        for (var item : input.getItems()) {
            var row = indexed.get(item.id());
            if (row == null) throw error("payment_not_found", 404);
            if (!Objects.equals(item.version(), row.getVersion()))
                throw error("version_conflict", 409);
            if (Boolean.TRUE.equals(row.getDeleted())
                    || row.getAmount() == null
                    || row.getRefundedAmount() == null
                    || row.getRefundedAmount().signum() < 0)
                throw error("refund_source_blocked", 409);
            var available = row.getAmount().subtract(row.getRefundedAmount());
            if (available.signum() <= 0) throw error("refund_exceeds_balance", 409);
            remaining.add(available.movePointRight(2).toBigIntegerExact());
        }
        var total = remaining.stream().reduce(BigInteger.ZERO, BigInteger::add);
        var requested = money(input.getAmount()).movePointRight(2).toBigIntegerExact();
        if (requested.compareTo(total) > 0) throw error("refund_exceeds_balance", 409);
        var amounts = new ArrayList<BigInteger>();
        var remainders = new ArrayList<BigInteger>();
        for (var balance : remaining) {
            var division = requested.multiply(balance).divideAndRemainder(total);
            amounts.add(division[0]);
            remainders.add(division[1]);
        }
        int cents =
                requested
                        .subtract(amounts.stream().reduce(BigInteger.ZERO, BigInteger::add))
                        .intValueExact();
        var order = new ArrayList<Integer>();
        for (int i = 0; i < amounts.size(); i++) order.add(i);
        order.sort(Comparator.<Integer, BigInteger>comparing(remainders::get).reversed());
        for (int i = 0; i < cents; i++) {
            int index = order.get(i);
            amounts.set(index, amounts.get(index).add(BigInteger.ONE));
        }
        var result = new ArrayList<Share>();
        for (int i = 0; i < amounts.size(); i++) {
            if (amounts.get(i).signum() > 0)
                result.add(
                        new Share(input.getItems().get(i).id(), new BigDecimal(amounts.get(i), 2)));
        }
        return List.copyOf(result);
    }

    public static BigDecimal money(BigDecimal value) {
        if (value == null
                || value.signum() <= 0
                || value.scale() > 2
                || value.precision() - value.scale() > 18) throw error("refund_invalid", 400);
        return value.setScale(2);
    }

    public static String fingerprint(RefundInput input) {
        var items =
                input.getItems().stream().map(item -> List.of(item.id(), item.version())).toList();
        return PaymentRules.digest(
                JSON.toJSONString(
                        List.of(
                                items,
                                money(input.getAmount()).toPlainString(),
                                input.getCompanyId(),
                                input.getMethod(),
                                Objects.toString(input.getRecordedId(), ""),
                                input.getRefundedAt(),
                                input.getReceiverName().trim(),
                                input.getReason().trim(),
                                Objects.toString(input.getAttachmentFileId(), ""),
                                Objects.toString(input.getAttachmentName(), ""))));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean tooLong(String value, int length) {
        return value != null && value.length() > length;
    }

    public record Share(String sourceId, BigDecimal amount) {}
}
