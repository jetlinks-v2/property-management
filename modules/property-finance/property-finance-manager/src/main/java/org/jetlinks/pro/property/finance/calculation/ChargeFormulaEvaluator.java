package org.jetlinks.pro.property.finance.calculation;

import org.jetlinks.pro.property.finance.calculation.ChargeFormulaParser.ParsedFormula;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Map;

@Component
public class ChargeFormulaEvaluator {

    public BigDecimal evaluate(ParsedFormula formula, Map<String, BigDecimal> variables,
                               int scale, RoundingMode roundingMode) {
        if (formula == null) {
            throw new IllegalArgumentException("计费公式不能为空");
        }
        if (scale < 0) {
            throw new IllegalArgumentException("计算精度不能小于0");
        }
        if (roundingMode == null) {
            throw new IllegalArgumentException("尾数处理方式不能为空");
        }
        Map<String, BigDecimal> values = variables == null ? Collections.emptyMap() : variables;
        try {
            return formula.evaluate(values, MathContext.DECIMAL128).setScale(scale, roundingMode);
        } catch (FormulaEvaluationException ex) {
            throw ex;
        } catch (ArithmeticException ex) {
            throw new FormulaEvaluationException("计费公式结果无法按指定精度处理", ex);
        }
    }
}
