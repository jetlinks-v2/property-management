package org.jetlinks.pro.property.quality.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class QualityHomeRulesTest {
    @Test
    void zeroDenominatorRemainsUnknownAndPercentUsesLegacyRounding() {
        assertNull(QualityHomeRules.percent(0, 0));
        assertEquals(33.33, QualityHomeRules.percent(1, 3));
        assertEquals(66.67, QualityHomeRules.percent(2, 3));
        assertEquals(200.0, QualityHomeRules.percent(2, 1));
    }

    @Test
    void dayHasSixTwoHourBucketsAndOneOvernightBucket() {
        var today = LocalDate.of(2026, 12, 31);
        var windows = QualityHomeRules.windows("day", today);
        assertEquals(7, windows.size());
        assertEquals(QualityHomeRules.at(today.atTime(8, 0)), windows.get(0).start());
        assertEquals(QualityHomeRules.at(today.plusDays(1).atTime(8, 0)), windows.get(6).end());
        assertEquals("20:00-08:00", windows.get(6).label());
        for (int i = 1; i < windows.size(); i++) assertEquals(windows.get(i - 1).end(), windows.get(i).start());
    }

    @Test
    void sevenDayWindowCrossesYearAndIncludesToday() {
        var today = LocalDate.of(2027, 1, 3);
        var windows = QualityHomeRules.windows("week", today);
        assertEquals(7, windows.size());
        assertEquals("2026-12-28", windows.get(0).label());
        assertEquals(QualityHomeRules.at(today.plusDays(1).atStartOfDay()), windows.get(6).end());
    }

    @Test
    void leapMonthUsesFourBucketsAndClipsFutureDays() {
        var today = LocalDate.of(2028, 2, 9);
        var windows = QualityHomeRules.windows("month", today);
        assertEquals(4, windows.size());
        assertEquals(QualityHomeRules.at(today.plusDays(1).atStartOfDay()), windows.get(1).end());
        assertTrue(windows.get(2).start() > windows.get(2).end());
        assertEquals("2028-02-22~2028-02-29", windows.get(3).label());
        var last = QualityHomeRules.windows("month", LocalDate.of(2028, 2, 29)).get(3);
        assertEquals(QualityHomeRules.at(LocalDate.of(2028, 3, 1).atStartOfDay()), last.end());
    }
}
