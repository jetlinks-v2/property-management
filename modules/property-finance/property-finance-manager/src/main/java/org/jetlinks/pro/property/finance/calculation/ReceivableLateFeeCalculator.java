package org.jetlinks.pro.property.finance.calculation;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

@Component
public class ReceivableLateFeeCalculator {

    public LateFeeResult calculateLateFee(ReceivableSnapshot snapshot, LocalDate asOf) {
        if (snapshot == null || asOf == null) {
            throw new IllegalArgumentException("应收快照和计提日期不能为空");
        }
        int scale = Math.max(snapshot.getMoneyScale(), 0);
        if (!snapshot.isLateFeeEnabled() || snapshot.isLateFeeLocked()) {
            return new LateFeeResult(0, zero(scale));
        }
        if (snapshot.getDueDate() == null) {
            throw new IllegalArgumentException("应收日期不能为空");
        }
        LocalDate effectiveDate = asOf;
        if (snapshot.isPaid()) {
            if (snapshot.getPaidDate() == null) {
                return new LateFeeResult(0, zero(scale));
            }
            if (snapshot.getPaidDate().isBefore(effectiveDate)) {
                effectiveDate = snapshot.getPaidDate();
            }
        }
        if (!effectiveDate.isAfter(snapshot.getDueDate())) {
            return new LateFeeResult(0, zero(scale));
        }

        String periodType = normalized(snapshot.getLateFeePeriodType(), "计费周期");
        long overdueUnits;
        if ("DAILY".equals(periodType)) {
            overdueUnits = ChronoUnit.DAYS.between(snapshot.getDueDate(), effectiveDate);
        } else if ("MONTHLY".equals(periodType)) {
            overdueUnits = completeAnniversaryMonths(
                    snapshot.getDueDate().plusDays(1), effectiveDate);
        } else {
            throw new IllegalArgumentException("不支持的滞纳金计费周期：" + periodType);
        }

        BigDecimal amountOrRate = snapshot.getLateFeeAmountOrRate();
        if (amountOrRate == null || amountOrRate.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("滞纳金金额或比例不能为空且不能小于0");
        }
        String calculationType = normalized(snapshot.getLateFeeCalculationType(), "计算方式");
        BigDecimal accrued;
        if ("FIXED".equals(calculationType)) {
            accrued = amountOrRate.multiply(BigDecimal.valueOf(overdueUnits));
        } else if ("PERCENTAGE".equals(calculationType)) {
            BigDecimal base = lateFeeBase(snapshot);
            accrued = base.multiply(amountOrRate.movePointLeft(2))
                    .multiply(BigDecimal.valueOf(overdueUnits));
        } else {
            throw new IllegalArgumentException("不支持的滞纳金计算方式：" + calculationType);
        }
        return new LateFeeResult(overdueUnits, accrued.setScale(scale, RoundingMode.HALF_UP));
    }

    private long completeAnniversaryMonths(LocalDate start, LocalDate end) {
        long months = ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(end));
        if (months <= 0) {
            return 0;
        }
        return start.plusMonths(months).isAfter(end) ? months - 1 : months;
    }

    private BigDecimal lateFeeBase(ReceivableSnapshot snapshot) {
        String baseType = normalized(snapshot.getLateFeeBaseType(), "计费基数");
        BigDecimal value;
        if ("CURRENT_RECEIVABLE".equals(baseType)) {
            value = snapshot.getCurrentReceivable();
        } else if ("ACCUMULATED_OVERDUE".equals(baseType)) {
            value = snapshot.getAccumulatedOverdue();
        } else {
            throw new IllegalArgumentException("不支持的滞纳金计费基数：" + baseType);
        }
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("滞纳金计费基数不能为空且不能小于0");
        }
        return value;
    }

    private String normalized(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("滞纳金" + field + "不能为空");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal zero(int scale) {
        return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
    }

    public static final class LateFeeResult {
        private final long overdueUnits;
        private final BigDecimal accruedLateFee;

        private LateFeeResult(long overdueUnits, BigDecimal accruedLateFee) {
            this.overdueUnits = overdueUnits;
            this.accruedLateFee = accruedLateFee;
        }

        public long getOverdueUnits() {
            return overdueUnits;
        }

        public BigDecimal getAccruedLateFee() {
            return accruedLateFee;
        }
    }
}
