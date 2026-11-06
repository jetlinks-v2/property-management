package org.jetlinks.pro.property.quality.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class QualityReportRulesTest {
    @Test
    void taskRatesKeepSeparateClosedDenominatorAndLegacyScales() {
        var row = new LinkedHashMap<String, Object>(Map.of("totalTask", 10L, "closeTask", 4L,
            "completeTask", 3L, "qualifiedContentCount", 2L, "unqualifiedContentCount", 1L,
            "totalRectificationCount", 1L));
        QualityReportRules.taskRates(row);
        assertEquals(new BigDecimal("0.3000"), row.get("totalRate"));
        assertEquals(new BigDecimal("0.5000"), row.get("validRate"));
        assertEquals(new BigDecimal("0.67"), row.get("comprehensiveQualifiedRate"));
        assertEquals(new BigDecimal("1.0000"), row.get("rectificationRate"));
    }
    @Test
    void emptyAndAllClosedTasksUseZeroRatherThanNullOrDivideByZero() {
        var row = new LinkedHashMap<String, Object>(Map.of("totalTask", 2L, "closeTask", 2L));
        QualityReportRules.taskRates(row);
        assertEquals(BigDecimal.ZERO, row.get("validRate"));
        assertEquals(BigDecimal.ZERO, row.get("comprehensiveQualifiedRate"));
    }
    @Test
    void lineGrowthAllowsDecreasesAndDoesNotInventBaselineForZeroPeriod() {
        var row = new LinkedHashMap<String, Object>(Map.of("problemCount", 3L, "rectifiedCount", 2L));
        QualityReportRules.lineRates(row, 4L);
        assertEquals(1L, row.get("unrectifiedCount"));
        assertEquals(new BigDecimal("0.6667"), row.get("rectificationRate"));
        assertEquals(new BigDecimal("-0.2500"), row.get("problemGrowthRate"));
        QualityReportRules.lineRates(row, 0L);
        assertNull(row.get("problemGrowthRate"));
        QualityReportRules.lineRates(row, null);
        assertNull(row.get("previousProblemCount"));
    }
}
