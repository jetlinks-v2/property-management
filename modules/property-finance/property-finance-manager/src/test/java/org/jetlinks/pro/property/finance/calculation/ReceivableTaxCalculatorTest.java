package org.jetlinks.pro.property.finance.calculation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReceivableTaxCalculatorTest {

    private final ReceivableTaxCalculator calculator = new ReceivableTaxCalculator();

    @Test
    void calculatesFullCollectionTaxFromTaxInclusiveAmount() {
        ReceivableTaxCalculator.TaxResult result = calculator.calculate(
                new BigDecimal("100.00"), new BigDecimal("0.02"), "FULL", BigDecimal.ZERO);

        assertEquals(new BigDecimal("1.960784"), result.getBaseOutputTaxAmount());
        assertEquals(new BigDecimal("0.000000"), result.getDifferenceAmount());
        assertEquals(new BigDecimal("1.960784"), result.getOutputTaxAmount());
        assertEquals(new BigDecimal("98.039216"), result.getIncomeExcludingTax());
    }

    @Test
    void calculatesDifferentialTaxFromReceivableLessDifference() {
        ReceivableTaxCalculator.TaxResult result = calculator.calculate(
                new BigDecimal("100.00"), new BigDecimal("0.02"),
                "DIFFERENTIAL", new BigDecimal("20.00"));

        assertEquals(new BigDecimal("1.960784"), result.getBaseOutputTaxAmount());
        assertEquals(new BigDecimal("20.000000"), result.getDifferenceAmount());
        assertEquals(new BigDecimal("1.568627"), result.getOutputTaxAmount());
        assertEquals(new BigDecimal("98.431373"), result.getIncomeExcludingTax());
    }

    @Test
    void rejectsDifferenceForFullAndInvalidDifferentialDifference() {
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(
                new BigDecimal("100.00"), new BigDecimal("0.02"), "FULL", new BigDecimal("0.01")));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(
                new BigDecimal("100.00"), new BigDecimal("0.02"),
                "DIFFERENTIAL", new BigDecimal("100.00")));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(
                new BigDecimal("100.00"), new BigDecimal("0.02"),
                "DIFFERENTIAL", new BigDecimal("-0.01")));
    }

    @Test
    void incomeAlwaysEqualsActualReceivableMinusOutputTax() {
        ReceivableTaxCalculator.TaxResult result = calculator.calculate(
                new BigDecimal("120.00"), new BigDecimal("0.02"),
                "DIFFERENTIAL", new BigDecimal("20.00"));

        assertEquals(new BigDecimal("1.960784"), result.getOutputTaxAmount());
        assertEquals(new BigDecimal("118.039216"), result.getIncomeExcludingTax());
    }
}
