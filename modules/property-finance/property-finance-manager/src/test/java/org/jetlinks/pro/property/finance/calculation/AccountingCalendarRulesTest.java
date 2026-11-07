package org.jetlinks.pro.property.finance.calculation;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.web.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** 会计月不等于自然计费月；校验真实日期覆盖，而非只断言返回十二条。 */
class AccountingCalendarRulesTest {
    private final AccountingCalendarRules rules = new AccountingCalendarRules();

    @Test
    void naturalMonthsUseTheirActualLastDayIncludingLeapFebruary() {
        var result = rules.generate(new AccountingCalendarRules.Input(2024, "NATURAL", null));
        assertEquals(12, result.size());
        for (int month = 1; month <= 12; month++) {
            var expected = YearMonth.of(2024, month);
            var period = result.get(month - 1);
            assertEquals(expected.toString(), period.month());
            assertEquals(expected.atDay(1), period.start());
            assertEquals(expected.atEndOfMonth(), period.end());
        }
        assertEquals(LocalDate.of(2024, 2, 29), result.get(1).end());
    }

    @Test
    void earlyOpeningDayAssignsFollowingMonthsFirstDaysToThePreviousLabel() {
        var result = rules.generate(new AccountingCalendarRules.Input(2024, "CUSTOM", 10));
        assertEquals(
                new AccountingCalendarRules.Period(
                        "2024-01", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 9)),
                result.get(0));
        assertEquals(
                new AccountingCalendarRules.Period(
                        "2024-02", LocalDate.of(2024, 2, 10), LocalDate.of(2024, 3, 9)),
                result.get(1));
        assertEquals(
                new AccountingCalendarRules.Period(
                        "2024-12", LocalDate.of(2024, 12, 10), LocalDate.of(2024, 12, 31)),
                result.get(11));
    }

    @Test
    void lateOpeningDayAssignsPreviousMonthsLastDaysToTheFollowingLabel() {
        var result = rules.generate(new AccountingCalendarRules.Input(2024, "CUSTOM", 20));
        assertEquals(
                new AccountingCalendarRules.Period(
                        "2024-01", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 19)),
                result.get(0));
        assertEquals(
                new AccountingCalendarRules.Period(
                        "2024-02", LocalDate.of(2024, 1, 20), LocalDate.of(2024, 2, 19)),
                result.get(1));
        assertEquals(
                new AccountingCalendarRules.Period(
                        "2024-12", LocalDate.of(2024, 11, 20), LocalDate.of(2024, 12, 31)),
                result.get(11));
    }

    @Test
    void firstDayHasNoInvertedJanuaryAndMatchesNaturalCalendar() {
        assertEquals(
                rules.generate(new AccountingCalendarRules.Input(2024, "NATURAL", null)),
                rules.generate(new AccountingCalendarRules.Input(2024, "CUSTOM", 1)));
    }

    @Test
    void everyAllowedOpeningDayCoversExactlyOneFullYearWithoutGapOrOverlap() {
        for (int year = 1999; year <= 2400; year++) {
            for (int day = 1; day <= 28; day++) {
                var periods =
                        rules.generate(new AccountingCalendarRules.Input(year, "CUSTOM", day));
                rules.validate(year, periods);
                long days =
                        periods.stream()
                                .mapToLong(
                                        period ->
                                                ChronoUnit.DAYS.between(
                                                                period.start(), period.end())
                                                        + 1)
                                .sum();
                assertEquals(Year.of(year).length(), days, "year=" + year + ", day=" + day);
                assertEquals(
                        12,
                        periods.stream()
                                .map(AccountingCalendarRules.Period::month)
                                .distinct()
                                .count());
            }
        }
    }

    @Test
    void fifteenAndSixteenUseTheIntendedLabelBoundary() {
        var early = rules.generate(new AccountingCalendarRules.Input(2024, "CUSTOM", 15));
        var late = rules.generate(new AccountingCalendarRules.Input(2024, "CUSTOM", 16));
        assertEquals(LocalDate.of(2024, 3, 14), early.get(1).end());
        assertEquals(LocalDate.of(2024, 2, 15), late.get(1).end());
        rules.validate(1, rules.generate(new AccountingCalendarRules.Input(1, "CUSTOM", 28)));
        rules.validate(9999, rules.generate(new AccountingCalendarRules.Input(9999, "CUSTOM", 2)));
    }

    @Test
    void unknownModeMissingFieldsAndOutOfRangeDatesAreBusinessErrors() {
        rejects(() -> rules.generate(null));
        for (var input :
                List.of(
                        new AccountingCalendarRules.Input(null, "NATURAL", 1),
                        new AccountingCalendarRules.Input(0, "NATURAL", 1),
                        new AccountingCalendarRules.Input(10000, "NATURAL", 1),
                        new AccountingCalendarRules.Input(2024, null, 1),
                        new AccountingCalendarRules.Input(2024, "OTHER", 1),
                        new AccountingCalendarRules.Input(2024, "CUSTOM", null),
                        new AccountingCalendarRules.Input(2024, "CUSTOM", 0),
                        new AccountingCalendarRules.Input(2024, "CUSTOM", 29))) {
            rejects(() -> rules.generate(input));
        }
    }

    @Test
    void manualYearMustHaveAllLabelsAndContinuousValidDates() {
        var original = rules.generate(new AccountingCalendarRules.Input(2024, "NATURAL", null));
        rejects(() -> rules.validate(2024, null));
        rejects(() -> rules.validate(2024, original.subList(0, 11)));
        rejects(() -> rules.validate(2023, original));
        for (var bad :
                List.of(
                        new AccountingCalendarRules.Period(
                                "2024-03", LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29)),
                        new AccountingCalendarRules.Period(
                                "2024-02", LocalDate.of(2024, 2, 2), LocalDate.of(2024, 2, 29)),
                        new AccountingCalendarRules.Period(
                                "2024-02", LocalDate.of(2024, 1, 31), LocalDate.of(2024, 2, 29)),
                        new AccountingCalendarRules.Period(
                                "2024-02", LocalDate.of(2024, 3, 1), LocalDate.of(2024, 2, 29)),
                        new AccountingCalendarRules.Period(
                                "2024-02", null, LocalDate.of(2024, 2, 29)))) {
            var candidate = new ArrayList<>(original);
            candidate.set(1, bad);
            rejects(() -> rules.validate(2024, candidate));
        }
        var edited = new ArrayList<>(original);
        edited.set(
                0,
                new AccountingCalendarRules.Period(
                        "2024-01", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 3)));
        edited.set(
                1,
                new AccountingCalendarRules.Period(
                        "2024-02", LocalDate.of(2024, 2, 4), LocalDate.of(2024, 2, 29)));
        assertDoesNotThrow(() -> rules.validate(2024, edited));
    }

    private void rejects(Runnable action) {
        var error = assertThrows(BusinessException.class, action::run);
        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("accounting_calendar_invalid"));
    }
}
