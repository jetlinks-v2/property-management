package org.jetlinks.pro.property.finance.report;

import java.math.BigDecimal;
import java.util.Map;

/** 退款展示正数，净额按原始现金方向汇总；无日期条件时不伪造本期划分。 */
public record ReceivedReportAmounts(
        String incomeAmount,
        String refundAmount,
        String netAmount,
        String currentPeriodAmount,
        String otherPeriodAmount) {
    public static ReceivedReportAmounts of(Map<String, Object> row, boolean period) {
        return new ReceivedReportAmounts(
                money(row, "income"),
                money(row, "refund"),
                money(row, "net"),
                period ? money(row, "currentAmount") : null,
                period ? money(row, "otherAmount") : null);
    }

    private static String money(Map<String, Object> row, String key) {
        return new BigDecimal(java.util.Objects.toString(row.get(key), "0"))
                .setScale(2)
                .toPlainString();
    }
}
