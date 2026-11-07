package org.jetlinks.pro.property.finance.calculation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NaturalBillingPeriodServiceTest {

    private final NaturalBillingPeriodService service = new NaturalBillingPeriodService();

    @Test
    void monthlySplitReturnsFullNaturalPeriodsIncludingLeapFebruary() {
        List<BillingPeriod> periods = service.split("MONTHLY",
                LocalDate.of(2024, 1, 15), LocalDate.of(2024, 3, 10));

        assertEquals(Arrays.asList(
                period("2024-01-01", "2024-01-31"),
                period("2024-02-01", "2024-02-29"),
                period("2024-03-01", "2024-03-31")), periods);
    }

    @Test
    void quarterlySplitCrossesYearAtNaturalQuarterBoundary() {
        List<BillingPeriod> periods = service.split("QUARTERLY",
                LocalDate.of(2025, 12, 15), LocalDate.of(2026, 4, 1));

        assertEquals(Arrays.asList(
                period("2025-10-01", "2025-12-31"),
                period("2026-01-01", "2026-03-31"),
                period("2026-04-01", "2026-06-30")), periods);
    }

    @Test
    void sameDayStillBelongsToOneNaturalPeriod() {
        assertEquals(List.of(period("2026-09-01", "2026-09-30")),
                service.split("MONTHLY", LocalDate.of(2026, 9, 19), LocalDate.of(2026, 9, 19)));
    }

    @Test
    void rejectsUnsupportedSettlementMethodAndReversedRange() {
        assertThrows(IllegalArgumentException.class,
                () -> service.split("YEARLY", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));
        assertThrows(IllegalArgumentException.class,
                () -> service.split("MONTHLY", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 31)));
    }

    private BillingPeriod period(String start, String end) {
        return new BillingPeriod(LocalDate.parse(start), LocalDate.parse(end));
    }
}
