package org.jetlinks.pro.property.finance.calculation;

import org.jetlinks.pro.property.finance.calculation.ChargeFormulaParser.ParsedFormula;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

@Component
public class BillingProrationService {
    private static final int CALCULATION_SCALE = 12;
    private static final int INTERMEDIATE_SCALE = 18;
    private static final String PERIOD_MONTHS = "PERIOD_MONTHS";

    public BigDecimal factor(ParsedFormula formula, BillingPeriod period,
                             LocalDate coveredStart, LocalDate coveredEnd) {
        validateCoverage(period, coveredStart, coveredEnd);
        if (formula != null && formula.containsVariable(PERIOD_MONTHS)) {
            return BigDecimal.ONE;
        }
        long coveredDays = ChronoUnit.DAYS.between(coveredStart, coveredEnd) + 1;
        long periodDays = ChronoUnit.DAYS.between(period.getStart(), period.getEnd()) + 1;
        return BigDecimal.valueOf(coveredDays)
                .divide(BigDecimal.valueOf(periodDays), CALCULATION_SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal monthFraction(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("计费开始和结束日期不能为空");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("计费开始日期不能晚于结束日期");
        }
        BigDecimal result = BigDecimal.ZERO;
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            YearMonth month = YearMonth.from(cursor);
            LocalDate segmentEnd = end.isBefore(month.atEndOfMonth()) ? end : month.atEndOfMonth();
            long coveredDays = ChronoUnit.DAYS.between(cursor, segmentEnd) + 1;
            result = result.add(BigDecimal.valueOf(coveredDays)
                    .divide(BigDecimal.valueOf(month.lengthOfMonth()),
                            INTERMEDIATE_SCALE, RoundingMode.HALF_UP));
            cursor = segmentEnd.plusDays(1);
        }
        return result.setScale(CALCULATION_SCALE, RoundingMode.HALF_UP);
    }

    private void validateCoverage(BillingPeriod period, LocalDate coveredStart, LocalDate coveredEnd) {
        if (period == null || coveredStart == null || coveredEnd == null) {
            throw new IllegalArgumentException("账期和覆盖日期不能为空");
        }
        if (coveredStart.isAfter(coveredEnd)) {
            throw new IllegalArgumentException("覆盖开始日期不能晚于结束日期");
        }
        if (!period.contains(coveredStart) || !period.contains(coveredEnd)) {
            throw new IllegalArgumentException("覆盖日期必须位于当前自然账期内");
        }
    }
}
