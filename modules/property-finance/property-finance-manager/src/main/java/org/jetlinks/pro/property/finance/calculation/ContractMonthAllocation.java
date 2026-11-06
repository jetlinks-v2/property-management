package org.jetlinks.pro.property.finance.calculation;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;

/** 冻结合同账单金额的月度分配，不重算应收或读取当前收费标准。 与计费共用自然月权重，累计截分后相减，最后覆盖月承接全部尾差。 */
@Component
@RequiredArgsConstructor
public class ContractMonthAllocation {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private final BillingProrationService proration;

    public BigDecimal through(BigDecimal amount, LocalDate start, LocalDate end, YearMonth month) {
        amount = money(amount);
        if (month == null) throw new IllegalArgumentException("统计月份不能为空");
        var fullWeight = proration.monthWeight(start, end);
        var cutoff = month.atEndOfMonth();
        if (cutoff.isBefore(start)) return ZERO;
        if (!cutoff.isBefore(end)) return amount;
        // 用相同整数权重相除，只在最终金额截分；先把月比例截成小数会使10元变成9.99元。
        return amount.multiply(proration.monthWeight(start, cutoff))
                .divide(fullWeight, 2, RoundingMode.DOWN);
    }

    /** 净已付须由同一账单在两个截止日的成功核销减有效退款提供；余额冲抵也属于核销。 报表中的预收仅指该账单已核销超过期间分配部分，不能再加资金账户余额。 */
    public Amounts statement(
            BigDecimal amount,
            LocalDate start,
            LocalDate end,
            YearMonth month,
            BigDecimal openingPaid,
            BigDecimal closingPaid) {
        openingPaid = money(openingPaid);
        closingPaid = money(closingPaid);
        if (month == null) throw new IllegalArgumentException("统计月份不能为空");
        var opening = through(amount, start, end, month.minusMonths(1));
        var closing = through(amount, start, end, month);
        return new Amounts(
                opening.subtract(openingPaid).max(ZERO),
                openingPaid.subtract(opening).max(ZERO),
                closing.subtract(opening),
                closingPaid.subtract(openingPaid),
                closing.subtract(closingPaid).max(ZERO),
                closingPaid.subtract(closing).max(ZERO));
    }

    private BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException("金额必须为非负数");
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("金额最多两位小数", error);
        }
    }

    /** 六项分别以账单为单位计算后合计，禁止用一张账单的预收隐式抵销另一张账单的欠费。 */
    public record Amounts(
            BigDecimal openingReceivable,
            BigDecimal openingAdvance,
            BigDecimal currentReceivable,
            BigDecimal currentNetReceived,
            BigDecimal closingReceivable,
            BigDecimal closingAdvance) {}
}
