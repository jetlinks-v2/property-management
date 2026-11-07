package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

class MonthClosingScheduleDatesTest {
    @Test
    void monthEndWaitsForTheFullEndDayAndHandlesLeapYearAndDecember() {
        assertEquals(
                LocalDate.of(2024, 3, 1),
                MonthClosingScheduleDates.due("MONTH_END", null, "2024-02-29"));
        assertEquals(
                LocalDate.of(2025, 1, 1),
                MonthClosingScheduleDates.due("MONTH_END", null, "2024-12-31"));
    }

    @Test
    void specifiedDayUsesFirstOccurrenceStrictlyAfterActualPeriodEnd() {
        assertEquals(
                LocalDate.of(2024, 2, 5), MonthClosingScheduleDates.due("DAY", 5, "2024-01-31"));
        assertEquals(
                LocalDate.of(2024, 3, 5), MonthClosingScheduleDates.due("DAY", 5, "2024-02-09"));
        assertEquals(
                LocalDate.of(2024, 3, 9), MonthClosingScheduleDates.due("DAY", 9, "2024-02-09"));
        assertEquals(
                LocalDate.of(2024, 2, 10), MonthClosingScheduleDates.due("DAY", 10, "2024-02-09"));
    }
}
