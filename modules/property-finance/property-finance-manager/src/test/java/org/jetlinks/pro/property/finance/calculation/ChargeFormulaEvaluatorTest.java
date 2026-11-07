package org.jetlinks.pro.property.finance.calculation;

import org.jetlinks.pro.property.finance.calculation.ChargeFormulaParser.ParsedFormula;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChargeFormulaEvaluatorTest {

    private final ChargeFormulaParser parser = new ChargeFormulaParser();
    private final ChargeFormulaEvaluator evaluator = new ChargeFormulaEvaluator();

    @Test
    void evaluatesOperatorPrecedenceAndAppliesFinalRoundingOnlyOnce() {
        ParsedFormula formula = parser.parse(
                "${STANDARD_PRICE}*${BILLING_AREA}+1/3",
                Set.of("STANDARD_PRICE", "BILLING_AREA"));

        BigDecimal amount = evaluator.evaluate(formula,
                Map.of("STANDARD_PRICE", new BigDecimal("10"),
                        "BILLING_AREA", new BigDecimal("2")),
                2, RoundingMode.HALF_UP);

        assertEquals(new BigDecimal("20.33"), amount);
    }

    @Test
    void rejectsMissingVolumeWithVariableNameAndPosition() {
        Map<String, String> allowed = new LinkedHashMap<>();
        allowed.put("STANDARD_PRICE", "收费标准_单价");
        allowed.put("BILLING_VOLUME", "计费体积");
        ParsedFormula formula = parser.parse(
                "${STANDARD_PRICE}*${BILLING_VOLUME}", allowed);

        FormulaEvaluationException error = assertThrows(FormulaEvaluationException.class,
                () -> evaluator.evaluate(formula,
                        Map.of("STANDARD_PRICE", BigDecimal.TEN),
                        2, RoundingMode.HALF_UP));

        assertTrue(error.getMessage().contains("计费体积"));
        assertTrue(error.getMessage().contains("位置"));
    }

    @Test
    void rejectsDynamicDivisionByZeroAtDivisionPosition() {
        ParsedFormula formula = parser.parse(
                "${STANDARD_PRICE}/${BILLING_AREA}",
                Set.of("STANDARD_PRICE", "BILLING_AREA"));

        FormulaEvaluationException error = assertThrows(FormulaEvaluationException.class,
                () -> evaluator.evaluate(formula,
                        Map.of("STANDARD_PRICE", BigDecimal.TEN,
                                "BILLING_AREA", BigDecimal.ZERO),
                        2, RoundingMode.HALF_UP));

        assertTrue(error.getMessage().contains("除数不能为0"));
        assertTrue(error.getMessage().contains("位置：18"));
    }

    @Test
    void parserRejectsUnknownVariableAndExecutableText() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("${UNKNOWN}+1", Set.of("STANDARD_PRICE")));
        assertTrue(unknown.getMessage().contains("UNKNOWN"));
        assertTrue(unknown.getMessage().contains("位置：1"));

        IllegalArgumentException function = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("max(1,2)", Set.of("STANDARD_PRICE")));
        assertTrue(function.getMessage().contains("位置：1"));

        IllegalArgumentException scientific = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("1e3", Set.of("STANDARD_PRICE")));
        assertTrue(scientific.getMessage().contains("位置：2"));
    }

    @Test
    void parserRejectsMismatchedParenthesesAndConstantDivisionByZero() {
        IllegalArgumentException parenthesis = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("${STANDARD_PRICE}*(1+2", Set.of("STANDARD_PRICE")));
        assertTrue(parenthesis.getMessage().contains("括号不匹配"));

        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("${STANDARD_PRICE}/(2-2)", Set.of("STANDARD_PRICE")));
        assertTrue(zero.getMessage().contains("除数不能为常量0"));
    }
}
