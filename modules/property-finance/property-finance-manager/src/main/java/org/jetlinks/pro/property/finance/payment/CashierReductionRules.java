package org.jetlinks.pro.property.finance.payment;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import org.jetlinks.pro.property.finance.entity.ReceivableEntity;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;

/** 当次本金减免按分分配，尾差按最大余数补齐；不修改原公式和已计提滞纳金。 */
public final class CashierReductionRules {
    private CashierReductionRules() {}

    static void validate(CashierInput.Reduction reduction) {
        if (reduction == null) return;
        if (!Set.of("AMOUNT", "PERCENT").contains(Objects.toString(reduction.mode(), ""))
                || reduction.value() == null
                || reduction.value().signum() <= 0
                || reduction.value().scale() > 2
                || reduction.value().precision() > 20
                || "PERCENT".equals(reduction.mode())
                        && reduction.value().compareTo(new BigDecimal("100")) > 0
                || reduction.reason() == null
                || reduction.reason().isBlank()
                || reduction.reason().length() > 1000)
            throw error("cashier_reduction_invalid", 400);
    }

    static List<BigDecimal> allocate(
            List<ReceivableEntity> rows, CashierInput.Reduction reduction) {
        validate(reduction);
        if (reduction == null) return Collections.nCopies(rows.size(), BigDecimal.ZERO.setScale(2));
        if (rows.stream().anyMatch(row -> Boolean.TRUE.equals(row.getFinancialClosed())))
            throw error("cashier_reduction_invalid", 409);
        var bases =
                rows.stream()
                        .map(
                                row ->
                                        row.getArrears()
                                                .subtract(row.getFinalLateFeeAmount())
                                                .max(BigDecimal.ZERO)
                                                .movePointRight(2)
                                                .toBigIntegerExact())
                        .toList();
        var total = bases.stream().reduce(BigInteger.ZERO, BigInteger::add);
        var due =
                rows.stream()
                        .map(ReceivableEntity::getArrears)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        var amount =
                "PERCENT".equals(reduction.mode())
                        ? new BigDecimal(total, 2)
                                .multiply(reduction.value())
                                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)
                        : reduction.value();
        var discount = amount.movePointRight(2).toBigIntegerExact();
        if (total.signum() <= 0
                || discount.signum() <= 0
                || discount.compareTo(total) > 0
                || amount.compareTo(due) >= 0) throw error("cashier_reduction_invalid", 400);
        var amounts = new ArrayList<BigInteger>();
        var order = new ArrayList<Integer>();
        for (int index = 0; index < bases.size(); index++) {
            amounts.add(discount.multiply(bases.get(index)).divide(total));
            order.add(index);
        }
        order.sort(
                Comparator.<Integer, BigInteger>comparing(
                                index -> discount.multiply(bases.get(index)).remainder(total))
                        .reversed()
                        .thenComparingInt(Integer::intValue));
        var remaining =
                discount.subtract(amounts.stream().reduce(BigInteger.ZERO, BigInteger::add))
                        .intValueExact();
        for (int index : order) {
            if (remaining == 0) break;
            if (amounts.get(index).compareTo(bases.get(index)) < 0) {
                amounts.set(index, amounts.get(index).add(BigInteger.ONE));
                remaining--;
            }
        }
        return amounts.stream().map(value -> new BigDecimal(value, 2)).toList();
    }
}
