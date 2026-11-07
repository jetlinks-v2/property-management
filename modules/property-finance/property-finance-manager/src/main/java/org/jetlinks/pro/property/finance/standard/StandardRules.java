package org.jetlinks.pro.property.finance.standard;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import org.jetlinks.pro.property.finance.calculation.ChargeFormulaParser;
import org.jetlinks.pro.property.finance.entity.StandardEntity;
import org.springframework.beans.BeanUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/** 统一保存及预校验规则；正式金额继续由迁移的安全解析器与计算核心计算。 */
public final class StandardRules {
    private static final Pattern TOKEN = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)}");
    public static final List<String> BILLING_METHODS =
            List.of("AREA", "PERSON", "COUNT", "WORKLOAD", "CUSTOM");

    private StandardRules() {}

    public static String text(String value, int max, boolean required) {
        String result = value == null ? null : value.trim();
        if (result != null && result.isEmpty()) result = null;
        if ((required && result == null) || (result != null && result.length() > max))
            throw error("standard_invalid", 400);
        return result;
    }

    public static StandardEntity validate(StandardInput input) {
        if (input == null) throw error("standard_invalid", 400);
        var next = new StandardEntity();
        BeanUtils.copyProperties(input, next, "price", "rateAmount", "version", "standardCode");
        next.setStandardName(text(input.getStandardName(), 30, true));
        next.setNameKey(next.getStandardName().toLowerCase(Locale.ROOT));
        next.setSubjectId(text(input.getSubjectId(), 64, true));
        next.setCompanyId(text(input.getCompanyId(), 64, true));
        next.setTaxItemId(text(input.getTaxItemId(), 64, true));
        next.setBillingMethod(choice(input.getBillingMethod(), BILLING_METHODS));
        next.setSettlementMethod(
                choice(input.getSettlementMethod(), List.of("MONTHLY", "QUARTERLY")));
        next.setPrice(decimal(input.getPrice(), false));
        number(input.getChargeRoundType(), 2);
        number(input.getMantissa(), 2);
        number(input.getIsDelay(), 1);
        if (input.getIsDelay() == 1) {
            number(input.getDelayCalcForm(), 1);
            number(input.getDelayRoundType(), 1);
            next.setDelayBaseType(
                    choice(
                            input.getDelayBaseType(),
                            List.of("CURRENT_RECEIVABLE", "ACCUMULATED_OVERDUE")));
            next.setRateAmount(decimal(input.getRateAmount(), true));
            if (input.getDelayCalcForm() == 1
                    && next.getRateAmount().compareTo(new BigDecimal("100")) > 0)
                throw error("standard_invalid", 400);
        } else {
            next.setDelayCalcForm(null);
            next.setDelayRoundType(null);
            next.setDelayBaseType(null);
            next.setRateAmount(null);
        }
        next.setExecuteDate(date(input.getExecuteDate()));
        next.setCancelDate(date(input.getCancelDate()));
        if (next.getExecuteDate() != null
                && next.getCancelDate() != null
                && next.getExecuteDate().compareTo(next.getCancelDate()) > 0)
            throw error("standard_invalid_dates", 400);
        next.setRemark(text(input.getRemark(), 300, false));
        return next;
    }

    private static BigDecimal decimal(String value, boolean positive) {
        if (value == null || !value.matches("[0-9]{1,18}(\\.[0-9]{1,2})?"))
            throw error("standard_invalid_amount", 400);
        var amount = new BigDecimal(value);
        if (positive && amount.signum() <= 0) throw error("standard_invalid_amount", 400);
        return amount;
    }

    private static void number(Integer value, int max) {
        if (value == null || value < 0 || value > max) throw error("standard_invalid", 400);
    }

    private static String choice(String value, List<String> choices) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!choices.contains(normalized)) throw error("standard_invalid", 400);
        return normalized;
    }

    private static String date(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                throw new DateTimeParseException("date", value, 0);
            return LocalDate.parse(value).toString();
        } catch (DateTimeParseException failure) {
            throw error("standard_invalid_dates", 400);
        }
    }

    /** 展示内容完全由已验证执行表达式推导，拒绝同名歧义或伪造的展示金额。 */
    public static FormulaResult formula(
            ChargeFormulaParser parser,
            String expression,
            String display,
            List<VariableDefinition> definitions) {
        String executable = compact(text(expression, 4000, true));
        String visible = compact(text(display, 4000, true));
        var names = new LinkedHashMap<String, String>();
        definitions.stream()
                .filter(VariableDefinition::enabled)
                .forEach(variable -> names.put(variable.variableCode(), variable.variableName()));
        try {
            var parsed = parser.parse(executable, names);
            if (!parsed.containsVariable("STANDARD_PRICE"))
                throw error("standard_price_required", 400);
            var matcher = TOKEN.matcher(executable);
            var derived = new StringBuffer("[费用_金额]=");
            while (matcher.find())
                matcher.appendReplacement(
                        derived,
                        java.util.regex.Matcher.quoteReplacement(
                                "[" + names.get(matcher.group(1)) + "]"));
            matcher.appendTail(derived);
            if (!compact(derived.toString()).equals(visible))
                throw error("standard_formula_mismatch", 400);
            var used =
                    definitions.stream()
                            .filter(variable -> parsed.containsVariable(variable.variableCode()))
                            .toList();
            return new FormulaResult(executable, derived.toString(), used);
        } catch (IllegalArgumentException failure) {
            throw error("standard_formula_invalid", 400);
        }
    }

    private static String compact(String value) {
        return value.replaceAll("\\s+", "");
    }

    public static String effectiveStatus(StandardEntity entity, LocalDate date) {
        if (entity.getExecuteDate() != null
                && date.isBefore(LocalDate.parse(entity.getExecuteDate()))) return "NOT_STARTED";
        if (entity.getCancelDate() != null && date.isAfter(LocalDate.parse(entity.getCancelDate())))
            return "EXPIRED";
        return "EFFECTIVE";
    }

    public record FormulaResult(
            String formulaExpression,
            String formulaDisplay,
            List<VariableDefinition> variablesSnapshot) {}
}
