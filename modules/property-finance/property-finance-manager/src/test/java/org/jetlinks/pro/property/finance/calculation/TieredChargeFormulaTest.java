package org.jetlinks.pro.property.finance.calculation;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TieredChargeFormulaTest {
    private final ChargeFormulaParser parser = new ChargeFormulaParser();
    private final ChargeFormulaEvaluator evaluator = new ChargeFormulaEvaluator();
    private final Set<String> variables = Set.of("STANDARD_PRICE", "WATER_USAGE", "ELECTRICITY_USAGE");

    @Test void ordinaryVariablesContainingTieredRemainValid() {
        var formula = parser.parse("${STANDARD_PRICE}*${TIERED_FACTOR}", Set.of("STANDARD_PRICE", "TIERED_FACTOR"));
        assertEquals(new BigDecimal("6.00"), evaluator.evaluate(formula,
                Map.of("STANDARD_PRICE", new BigDecimal("2"), "TIERED_FACTOR", new BigDecimal("3")), 2, RoundingMode.HALF_UP));
    }

    @Test void editableFormulaAppliesAfterTierSumWithoutRepeatingTheSurcharge() {
        var formula = parser.parse("TIERED(${STANDARD_PRICE}*${WATER_USAGE},10,4,20,5)*0.9+2", variables);
        assertTrue(formula.preservesMeterBase("WATER_USAGE"));
        assertEquals(new BigDecimal("36.20"), evaluator.evaluate(formula,
                Map.of("STANDARD_PRICE", new BigDecimal("3"), "WATER_USAGE", new BigDecimal("12")), 2, RoundingMode.HALF_UP));
        for (String expression : new String[] {"${STANDARD_PRICE}+${WATER_USAGE}",
                "${STANDARD_PRICE}*${WATER_USAGE}+${WATER_USAGE}", "-${STANDARD_PRICE}*${WATER_USAGE}"}) {
            assertFalse(parser.parse(expression, variables).preservesMeterBase("WATER_USAGE"));
        }
    }

    @Test void monthlyBandsChargeOnlyTheUsageWithinEachBand() {
        var formula = parser.parse("TIERED(${WATER_USAGE},${STANDARD_PRICE},10,4,20,5)", variables);
        var cases = Map.of("0", "0.00", "10", "30.00", "12", "38.00", "20", "70.00", "25", "95.00", "10.125", "30.50");
        cases.forEach((usage, expected) -> assertEquals(new BigDecimal(expected), evaluator.evaluate(formula,
                Map.of("STANDARD_PRICE", new BigDecimal("3"), "WATER_USAGE", new BigDecimal(usage)), 2, RoundingMode.HALF_UP)));
        assertEquals(Set.of("WATER_USAGE", "STANDARD_PRICE"), formula.getVariables());
    }

    @Test void electricityRoundsOnlyAfterAddingAllBandsAndKeepsLargeDecimalPrices() {
        var formula = parser.parse("TIERED(${ELECTRICITY_USAGE},${STANDARD_PRICE},0.5,0.01)", variables);
        assertEquals(new BigDecimal("0.01"), evaluator.evaluate(formula,
                Map.of("STANDARD_PRICE", new BigDecimal("0.01"), "ELECTRICITY_USAGE", BigDecimal.ONE), 2, RoundingMode.HALF_UP));
        var single = parser.parse("TIERED(${WATER_USAGE},${STANDARD_PRICE})", variables);
        assertEquals(new BigDecimal("999999999999999999.99"), evaluator.evaluate(single,
                Map.of("STANDARD_PRICE", new BigDecimal("999999999999999999.99"), "WATER_USAGE", BigDecimal.ONE), 2, RoundingMode.HALF_UP));
        assertThrows(FormulaEvaluationException.class, () -> evaluator.evaluate(single,
                Map.of("STANDARD_PRICE", BigDecimal.ONE, "WATER_USAGE", BigDecimal.ONE.negate()), 2, RoundingMode.HALF_UP));
    }

    @Test void rejectsNonIncreasingThresholdsBadPricesWrongVariablesAndExecutableSyntax() {
        for (String expression : new String[] {
                "TIERED(${WATER_USAGE},${STANDARD_PRICE},0,4)",
                "TIERED(${WATER_USAGE},${STANDARD_PRICE},10,4,10,5)",
                "TIERED(${WATER_USAGE},${STANDARD_PRICE},20,4,10,5)",
                "TIERED(${WATER_USAGE},${STANDARD_PRICE},10,-1)",
                "TIERED(${WATER_USAGE},${STANDARD_PRICE},10,1.001)",
                "TIERED(${WATER_USAGE},${STANDARD_PRICE},10)",
                "TIERED(${WATER_USAGE},${STANDARD_PRICE},10,${ELECTRICITY_USAGE})",
                "TIERED(${STANDARD_PRICE},${STANDARD_PRICE},10,4)",
                "TIERED(${WATER_USAGE},3,10,4)",
                "TIERED(${STANDARD_PRICE}*${WATER_USAGE},10,4)+TIERED(${STANDARD_PRICE}*${WATER_USAGE})",
                "TIERED(${WATER_USAGE},${STANDARD_PRICE});alert(1)",
                "eval(1)"
        }) assertThrows(IllegalArgumentException.class, () -> parser.parse(expression, variables), expression);
    }
}
