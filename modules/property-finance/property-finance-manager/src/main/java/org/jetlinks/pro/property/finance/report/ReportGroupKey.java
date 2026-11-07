package org.jetlinks.pro.property.finance.report;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import java.util.*;

/** 下钻同时匹配编码与名称快照，避免历史改名扩大明细范围。 */
public record ReportGroupKey(String value, String label) {
    public static void validate(List<ReportGroupKey> keys) {
        if (keys == null || keys.size() > 1000) throw error("report_query_invalid", 400);
        for (var key : keys)
            if (key == null
                    || key.value != null && key.value.length() > 100
                    || key.label != null && key.label.length() > 100)
                throw error("report_query_invalid", 400);
    }

    public static void filter(
            List<String> where,
            List<Object> params,
            List<ReportGroupKey> keys,
            String valueColumn,
            String labelColumn) {
        if (keys.isEmpty()) return;
        var alternatives = new ArrayList<String>();
        for (var key : keys)
            alternatives.add(
                    "("
                            + clause(params, valueColumn, key.value)
                            + " and "
                            + clause(params, labelColumn, key.label)
                            + ")");
        where.add("(" + String.join(" or ", alternatives) + ")");
    }

    private static String clause(List<Object> params, String column, String value) {
        if (value == null) return "\"" + column + "\" is null";
        params.add(value);
        return "\"" + column + "\"=?";
    }
}
