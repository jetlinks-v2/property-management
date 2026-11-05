package org.jetlinks.pro.property.finance.calculation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.jetlinks.pro.property.finance.calculation.ChargeFormulaParser.ParsedFormula;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

class BillingProrationServiceTest {

    private final ChargeFormulaParser parser = new ChargeFormulaParser();
    private final ChargeFormulaEvaluator evaluator = new ChargeFormulaEvaluator();
    private final BillingProrationService proration = new BillingProrationService();

    @Test
    void timeVariableFormulaIsNotProratedTwice() {
        ParsedFormula formula =
                parser.parse(
                        "${STANDARD_PRICE}*${PERIOD_MONTHS}",
                        Set.of("STANDARD_PRICE", "PERIOD_MONTHS"));
        BigDecimal months =
                proration.monthFraction(LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 30));
        BigDecimal amount =
                evaluator.evaluate(
                        formula,
                        Map.of("STANDARD_PRICE", new BigDecimal("300"), "PERIOD_MONTHS", months),
                        2,
                        RoundingMode.HALF_UP);
        BillingPeriod september =
                new BillingPeriod(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertEquals(new BigDecimal("0.500000000000"), months);
        assertEquals(new BigDecimal("150.00"), amount);
        assertEquals(
                BigDecimal.ONE,
                proration.factor(
                        formula, september, LocalDate.of(2026, 9, 16), september.getEnd()));
    }

    @Test
    void formulaWithoutTimeVariableUsesCoveredDayRatio() {
        ParsedFormula formula =
                parser.parse(
                        "${STANDARD_PRICE}*${BILLING_AREA}",
                        Set.of("STANDARD_PRICE", "BILLING_AREA"));
        BillingPeriod september =
                new BillingPeriod(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertEquals(
                new BigDecimal("0.500000000000"),
                proration.factor(
                        formula, september, LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 30)));
    }

    @Test
    void monthFractionSumsCoverageAcrossLeapMonthBoundary() {
        assertEquals(
                new BigDecimal("1.001112347052"),
                proration.monthFraction(LocalDate.of(2024, 2, 15), LocalDate.of(2024, 3, 15)));
    }

    @Test
    void naturalMonthRatioHandlesThirtyFirstAndLongFullCalendarRangeExactly() {
        assertEquals(
                new BigDecimal("1.064516129032"),
                proration.monthFraction(LocalDate.of(2024, 1, 31), LocalDate.of(2024, 3, 1)));
        assertEquals(
                new BigDecimal("3612.000000000000"),
                proration.monthFraction(LocalDate.of(1900, 1, 1), LocalDate.of(2200, 12, 31)));
    }

    @Test
    void exactMonthWeightsRemainAdditiveAcrossLeapAndNonLeapBoundaries() {
        for (int year : new int[] {1900, 2000, 2023, 2024, 2100}) {
            for (int month = 1; month <= 12; month++) {
                var first = LocalDate.of(year, month, 1);
                var middle = first.plusDays(13);
                var last = first.plusMonths(2).minusDays(1);
                assertEquals(
                        0,
                        proration
                                .monthWeight(first, last)
                                .compareTo(
                                        proration
                                                .monthWeight(first, middle)
                                                .add(
                                                        proration.monthWeight(
                                                                middle.plusDays(1), last))));
            }
        }
    }

    @Test
    void rejectsCoverageOutsideNaturalPeriod() {
        BillingPeriod september =
                new BillingPeriod(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        ParsedFormula formula = parser.parse("${STANDARD_PRICE}", Set.of("STANDARD_PRICE"));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        proration.factor(
                                formula,
                                september,
                                LocalDate.of(2026, 8, 31),
                                LocalDate.of(2026, 9, 30)));
    }
}
