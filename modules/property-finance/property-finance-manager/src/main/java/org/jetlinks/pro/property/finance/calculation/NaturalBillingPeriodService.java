package org.jetlinks.pro.property.finance.calculation;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class NaturalBillingPeriodService {

    public List<BillingPeriod> split(String settlementMethod, LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("计费开始和结束日期不能为空");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("计费开始日期不能晚于结束日期");
        }
        String method = settlementMethod == null
                ? ""
                : settlementMethod.trim().toUpperCase(Locale.ROOT);
        if (!"MONTHLY".equals(method) && !"QUARTERLY".equals(method)) {
            throw new IllegalArgumentException("结算方式仅支持MONTHLY或QUARTERLY");
        }

        int monthsPerPeriod = "MONTHLY".equals(method) ? 1 : 3;
        LocalDate cursor = "MONTHLY".equals(method)
                ? start.withDayOfMonth(1)
                : start.withMonth(((start.getMonthValue() - 1) / 3) * 3 + 1).withDayOfMonth(1);
        List<BillingPeriod> periods = new ArrayList<>();
        while (!cursor.isAfter(end)) {
            LocalDate periodEnd = cursor.plusMonths(monthsPerPeriod).minusDays(1);
            periods.add(new BillingPeriod(cursor, periodEnd));
            cursor = periodEnd.plusDays(1);
        }
        return periods;
    }
}
