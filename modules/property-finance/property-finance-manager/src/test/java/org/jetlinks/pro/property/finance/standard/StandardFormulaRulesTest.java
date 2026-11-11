package org.jetlinks.pro.property.finance.standard;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.finance.calculation.ChargeFormulaEvaluator;
import org.jetlinks.pro.property.finance.calculation.ChargeFormulaParser;
import org.junit.jupiter.api.Test;

import java.math.*;
import java.time.LocalDate;
import java.util.*;

class StandardFormulaRulesTest {
    final ChargeFormulaParser parser = new ChargeFormulaParser();

    @Test
    void builtinFormulasUseRealParserAndExactArithmeticForEveryMethod() {
        var methods = (List<Map<String, Object>>) StandardOptions.enums().get("billingMethods");
        var values =
                Map.of(
                        "STANDARD_PRICE",
                        new BigDecimal("123456789012345678.12"),
                        "BILLING_AREA",
                        new BigDecimal("2"),
                        "BILLING_PERSON",
                        new BigDecimal("2"),
                        "CONTRACT_COUNT",
                        new BigDecimal("2"),
                        "WORKLOAD",
                        new BigDecimal("2"),
                        "PERIOD_MONTHS", new BigDecimal("3"),
                        "WATER_USAGE", new BigDecimal("2"),
                        "ELECTRICITY_USAGE", new BigDecimal("2"));
        for (var method : methods) {
            var result =
                    StandardRules.formula(
                            parser,
                            method.get("defaultExpression").toString(),
                            method.get("defaultDisplay").toString(),
                            VariableDefinition.SYSTEM);
            var parsed = parser.parse(result.formulaExpression(), values.keySet());
            var expected =
                    values.get("STANDARD_PRICE")
                            .multiply(
                                    method.get("value").equals("CUSTOM") ? BigDecimal.ONE
                                      : Set.of("WATER", "ELECTRICITY").contains(method.get("value")) ? new BigDecimal("2")
                                      : new BigDecimal("6"));
            assertEquals(
                    expected,
                    new ChargeFormulaEvaluator().evaluate(parsed, values, 2, RoundingMode.HALF_UP));
        }
    }

    @Test void meterBuiltinsAreAvailableAndCannotMultiplyMonthsOrUseTheWrongEnergy() {
        assertTrue(VariableDefinition.SYSTEM.stream().anyMatch(v -> v.variableCode().equals("WATER_USAGE") && v.scopeType().equals("SYSTEM")));
        assertTrue(VariableDefinition.SYSTEM.stream().anyMatch(v -> v.variableCode().equals("ELECTRICITY_USAGE") && v.scopeType().equals("SYSTEM")));
        var input = valid(); input.setBillingMethod("WATER");
        input.setFormulaExpression("${STANDARD_PRICE}*${WATER_USAGE}");
        assertEquals("WATER", StandardRules.validate(input).getBillingMethod());
        input.setFormulaExpression("${STANDARD_PRICE}*${WATER_USAGE}*${PERIOD_MONTHS}");
        assertThrows(BusinessException.class, () -> StandardRules.validate(input));
        input.setFormulaExpression("${STANDARD_PRICE}*${ELECTRICITY_USAGE}");
        assertThrows(BusinessException.class, () -> StandardRules.validate(input));
        input.setFormulaExpression("TIERED(${WATER_USAGE},${STANDARD_PRICE},10,4)");
        input.setSettlementMethod("QUARTERLY");
        assertThrows(BusinessException.class, () -> StandardRules.validate(input));
    }

    @Test void meterFormulaCannotDeleteOrDuplicateTheProtectedProduct() {
        String valid = "TIERED(${STANDARD_PRICE}*${ELECTRICITY_USAGE},100,0.8)*0.9+2";
        var result = StandardRules.formula(parser, valid,
                "[费用_金额]=阶梯累加([收费标准_单价]*[用电量],100,0.8)*0.9+2", VariableDefinition.SYSTEM);
        assertEquals(valid, result.formulaExpression());
        for (String expression : List.of("${STANDARD_PRICE}+${WATER_USAGE}",
                "${STANDARD_PRICE}*${WATER_USAGE}+${WATER_USAGE}", "${WATER_USAGE}*${STANDARD_PRICE}")) {
            String display = "[费用_金额]=" + expression.replace("${STANDARD_PRICE}", "[收费标准_单价]").replace("${WATER_USAGE}", "[用水量]");
            assertThrows(BusinessException.class, () -> StandardRules.formula(parser, expression, display, VariableDefinition.SYSTEM));
        }
    }

    @Test
    void rejectsSpoofedDisplayUnknownVariablesConstantZeroAndUnsafeSyntax() {
        String[][] invalid = {
            {"${STANDARD_PRICE}*2", "[费用_金额]=[收费标准_单价]*3"},
            {"${UNKNOWN}+${STANDARD_PRICE}", "[费用_金额]=[UNKNOWN]+[收费标准_单价]"},
            {"${STANDARD_PRICE}/(2-2)", "[费用_金额]=[收费标准_单价]/(2-2)"},
            {"${STANDARD_PRICE};alert(1)", "[费用_金额]=[收费标准_单价];alert(1)"},
            {"${BILLING_AREA}", "[费用_金额]=[计费面积]"},
            {"(${STANDARD_PRICE}", "[费用_金额]=([收费标准_单价]"}
        };
        for (var formula : invalid)
            assertThrows(
                    BusinessException.class,
                    () ->
                            StandardRules.formula(
                                    parser, formula[0], formula[1], VariableDefinition.SYSTEM));
    }

    @Test
    void decimalsDateBoundariesAndDisabledLateParametersAreExact() {
        var input = valid();
        input.setPrice("999999999999999999.99");
        input.setExecuteDate("2026-01-01");
        input.setCancelDate("2026-12-31");
        input.setRateAmount("invalid discarded disabled field");
        var entity = StandardRules.validate(input);
        assertEquals("999999999999999999.99", entity.getPrice().toPlainString());
        assertNull(entity.getRateAmount());
        assertEquals(
                "NOT_STARTED",
                StandardRules.effectiveStatus(entity, LocalDate.parse("2025-12-31")));
        assertEquals(
                "EFFECTIVE", StandardRules.effectiveStatus(entity, LocalDate.parse("2026-01-01")));
        assertEquals(
                "EFFECTIVE", StandardRules.effectiveStatus(entity, LocalDate.parse("2026-12-31")));
        assertEquals(
                "EXPIRED", StandardRules.effectiveStatus(entity, LocalDate.parse("2027-01-01")));
        for (String amount : List.of("1000000000000000000", "1.001", "-1", "1e3", "NaN")) {
            input.setPrice(amount);
            assertThrows(BusinessException.class, () -> StandardRules.validate(input));
        }
        input.setPrice("1");
        input.setExecuteDate("2026-02-30");
        assertThrows(BusinessException.class, () -> StandardRules.validate(input));
    }

    @Test
    void lateFeeRequiresAllParametersAndRatioCannotExceedHundred() {
        var input = valid();
        input.setIsDelay(1);
        assertThrows(BusinessException.class, () -> StandardRules.validate(input));
        input.setDelayCalcForm(1);
        input.setDelayRoundType(1);
        input.setDelayBaseType("ACCUMULATED_OVERDUE");
        for (String amount : List.of("0", "100.01", "-1")) {
            input.setRateAmount(amount);
            assertThrows(BusinessException.class, () -> StandardRules.validate(input));
        }
        input.setRateAmount("100");
        assertEquals(new BigDecimal("100"), StandardRules.validate(input).getRateAmount());
    }

    public static StandardInput valid() {
        var input = new StandardInput();
        input.setStandardCode("STD-01");
        input.setStandardName("物业费");
        input.setSubjectId("subject");
        input.setCompanyId("company");
        input.setTaxItemId("tax");
        input.setBillingMethod("AREA");
        input.setFormulaExpression("${STANDARD_PRICE}*${BILLING_AREA}*${PERIOD_MONTHS}");
        input.setFormulaDisplay("[费用_金额]=[收费标准_单价]*[计费面积]*[起止时间（月）]");
        input.setPrice("1.23");
        input.setChargeRoundType(2);
        input.setMantissa(0);
        input.setSettlementMethod("MONTHLY");
        input.setIsDelay(0);
        return input;
    }
}
