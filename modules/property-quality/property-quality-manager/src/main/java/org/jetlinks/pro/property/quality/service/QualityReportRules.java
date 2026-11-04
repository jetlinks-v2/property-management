package org.jetlinks.pro.property.quality.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** 旧报表返回比率小数：首页返回百分数，两者的舍入精度不可混用。 */
final class QualityReportRules {
    static BigDecimal ratio(long numerator, long denominator, int scale) {
        return denominator == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(numerator)
            .divide(BigDecimal.valueOf(denominator), scale, RoundingMode.HALF_UP);
    }
    static long number(Map<String, Object> row, String key) {
        return ((Number) row.getOrDefault(key, 0L)).longValue();
    }
    static void add(Map<String, Object> row, String key, long value) {
        row.put(key, number(row, key) + value);
    }
    static Map<String, Object> taskRates(Map<String, Object> row) {
        long total = number(row, "totalTask"), completed = number(row, "completeTask");
        long qualified = number(row, "qualifiedContentCount"), bad = number(row, "unqualifiedContentCount");
        row.put("totalRate", ratio(completed, total, 4));
        row.put("validRate", ratio(completed, total - number(row, "closeTask"), 4));
        row.put("comprehensiveQualifiedRate", ratio(qualified, qualified + bad, 2));
        row.put("rectificationRate", ratio(number(row, "totalRectificationCount"), bad, 4));
        return row;
    }
    static Map<String, Object> lineRates(Map<String, Object> row, Long previous) {
        long problem = number(row, "problemCount"), rectified = number(row, "rectifiedCount");
        row.put("unrectifiedCount", problem - rectified);
        row.put("rectificationRate", ratio(rectified, problem, 4));
        row.put("previousProblemCount", previous);
        row.put("problemGrowthRate", previous == null || previous == 0 ? null : ratio(problem - previous, previous, 4));
        return row;
    }
}
