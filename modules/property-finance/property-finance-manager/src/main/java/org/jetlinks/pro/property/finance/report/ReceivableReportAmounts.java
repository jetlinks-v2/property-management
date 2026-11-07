package org.jetlinks.pro.property.finance.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** 金额统一精确文本；读取正式净应收，不在报表重新计费或再次扣减减免。 */
public record ReceivableReportAmounts(
        String receivableAmount,
        String remissionAmount,
        String netAmount,
        String receivedAmount,
        String unreceivedAmount,
        String arrearsAmount,
        String collectionRate,
        String overdueAmount) {
    public ReceivableReportAmounts(
            String a, String b, String c, String d, String e, String f, String g) {
        this(a, b, c, d, e, f, g, "0.00");
    }

    public static ReceivableReportAmounts of(Map<String, Object> row) {
        var net = value(row, "net");
        var remission = value(row, "remission");
        var paid = value(row, "paid");
        var rate =
                net.signum() <= 0
                        ? BigDecimal.ZERO
                        : paid.multiply(BigDecimal.valueOf(100))
                                .divide(net, 2, RoundingMode.HALF_UP);
        return new ReceivableReportAmounts(
                money(net.add(remission)),
                money(remission),
                money(net),
                money(paid),
                money(value(row, "arrears")),
                money(value(row, "arrears")),
                money(rate),
                money(value(row, "overdue")));
    }

    private static BigDecimal value(Map<String, Object> row, String key) {
        var value = row.get(key);
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2).toPlainString();
    }
}
