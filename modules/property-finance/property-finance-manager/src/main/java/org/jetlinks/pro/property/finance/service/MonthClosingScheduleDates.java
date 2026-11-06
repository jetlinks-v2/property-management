package org.jetlinks.pro.property.finance.service;

import java.time.LocalDate;
import java.time.YearMonth;

/** 调度日必须严格晚于实际账期末；月末午夜不能关闭尚未发生的当天业务。 */
final class MonthClosingScheduleDates {
    private MonthClosingScheduleDates() {}

    static LocalDate due(String mode, Integer day, String endDate) {
        LocalDate end = LocalDate.parse(endDate);
        if ("MONTH_END".equals(mode)) return YearMonth.from(end).plusMonths(1).atDay(1);
        if (!"DAY".equals(mode) || day == null || day < 1 || day > 28)
            throw FinanceScope.error("month_schedule_invalid", 400);
        LocalDate candidate = end.withDayOfMonth(day);
        return candidate.isAfter(end) ? candidate : candidate.plusMonths(1);
    }
}
