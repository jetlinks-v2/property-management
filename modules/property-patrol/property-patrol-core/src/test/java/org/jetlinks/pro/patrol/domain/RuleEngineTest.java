package org.jetlinks.pro.patrol.domain;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

class RuleEngineTest {
    private final LocalDate monday = LocalDate.of(2026, 11, 2);
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");
    private PatrolRule rule(List<PatrolRule.Window> windows) {
        return new PatrolRule(Map.of(1, windows), Map.of(), 30);
    }
    private PatrolRule.Window w(int start, int end, int interval, int count) {
        return new PatrolRule.Window(start, end, interval, count);
    }
    @Test
    void hourlyFrequencyKeepsOneConfiguredPeriodWithFourRequiredPatrols() {
        var rows = RuleEngine.expand(rule(List.of(w(480, 720, 60, 1))), monday, zone);
        assertEquals(1, rows.size());
        assertEquals(monday.atTime(8, 0).atZone(zone).toInstant(), rows.get(0).start());
        assertEquals(monday.atTime(12, 0).atZone(zone).toInstant(), rows.get(0).end());
        assertEquals(4, rows.get(0).requiredCount());
        assertEquals(30, rows.get(0).cooldownMinutes());
    }
    @Test
    void datesCanBeConfiguredAloneOrOverrideWeekdaysIncludingPersistedRest() {
        var base = rule(List.of(w(480, 600, 60, 2)));
        assertEquals(4, RuleEngine.expand(base, monday, zone).get(0).requiredCount());
        var dateOnly = new PatrolRule(Map.of(), Map.of(monday, List.of(w(600, 660, 60, 3))), 0);
        assertEquals(3, RuleEngine.expand(dateOnly, monday, zone).get(0).requiredCount());
        assertTrue(RuleEngine.expand(dateOnly, monday.plusDays(1), zone).isEmpty());
        var both = new PatrolRule(base.weekly(), dateOnly.dates(), 0);
        assertEquals(1, RuleEngine.expand(both, monday, zone).size());
        assertEquals(3, RuleEngine.expand(both, monday, zone).get(0).requiredCount());
        assertTrue(RuleEngine.expand(new PatrolRule(base.weekly(), Map.of(monday, List.of()), 30), monday, zone).isEmpty());
    }
    @Test
    void incompleteCycleTailKeepsWholeWindowButDoesNotAddRequiredCount() {
        var result = RuleEngine.expand(rule(List.of(w(480, 610, 60, 2))), monday, zone);
        assertEquals(1, result.size());
        assertEquals(4, result.get(0).requiredCount());
        assertEquals(monday.atTime(10, 10).atZone(zone).toInstant(), result.get(0).end());
        var zero = RuleEngine.expand(rule(List.of(w(480, 510, 60, 1))), monday, zone).get(0);
        assertEquals(0, zero.requiredCount());
    }
    @Test
    void eachPeriodCarriesItsOwnMinimumInterval() {
        var first = new PatrolRule.Window(480, 720, 60, 1, 10, "2", "1");
        var second = new PatrolRule.Window(780, 1080, 120, 1, 120, "2", "2");
        var rows = RuleEngine.expand(rule(List.of(first, second)), monday, zone);
        assertEquals(List.of(4, 2), rows.stream().map(RuleEngine.Occurrence::requiredCount).toList());
        assertEquals(List.of(10, 120), rows.stream().map(RuleEngine.Occurrence::cooldownMinutes).toList());
    }
    @Test
    void overnightUsesLogicalBusinessDateAndEqualTimesMean24Hours() {
        var result = RuleEngine.expand(rule(List.of(w(1320, 360, 120, 1))), monday, zone);
        assertEquals(1, result.size());
        assertEquals(4, result.get(0).requiredCount());
        assertEquals(monday.plusDays(1).atTime(6, 0).atZone(zone).toInstant(), result.get(0).end());
        assertEquals(monday, result.get(0).businessDate());
        var allDay = RuleEngine.expand(rule(List.of(w(0, 0, 60, 1))), monday, zone).get(0);
        assertEquals(24, allDay.requiredCount());
        assertEquals(monday.plusDays(1).atStartOfDay(zone).toInstant(), allDay.end());
        assertEquals(allDay.end(), RuleEngine.expand(rule(List.of(w(0, 1440, 60, 1))), monday, zone).get(0).end());
    }
    @Test
    void touchingPeriodsAllowedAndSameDayOverlapRejected() {
        assertEquals(2, RuleEngine.expand(rule(List.of(w(480, 540, 60, 1), w(540, 600, 60, 1))), monday, zone).size());
        assertThrows(IllegalArgumentException.class, () -> RuleEngine.validate(rule(List.of(w(480, 550, 60, 1), w(540, 600, 60, 1)))));
    }
    @Test
    void savingRejectsWeeklyAndSpecialDateCrossMidnightOverlap() {
        assertThrows(IllegalArgumentException.class, () -> RuleEngine.validate(new PatrolRule(
                Map.of(7, List.of(w(1320, 360, 120, 1)), 1, List.of(w(300, 480, 60, 1))), Map.of(), 0)));
        assertThrows(IllegalArgumentException.class, () -> RuleEngine.validate(new PatrolRule(
                Map.of(1, List.of(w(1320, 360, 120, 1))), Map.of(monday.plusDays(1), List.of(w(300, 480, 60, 1))), 0)));
        assertThrows(IllegalArgumentException.class, () -> RuleEngine.validate(new PatrolRule(
                Map.of(2, List.of(w(300, 480, 60, 1))), Map.of(monday, List.of(w(1320, 360, 120, 1))), 0)));
    }
    @Test
    void impossibleIntervalAndExcessiveCountAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RuleEngine.validate(rule(List.of(new PatrolRule.Window(480, 720, 60, 1, 100, null, null)))));
        assertThrows(IllegalArgumentException.class, () -> RuleEngine.validate(rule(List.of(new PatrolRule.Window(0, 1440, 1, 100, 0, null, null)))));
    }
    @Test
    void invalidUnitsAndWeekdaysAreRejectedButDayUnitIsSupported() {
        assertThrows(IllegalArgumentException.class, () -> RuleEngine.validate(rule(List.of(w(0, 60, 0, 1)))));
        assertThrows(IllegalArgumentException.class, () -> RuleEngine.validate(new PatrolRule(Map.of(8, List.of()), Map.of(), 0)));
        assertThrows(IllegalArgumentException.class, () -> RuleEngine.validate(rule(List.of(new PatrolRule.Window(0, 0, 65, 1, 1, "2", "1")))));
        var dayWindow = new PatrolRule.Window(0, 0, 1440, 1, 1440, "3", "3");
        assertEquals(1, RuleEngine.expand(rule(List.of(dayWindow)), monday, zone).get(0).requiredCount());
    }
}
