package org.jetlinks.pro.property.finance.calculation;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 会计账期按完整年度连续覆盖，与应收的自然计费账期分开；只计算日期，不隐式落库。 */
@Component
public class AccountingCalendarRules {
    public record Input(Integer year, String mode, Integer openingDay) {}

    public record Period(String month, LocalDate start, LocalDate end) {}

    /** 沿旧自定义开账日标签规则；每年首尾裁切，开账日为1时直接使用自然月以避免一月倒置。 */
    public List<Period> generate(Input input) {
        if (input == null
                || input.year() == null
                || input.year() < 1
                || input.year() > 9999
                || input.mode() == null
                || !Set.of("NATURAL", "CUSTOM").contains(input.mode()))
            throw error("accounting_calendar_invalid", 400);
        int day =
                "NATURAL".equals(input.mode())
                        ? 1
                        : input.openingDay() == null ? 0 : input.openingDay();
        if (day < 1 || day > 28) throw error("accounting_calendar_invalid", 400);
        var result = new ArrayList<Period>(12);
        for (int month = 1; month <= 12; month++) {
            var label = YearMonth.of(input.year(), month);
            LocalDate start;
            LocalDate end;
            if (day == 1) {
                start = label.atDay(1);
                end = label.atEndOfMonth();
            } else if (day <= 15) {
                start = month == 1 ? label.atDay(1) : label.atDay(day);
                end =
                        month == 12
                                ? label.atEndOfMonth()
                                : label.plusMonths(1).atDay(day).minusDays(1);
            } else {
                start = month == 1 ? label.atDay(1) : label.minusMonths(1).atDay(day);
                end = month == 12 ? label.atEndOfMonth() : label.atDay(day).minusDays(1);
            }
            result.add(new Period(label.toString(), start, end));
        }
        validate(input.year(), result);
        return List.copyOf(result);
    }

    /** 手工编辑也校验整年，不能只检验单期开始早于结束而留下缺期、重叠或跨年空档。 */
    public void validate(int year, List<Period> periods) {
        if (year < 1 || year > 9999 || periods == null || periods.size() != 12)
            throw error("accounting_calendar_invalid", 400);
        var expectedStart = LocalDate.of(year, 1, 1);
        for (int index = 0; index < 12; index++) {
            var period = periods.get(index);
            if (period == null
                    || !YearMonth.of(year, index + 1).toString().equals(period.month())
                    || period.start() == null
                    || period.end() == null
                    || !expectedStart.equals(period.start())
                    || period.end().isBefore(period.start())
                    || period.end().getYear() != year)
                throw error("accounting_calendar_invalid", 400);
            if (index == 11) {
                if (!LocalDate.of(year, 12, 31).equals(period.end()))
                    throw error("accounting_calendar_invalid", 400);
            } else {
                expectedStart = period.end().plusDays(1);
            }
        }
    }
}
