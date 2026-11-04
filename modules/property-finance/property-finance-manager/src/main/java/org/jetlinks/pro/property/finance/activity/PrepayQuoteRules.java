package org.jetlinks.pro.property.finance.activity;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.*;

/** 预缴纯规则：金额必须来自逐账期计费结果，赠送是账期优惠，不是可提现余额。 */
public final class PrepayQuoteRules {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final int MAX_MONTHS = 1200;

    private PrepayQuoteRules() {}

    /** 月份先转换为自然期数；历史欠费优先，实收取前几期原额，优惠分摊到全部所选账期。 */
    public static Quote calculate(
            String method,
            int paidMonths,
            int giftMonths,
            List<Candidate> arrears,
            List<Candidate> future) {
        int step = monthsPerPeriod(method);
        if (paidMonths < 1
                || paidMonths > MAX_MONTHS
                || giftMonths < 0
                || giftMonths > MAX_MONTHS
                || paidMonths % step != 0
                || giftMonths % step != 0) throw error("prepay_invalid", 400);
        int paidPeriods = paidMonths / step, needed = (paidMonths + giftMonths) / step;
        var all = new ArrayList<Candidate>();
        append(all, arrears, true);
        append(all, future, false);
        var ids = new HashSet<String>();
        LocalDate previousEnd = null;
        for (var row : all) {
            if ((row.receivableId() != null && !ids.add(row.receivableId()))
                    || (previousEnd != null && !row.start().isAfter(previousEnd)))
                throw error("prepay_period_conflict", 409);
            previousEnd = row.end();
        }
        if (all.size() < needed) throw error("prepay_periods_insufficient", 409);
        var selected = all.subList(0, needed);
        var pay =
                money(
                        selected.subList(0, paidPeriods).stream()
                                .map(Candidate::originalAmount)
                                .reduce(ZERO, BigDecimal::add));
        var total =
                money(
                        selected.stream()
                                .map(Candidate::originalAmount)
                                .reduce(ZERO, BigDecimal::add));
        if (pay.signum() <= 0 || total.signum() <= 0) throw error("prepay_invalid", 400);
        var lines = new ArrayList<Line>();
        var remainingPay = pay;
        var remainingOriginal = total;
        for (var period : selected) {
            var original = money(period.originalAmount());
            remainingOriginal = remainingOriginal.subtract(original);
            var proportional = pay.multiply(original).divide(total, 2, RoundingMode.HALF_UP);
            // 保留旧逐期四舍五入、末期收尾；同时预留剩余账期容量，避免负尾差或优惠为负。
            var minimum = remainingPay.subtract(remainingOriginal).max(ZERO);
            var maximum = remainingPay.min(original);
            var allocated = proportional.max(minimum).min(maximum);
            lines.add(new Line(period, allocated, original.subtract(allocated)));
            remainingPay = remainingPay.subtract(allocated);
        }
        return new Quote(pay, total, List.copyOf(lines));
    }

    private static void append(List<Candidate> target, List<Candidate> rows, boolean arrears) {
        if (rows == null || rows.size() > 10000) throw error("prepay_invalid", 400);
        var sorted = new ArrayList<Candidate>();
        for (var row : rows) {
            if (row == null
                    || !validDate(row.start())
                    || !validDate(row.end())
                    || row.start().isAfter(row.end())
                    || (arrears
                            ? !"ARREARS".equals(row.type())
                            : !Set.of("CURRENT", "FUTURE")
                                    .contains(Objects.toString(row.type(), "")))
                    || (arrears && row.receivableId() == null)
                    || (row.receivableId() != null
                            && (row.receivableId().isBlank() || row.receivableId().length() > 64)))
                throw error("prepay_invalid", 400);
            money(row.originalAmount());
            sorted.add(row);
        }
        sorted.sort(Comparator.comparing(Candidate::start));
        target.addAll(sorted);
    }

    /** 只规划日期，逐期金额由现有计费引擎确定；月份上限同时约束循环工作量。 */
    public static List<Window> plan(
            String method,
            LocalDate purchaseDate,
            LocalDate effectiveStart,
            LocalDate effectiveEnd,
            int requestedMonths) {
        int step = monthsPerPeriod(method);
        if (!validDate(purchaseDate)
                || !validDate(effectiveStart)
                || (effectiveEnd != null
                        && (!validDate(effectiveEnd) || effectiveEnd.isBefore(effectiveStart)))
                || requestedMonths < 1
                || requestedMonths > 2 * MAX_MONTHS
                || requestedMonths % step != 0) throw error("prepay_invalid", 400);
        try {
            var cursor = naturalStart(purchaseDate, step);
            if (!purchaseDate.equals(cursor)) cursor = cursor.plusMonths(step);
            // 直接跳到生效期间，不能从购买日逐月空转到多年以后的生效日。
            if (cursor.isBefore(naturalStart(effectiveStart, step)))
                cursor = naturalStart(effectiveStart, step);
            if (step == 3 && cursor.isBefore(effectiveStart)) cursor = cursor.plusMonths(step);
            var result = new ArrayList<Window>();
            for (int count = 0; count < requestedMonths / step; count++) {
                var end = cursor.plusMonths(step).minusDays(1);
                if (!validDate(end)) throw error("prepay_invalid", 400);
                if (effectiveEnd != null
                        && (step == 3 ? end.isAfter(effectiveEnd) : cursor.isAfter(effectiveEnd)))
                    break;
                var coveredStart = cursor.isBefore(effectiveStart) ? effectiveStart : cursor;
                var coveredEnd =
                        effectiveEnd != null && end.isAfter(effectiveEnd) ? effectiveEnd : end;
                result.add(new Window(coveredStart, coveredEnd));
                cursor = cursor.plusMonths(step);
            }
            return List.copyOf(result);
        } catch (DateTimeException e) {
            throw error("prepay_invalid", 400);
        }
    }

    private static LocalDate naturalStart(LocalDate date, int step) {
        return step == 1
                ? date.withDayOfMonth(1)
                : date.withMonth(((date.getMonthValue() - 1) / 3) * 3 + 1).withDayOfMonth(1);
    }

    private static int monthsPerPeriod(String method) {
        if ("MONTHLY".equals(method)) return 1;
        if ("QUARTERLY".equals(method)) return 3;
        throw error("prepay_invalid", 400);
    }

    private static boolean validDate(LocalDate date) {
        return date != null && date.getYear() >= 1 && date.getYear() <= 9999;
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.precision() - value.scale() > 18)
            throw error("prepay_invalid", 400);
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw error("prepay_invalid", 400);
        }
    }

    public record Candidate(
            String type,
            String receivableId,
            LocalDate start,
            LocalDate end,
            BigDecimal originalAmount) {}

    public record Line(Candidate period, BigDecimal paidAmount, BigDecimal discountAmount) {}

    public record Quote(BigDecimal payAmount, BigDecimal originalAmount, List<Line> lines) {}

    public record Window(LocalDate start, LocalDate end) {}
}
