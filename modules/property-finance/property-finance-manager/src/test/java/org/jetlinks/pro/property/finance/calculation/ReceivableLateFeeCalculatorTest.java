package org.jetlinks.pro.property.finance.calculation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReceivableLateFeeCalculatorTest {

    private final ReceivableLateFeeCalculator calculator = new ReceivableLateFeeCalculator();

    @Test
    void dailyPercentageStartsOnTheDayAfterTheDueDate() {
        ReceivableSnapshot snapshot = snapshot("PERCENTAGE", "DAILY", "CURRENT_RECEIVABLE",
                "1", "1000", "600", false, false, null);

        ReceivableLateFeeCalculator.LateFeeResult result = calculator.calculateLateFee(
                snapshot, LocalDate.of(2026, 9, 3));

        assertEquals(3L, result.getOverdueUnits());
        assertEquals(new BigDecimal("30.00"), result.getAccruedLateFee());
    }

    @Test
    void monthlyFixedAmountUsesCompleteAnniversaryMonths() {
        ReceivableSnapshot snapshot = snapshot("FIXED", "MONTHLY", "CURRENT_RECEIVABLE",
                "25", "1000", "600", false, false, null);

        assertEquals(new BigDecimal("0.00"), calculator.calculateLateFee(
                snapshot, LocalDate.of(2026, 9, 30)).getAccruedLateFee());
        assertEquals(new BigDecimal("25.00"), calculator.calculateLateFee(
                snapshot, LocalDate.of(2026, 10, 1)).getAccruedLateFee());
    }

    @Test
    void monthlyAnniversaryUsesTheLastDayWhenTheStartDayDoesNotExist() {
        ReceivableSnapshot snapshot = snapshot(LocalDate.of(2026, 1, 30),
                "FIXED", "MONTHLY", "CURRENT_RECEIVABLE",
                "25", "1000", "600", false, false, null);

        assertEquals(new BigDecimal("0.00"), calculator.calculateLateFee(
                snapshot, LocalDate.of(2026, 2, 27)).getAccruedLateFee());
        assertEquals(new BigDecimal("25.00"), calculator.calculateLateFee(
                snapshot, LocalDate.of(2026, 2, 28)).getAccruedLateFee());
    }

    @Test
    void accumulatedOverdueBaseAndPaidDateAreHonoured() {
        ReceivableSnapshot snapshot = snapshot("PERCENTAGE", "DAILY", "ACCUMULATED_OVERDUE",
                "1", "1000", "600", true, false, LocalDate.of(2026, 9, 2));

        ReceivableLateFeeCalculator.LateFeeResult result = calculator.calculateLateFee(
                snapshot, LocalDate.of(2026, 9, 19));

        assertEquals(2L, result.getOverdueUnits());
        assertEquals(new BigDecimal("12.00"), result.getAccruedLateFee());
    }

    @Test
    void disabledOrLockedLateFeeDoesNotGrowAndInvalidRuleFailsVisibly() {
        assertEquals(new BigDecimal("0.00"), calculator.calculateLateFee(
                snapshot("PERCENTAGE", "DAILY", "CURRENT_RECEIVABLE", "1", "1000", "600",
                        false, true, null), LocalDate.of(2026, 9, 19)).getAccruedLateFee());
        assertEquals(new BigDecimal("0.00"), calculator.calculateLateFee(
                ReceivableSnapshot.builder().lateFeeEnabled(false).moneyScale(2).build(),
                LocalDate.of(2026, 9, 19)).getAccruedLateFee());
        assertThrows(IllegalArgumentException.class, () -> calculator.calculateLateFee(
                snapshot("UNKNOWN", "DAILY", "CURRENT_RECEIVABLE", "1", "1000", "600",
                        false, false, null), LocalDate.of(2026, 9, 19)));
    }

    private ReceivableSnapshot snapshot(String calculationType, String periodType, String baseType,
                                        String amountOrRate, String current, String arrears,
                                        boolean paid, boolean locked, LocalDate paidDate) {
        return snapshot(LocalDate.of(2026, 8, 31), calculationType, periodType, baseType,
                amountOrRate, current, arrears, paid, locked, paidDate);
    }

    private ReceivableSnapshot snapshot(LocalDate dueDate, String calculationType,
                                        String periodType, String baseType,
                                        String amountOrRate, String current, String arrears,
                                        boolean paid, boolean locked, LocalDate paidDate) {
        return ReceivableSnapshot.builder()
                .receivableId("11")
                .dueDate(dueDate)
                .lateFeeEnabled(true)
                .lateFeeCalculationType(calculationType)
                .lateFeePeriodType(periodType)
                .lateFeeBaseType(baseType)
                .lateFeeAmountOrRate(new BigDecimal(amountOrRate))
                .currentReceivable(new BigDecimal(current))
                .accumulatedOverdue(new BigDecimal(arrears))
                .paid(paid)
                .lateFeeLocked(locked)
                .paidDate(paidDate)
                .moneyScale(2)
                .build();
    }
}
