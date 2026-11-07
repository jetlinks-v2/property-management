package org.jetlinks.pro.property.finance.calculation;

import org.jetlinks.pro.property.finance.calculation.ChargeFormulaParser.ParsedFormula;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ReceivableAmountCalculator {
    private static final String PERIOD_MONTHS = "PERIOD_MONTHS";
    private static final int INTERMEDIATE_SCALE = 12;

    private final ChargeFormulaParser parser;
    private final ChargeFormulaEvaluator evaluator;
    private final BillingProrationService proration;

    public ReceivableAmountSnapshot calculate(ReceivableCalculationContext context, LocalDate accruedAsOf) {
        validate(context, accruedAsOf);
        ParsedFormula formula = parser.parse(context.getFormulaExpression(), context.getVariableNames());
        BigDecimal contract = calculateCoverage(context, formula,
                context.getCoveredStart(), context.getCoveredEnd());
        LocalDate accruedEnd = accruedAsOf.isBefore(context.getCoveredEnd())
                ? accruedAsOf : context.getCoveredEnd();
        BigDecimal accrued = accruedEnd.isBefore(context.getCoveredStart())
                ? BigDecimal.ZERO.setScale(context.getScale(), context.getRoundingMode())
                : calculateCoverage(context, formula, context.getCoveredStart(), accruedEnd);
        return new ReceivableAmountSnapshot(contract, accrued, accruedAsOf);
    }

    private BigDecimal calculateCoverage(ReceivableCalculationContext context, ParsedFormula formula,
                                         LocalDate start, LocalDate end) {
        Map<String, BigDecimal> variables = new LinkedHashMap<>(context.getVariableValues());
        if (formula.containsVariable(PERIOD_MONTHS)) {
            variables.put(PERIOD_MONTHS, proration.monthFraction(start, end));
        }
        BigDecimal formulaAmount = evaluator.evaluate(formula, variables,
                INTERMEDIATE_SCALE, context.getRoundingMode());
        BigDecimal factor = proration.factor(formula, context.getPeriod(), start, end);
        // 单一收费客户承担完整金额；不再接受客户分摊比例。
        return formulaAmount.multiply(factor)
                .setScale(context.getScale(), context.getRoundingMode());
    }

    private void validate(ReceivableCalculationContext context, LocalDate accruedAsOf) {
        if (context == null || context.getPeriod() == null || context.getCoveredStart() == null
                || context.getCoveredEnd() == null || accruedAsOf == null) {
            throw new IllegalArgumentException("应收计算账期和日期不能为空");
        }
        if (context.getCoveredStart().isAfter(context.getCoveredEnd())
                || !context.getPeriod().contains(context.getCoveredStart())
                || !context.getPeriod().contains(context.getCoveredEnd())) {
            throw new IllegalArgumentException("应收计算覆盖区间必须位于自然账期内");
        }
        if (context.getScale() < 0 || context.getRoundingMode() == null) {
            throw new IllegalArgumentException("应收计算精度和尾数处理方式不能为空");
        }

    }
}
