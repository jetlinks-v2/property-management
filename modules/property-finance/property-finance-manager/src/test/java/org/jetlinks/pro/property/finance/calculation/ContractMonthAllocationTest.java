package org.jetlinks.pro.property.finance.calculation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;

/** 合同月报分配只读；金额守恒与自然月边界使用固定业务预期。 */
class ContractMonthAllocationTest {
    final ContractMonthAllocation calculator =
            new ContractMonthAllocation(new BillingProrationService());

    BigDecimal amount(String text) {
        return new BigDecimal(text);
    }

    BigDecimal through(String total, String start, String end, String month) {
        return calculator.through(
                amount(total),
                LocalDate.parse(start),
                LocalDate.parse(end),
                YearMonth.parse(month));
    }

    @Test
    void fullCalendarMonthsPreserveLegacyBaselineAndLastMonthRemainder() {
        assertEquals(amount("333.33"), through("1000", "2024-01-01", "2024-03-31", "2024-01"));
        assertEquals(amount("666.66"), through("1000", "2024-01-01", "2024-03-31", "2024-02"));
        assertEquals(amount("1000.00"), through("1000", "2024-01-01", "2024-03-31", "2024-03"));
    }

    @Test
    void partialMonthsUseSharedActualCalendarWeightInsteadOfThirtyDayBranches() {
        assertEquals(amount("258.06"), through("1000", "2024-01-16", "2024-03-15", "2024-01"));
        assertEquals(amount("758.06"), through("1000", "2024-01-16", "2024-03-15", "2024-02"));
        assertEquals(amount("1000.00"), through("1000", "2024-01-16", "2024-03-15", "2024-03"));
    }

    @Test
    void thirtyFirstAndLeapFebruaryRemainPositiveAndIncludeBothBoundaryDays() {
        assertEquals(amount("10.00"), through("330", "2024-01-31", "2024-03-01", "2024-01"));
        assertEquals(amount("320.00"), through("330", "2024-01-31", "2024-03-01", "2024-02"));
        assertEquals(amount("9.21"), through("295", "2023-01-31", "2023-02-28", "2023-01"));
        assertEquals(amount("295.00"), through("295", "2023-01-31", "2023-02-28", "2023-02"));
    }

    @Test
    void outsideCoverageAndSameDayHaveExactZeroOrFullAmount() {
        assertEquals(amount("0.00"), through("0.01", "2024-02-29", "2024-02-29", "2024-01"));
        assertEquals(amount("0.01"), through("0.01", "2024-02-29", "2024-02-29", "2024-02"));
        assertEquals(amount("0.01"), through("0.01", "2024-02-29", "2024-02-29", "2025-01"));
        assertEquals(amount("0.00"), through("0", "2024-02-01", "2024-03-01", "2024-02"));
    }

    @Test
    void refundsAreNegativeMonthlyReceiptsAndOpeningPrepaymentIsNotCountedAgain() {
        var result =
                calculator.statement(
                        amount("300"),
                        LocalDate.parse("2024-01-01"),
                        LocalDate.parse("2024-03-31"),
                        YearMonth.parse("2024-02"),
                        amount("150"),
                        amount("120"));
        assertEquals(amount("0.00"), result.openingReceivable());
        assertEquals(amount("50.00"), result.openingAdvance());
        assertEquals(amount("100.00"), result.currentReceivable());
        assertEquals(amount("-30.00"), result.currentNetReceived());
        assertEquals(amount("80.00"), result.closingReceivable());
        assertEquals(amount("0.00"), result.closingAdvance());
        identity(result);
    }

    @Test
    void perBillBalancesDoNotUseOtherBillPrepaymentsToHideArrears() {
        var start = LocalDate.parse("2024-01-01");
        var end = LocalDate.parse("2024-02-29");
        var month = YearMonth.parse("2024-01");
        var debt = calculator.statement(amount("200"), start, end, month, amount("0"), amount("0"));
        var advance =
                calculator.statement(amount("200"), start, end, month, amount("0"), amount("200"));
        assertEquals(amount("100.00"), debt.closingReceivable().add(advance.closingReceivable()));
        assertEquals(amount("100.00"), debt.closingAdvance().add(advance.closingAdvance()));
        identity(debt);
        identity(advance);
    }

    @Test
    void monthlyAmountsAreMonotonicAndConserveExactLargeTotalAcrossYears() {
        var start = LocalDate.parse("2023-12-31");
        var end = LocalDate.parse("2025-02-17");
        for (String total : new String[] {"0.01", "9999999999999999.99", "999.99"}) {
            BigDecimal previous = amount("0.00"), sum = amount("0.00");
            for (var month = YearMonth.from(start);
                    !month.isAfter(YearMonth.from(end));
                    month = month.plusMonths(1)) {
                var current = calculator.through(amount(total), start, end, month);
                assertTrue(current.compareTo(previous) >= 0);
                sum = sum.add(current.subtract(previous));
                previous = current;
            }
            assertEquals(amount(total), sum);
        }
    }

    @Test
    void invalidAmountsOrDatesAreRejectedWithoutRoundingInput() {
        for (String total : new String[] {"-1", "1.001"})
            assertThrows(
                    IllegalArgumentException.class,
                    () -> through(total, "2024-01-01", "2024-02-29", "2024-01"));
        assertThrows(
                IllegalArgumentException.class,
                () -> through("1", "2024-02-02", "2024-02-01", "2024-02"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        calculator.through(
                                null,
                                LocalDate.parse("2024-01-01"),
                                LocalDate.parse("2024-01-31"),
                                YearMonth.parse("2024-01")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        calculator.statement(
                                amount("1"),
                                LocalDate.parse("2024-01-01"),
                                LocalDate.parse("2024-02-29"),
                                YearMonth.parse("2024-01"),
                                amount("-0.01"),
                                amount("0")));
    }

    void identity(ContractMonthAllocation.Amounts value) {
        assertEquals(
                value.openingReceivable()
                        .subtract(value.openingAdvance())
                        .add(value.currentReceivable())
                        .subtract(value.currentNetReceived()),
                value.closingReceivable().subtract(value.closingAdvance()));
    }
}
