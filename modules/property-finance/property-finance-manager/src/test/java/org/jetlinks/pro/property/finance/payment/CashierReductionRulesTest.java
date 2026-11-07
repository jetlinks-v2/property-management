package org.jetlinks.pro.property.finance.payment;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.pro.property.finance.entity.ReceivableEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

class CashierReductionRulesTest {
    private ReceivableEntity bill(String amount, String late) {
        var row = new ReceivableEntity();
        row.setArrears(new BigDecimal(amount));
        row.setFinalLateFeeAmount(new BigDecimal(late));
        return row;
    }

    @Test
    void remainderIsAllocatedByCentAndPercentageRoundsHalfUp() {
        var rows = List.of(bill("0.01", "0"), bill("0.01", "0"), bill("0.01", "0"));
        var input = new CashierInput.Reduction("AMOUNT", new BigDecimal("0.02"), "尾差");
        assertEquals(List.of(new BigDecimal("0.01"), new BigDecimal("0.01"), new BigDecimal("0.00")),
                CashierReductionRules.allocate(rows, input));
        assertEquals(List.of(new BigDecimal("0.51")), CashierReductionRules.allocate(
                List.of(bill("1.01", "0")), new CashierInput.Reduction("PERCENT", new BigDecimal("50"), "比例")));
    }

    @Test
    void reductionCannotConsumeLateFeesOrZeroTheEntireCollection() {
        assertThrows(RuntimeException.class, () -> CashierReductionRules.allocate(
                List.of(bill("10", "9")), new CashierInput.Reduction("AMOUNT", new BigDecimal("2"), "超本金")));
        assertThrows(RuntimeException.class, () -> CashierReductionRules.allocate(
                List.of(bill("10", "0")), new CashierInput.Reduction("PERCENT", new BigDecimal("100"), "全免")));
        assertThrows(RuntimeException.class, () -> CashierReductionRules.validate(
                new CashierInput.Reduction("PERCENT", new BigDecimal("100.01"), "超限")));
        assertThrows(RuntimeException.class, () -> CashierReductionRules.validate(
                new CashierInput.Reduction("AMOUNT", new BigDecimal("1"), " ")));
    }
}
