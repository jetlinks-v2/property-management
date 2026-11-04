package org.jetlinks.pro.property.finance.calculation;

import java.time.LocalDate;
import java.util.Objects;

public final class BillingPeriod {
    private final LocalDate start;
    private final LocalDate end;

    public BillingPeriod(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("账期开始和结束日期不能为空");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("账期开始日期不能晚于结束日期");
        }
        this.start = start;
        this.end = end;
    }

    public LocalDate getStart() {
        return start;
    }

    public LocalDate getEnd() {
        return end;
    }

    public LocalDate coveredStart(LocalDate effectiveStart) {
        return effectiveStart == null || effectiveStart.isBefore(start) ? start : effectiveStart;
    }

    public LocalDate coveredEnd(LocalDate effectiveEnd) {
        return effectiveEnd == null || effectiveEnd.isAfter(end) ? end : effectiveEnd;
    }

    public boolean contains(LocalDate date) {
        return date != null && !date.isBefore(start) && !date.isAfter(end);
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof BillingPeriod)) {
            return false;
        }
        BillingPeriod that = (BillingPeriod) value;
        return start.equals(that.start) && end.equals(that.end);
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end);
    }

    @Override
    public String toString() {
        return start + "~" + end;
    }
}
