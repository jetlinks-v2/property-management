package org.jetlinks.pro.property.finance.report;

import java.math.BigDecimal;
import java.util.Map;

/** 退款展示正数，净额按原始现金方向汇总；无日期条件时不伪造本期划分。 */
public record ReceivedReportAmounts(
        String incomeAmount,
        String refundAmount,
        String netAmount,
        String currentPeriodAmount,
        String otherPeriodAmount,
        String unhandedAmount,
        String pendingAmount,
        String approvedAmount) {
    public ReceivedReportAmounts(String a, String b, String c, String d, String e) {
        this(a, b, c, d, e, "0.00", "0.00", "0.00");
    }

    public static ReceivedReportAmounts of(Map<String, Object> row, boolean period) {
        return new ReceivedReportAmounts(
                money(row, "income"),
                money(row, "refund"),
                money(row, "net"),
                period ? money(row, "currentAmount") : null,
                period ? money(row, "otherAmount") : null,
                money(row, "unhanded"),
                money(row, "pending"),
                money(row, "approved"));
    }

    private static String money(Map<String, Object> row, String key) {
        return new BigDecimal(java.util.Objects.toString(row.get(key), "0"))
                .setScale(2)
                .toPlainString();
    }
}
