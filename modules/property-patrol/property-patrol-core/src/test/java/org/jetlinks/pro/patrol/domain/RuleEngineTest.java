package org.jetlinks.pro.patrol.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void dateOverrideIncludesEmptyHoliday() {
        var base = rule(List.of(w(480, 600, 60, 2)));
        assertEquals(2, RuleEngine.expand(base, monday, zone).size());
        assertTrue(
                RuleEngine.expand(
                                new PatrolRule(base.weekly(), Map.of(monday, List.of()), 30),
                                monday,
                                zone)
                        .isEmpty());
        var override =
                new PatrolRule(base.weekly(), Map.of(monday, List.of(w(600, 660, 60, 3))), 30);
        assertEquals(3, RuleEngine.expand(override, monday, zone).get(0).requiredCount());
    }

    @Test
    void incompleteFrequencyTailNotCounted() {
        var result = RuleEngine.expand(rule(List.of(w(480, 610, 60, 2))), monday, zone);
        assertEquals(2, result.size());
        assertEquals(monday.atTime(10, 0).atZone(zone).toInstant(), result.get(1).end());
    }

    @Test
    void overnightUsesLogicalBusinessDate() {
        var result = RuleEngine.expand(rule(List.of(w(1320, 360, 120, 1))), monday, zone);
        assertEquals(4, result.size());
        assertEquals(monday.plusDays(1).atTime(6, 0).atZone(zone).toInstant(), result.get(3).end());
        assertEquals(monday, result.get(3).businessDate());
    }

    @Test
    void touchingWindowsAllowedButOverlapRejected() {
        assertEquals(
                2,
                RuleEngine.expand(
                                rule(List.of(w(480, 540, 60, 1), w(540, 600, 60, 1))), monday, zone)
                        .size());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RuleEngine.expand(
                                rule(List.of(w(480, 550, 60, 1), w(540, 600, 60, 1))),
                                monday,
                                zone));
    }

    @Test
    void previousDayTailConflictsWithTodaysRule() {
        var r =
                new PatrolRule(
                        Map.of(1, List.of(w(1320, 360, 120, 1)), 2, List.of(w(300, 480, 60, 1))),
                        Map.of(),
                        0);
        assertThrows(
                IllegalArgumentException.class,
                () -> RuleEngine.expand(r, monday.plusDays(1), zone));
    }

    @Test
    void invalidWindowsAndWeekdaysRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RuleEngine.expand(rule(List.of(w(0, 60, 0, 1))), monday, zone));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RuleEngine.expand(
                                new PatrolRule(Map.of(8, List.of()), Map.of(), 0), monday, zone));
    }
}
