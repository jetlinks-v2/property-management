package org.jetlinks.pro.property.finance.standard;

import java.util.*;

/** 旧计费映射的唯一选项定义，前端不另造数字枚举。 */
public final class StandardOptions {
    private StandardOptions() {}

    public static Map<String, Object> enums() {
        var options = new LinkedHashMap<String, Object>();
        var methods = new ArrayList<Map<String, Object>>();
        String[] codes = {"AREA", "PERSON", "COUNT", "WORKLOAD", "CUSTOM"};
        String[] texts = {"面积", "人数", "次数", "工作量", "自定义"};
        String[] variables = {"BILLING_AREA", "BILLING_PERSON", "CONTRACT_COUNT", "WORKLOAD", null};
        for (int i = 0; i < codes.length; i++) {
            var method = new LinkedHashMap<String, Object>();
            method.put("value", codes[i]);
            method.put("text", label("billing_" + codes[i].toLowerCase(Locale.ROOT), texts[i]));
            String expression =
                    variables[i] == null
                            ? "${STANDARD_PRICE}"
                            : "${STANDARD_PRICE}*${" + variables[i] + "}*${PERIOD_MONTHS}";
            String display = expression;
            for (var variable : VariableDefinition.SYSTEM)
                display =
                        display.replace(
                                "${" + variable.variableCode() + "}",
                                "[" + variable.variableName() + "]");
            method.put("defaultExpression", expression);
            method.put("defaultDisplay", "[费用_金额]=" + display);
            methods.add(method);
        }
        options.put("billingMethods", methods);
        options.put(
                "chargeRoundTypes", choices("precision", new Object[] {0, 1, 2}, "元", "角", "分"));
        options.put("mantissas", choices("mantissa", new Object[] {0, 1, 2}, "四舍五入", "舍去", "进位"));
        options.put(
                "settlementMethods",
                choices("settlement", new Object[] {"MONTHLY", "QUARTERLY"}, "按月", "按季度"));
        options.put("delayCalcForms", choices("delay_form", new Object[] {0, 1}, "金额", "比例"));
        options.put("delayRoundTypes", choices("delay_period", new Object[] {0, 1}, "按日", "按月"));
        options.put(
                "delayBaseTypes",
                choices(
                        "delay_base",
                        new Object[] {"CURRENT_RECEIVABLE", "ACCUMULATED_OVERDUE"},
                        "当期应收",
                        "累计欠费"));
        options.put(
                "effectiveStatuses",
                choices(
                        "status",
                        new Object[] {"NOT_STARTED", "EFFECTIVE", "EXPIRED"},
                        "未开始",
                        "生效中",
                        "已到期"));
        return options;
    }

    private static List<Map<String, Object>> choices(
            String prefix, Object[] values, String... texts) {
        var result = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < values.length; i++)
            result.add(
                    Map.of(
                            "value",
                            values[i],
                            "text",
                            label(
                                    prefix + "_" + values[i].toString().toLowerCase(Locale.ROOT),
                                    texts[i])));
        return result;
    }

    private static String label(String key, String fallback) {
        return org.hswebframework.web.i18n.LocaleUtils.resolveMessage(
                "message.property_finance_standard_" + key, fallback);
    }
}
