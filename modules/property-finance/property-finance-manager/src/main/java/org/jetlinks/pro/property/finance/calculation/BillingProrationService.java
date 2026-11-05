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
    // 28、29、30、31的最小公倍数；自然月的任意覆盖均可表示成精确整数权重。
    private static final BigDecimal MONTH_WEIGHT = BigDecimal.valueOf(377580);
    private static final String PERIOD_MONTHS = "PERIOD_MONTHS";

    public BigDecimal factor(
            ParsedFormula formula,
            BillingPeriod period,
            LocalDate coveredStart,
            LocalDate coveredEnd) {
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
        return monthWeight(start, end)
                .divide(MONTH_WEIGHT, CALCULATION_SCALE, RoundingMode.HALF_UP);
    }

    /** 同一权重单位可直接作分子/分母，避免月比例先舍入后放大为金额误差；计算与跨度无关。 */
    BigDecimal monthWeight(LocalDate start, LocalDate end) {
        if (start == null || end == null) throw new IllegalArgumentException("计费开始和结束日期不能为空");
        if (start.isAfter(end)) throw new IllegalArgumentException("计费开始日期不能晚于结束日期");
        var first = YearMonth.from(start);
        var last = YearMonth.from(end);
        if (first.equals(last)) {
            return MONTH_WEIGHT
                    .divide(BigDecimal.valueOf(first.lengthOfMonth()))
                    .multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(start, end) + 1));
        }
        long middleMonths = ChronoUnit.MONTHS.between(first, last) - 1;
        return MONTH_WEIGHT
                .multiply(BigDecimal.valueOf(middleMonths))
                .add(
                        MONTH_WEIGHT
                                .divide(BigDecimal.valueOf(first.lengthOfMonth()))
                                .multiply(
                                        BigDecimal.valueOf(
                                                first.lengthOfMonth() - start.getDayOfMonth() + 1)))
                .add(
                        MONTH_WEIGHT
                                .divide(BigDecimal.valueOf(last.lengthOfMonth()))
                                .multiply(BigDecimal.valueOf(end.getDayOfMonth())));
    }

    private void validateCoverage(
            BillingPeriod period, LocalDate coveredStart, LocalDate coveredEnd) {
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
