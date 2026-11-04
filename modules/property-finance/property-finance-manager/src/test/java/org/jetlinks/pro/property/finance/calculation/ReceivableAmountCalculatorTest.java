package org.jetlinks.pro.property.finance.calculation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 旧系统代表性金额基线；仅按已确认要求移除比例分摊，一位客户承担整笔费用。 */
class ReceivableAmountCalculatorTest {
    private final ReceivableAmountCalculator calculator = new ReceivableAmountCalculator(
            new ChargeFormulaParser(), new ChargeFormulaEvaluator(), new BillingProrationService());
    private final BillingPeriod september = new BillingPeriod(
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

    @Test
    void partialMonthAndAccrualUseIdenticalDateCoverageWithOrWithoutExplicitMonthVariable() {
        for (String expression : new String[]{"${PRICE}", "${PRICE}*${PERIOD_MONTHS}"}) {
            var context = new ReceivableCalculationContext(september,
                    LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 30), expression,
                    Map.of("PRICE", new BigDecimal("300")),
                    Map.of("PRICE", "单价", "PERIOD_MONTHS", "起止时间（月）"), 2, RoundingMode.HALF_UP);
            var result = calculator.calculate(context, LocalDate.of(2026, 9, 19));
            assertEquals(new BigDecimal("150.00"), result.getContractReceivableAmount());
            assertEquals(new BigDecimal("40.00"), result.getAccruedReceivableAmount());
            assertEquals(new BigDecimal("0.00"), calculator.calculate(context,
                    LocalDate.of(2026, 9, 15)).getAccruedReceivableAmount());
            assertEquals(new BigDecimal("150.00"), calculator.calculate(context,
                    LocalDate.of(2026, 10, 15)).getAccruedReceivableAmount());
        }
    }

    @Test
    void wholeChargeBelongsToSingleCustomerAndRoundingUsesConfiguredPrecision() {
        for (int scale = 0; scale <= 2; scale++) {
            var context = new ReceivableCalculationContext(september,
                    september.getStart(), september.getEnd(), "10/3", Map.of(), Map.of(), scale, RoundingMode.UP);
            var result = calculator.calculate(context, september.getEnd());
            assertEquals(new BigDecimal("10").divide(new BigDecimal("3"), scale, RoundingMode.UP),
                    result.getContractReceivableAmount());
        }
    }

    @Test
    void invalidCoverageIsRejectedAndVariableSnapshotsAreImmutable() {
        var values = new java.util.HashMap<>(Map.of("PRICE", BigDecimal.TEN));
        var context = new ReceivableCalculationContext(september,
                september.getStart(), september.getEnd(), "${PRICE}", values, Map.of("PRICE", "单价"),
                2, RoundingMode.HALF_UP);
        values.put("PRICE", BigDecimal.ZERO);
        assertEquals(new BigDecimal("10.00"), calculator.calculate(context,
                september.getEnd()).getContractReceivableAmount());
        assertThrows(UnsupportedOperationException.class, () -> context.getVariableValues().clear());
        var invalid = new ReceivableCalculationContext(september,
                LocalDate.of(2026, 8, 31), september.getEnd(), "1", Map.of(), Map.of(), 2, RoundingMode.HALF_UP);
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(invalid, september.getEnd()));
    }
}
