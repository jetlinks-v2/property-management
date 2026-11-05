package org.jetlinks.pro.property.finance.report;

import java.math.BigDecimal;
import java.util.*;

/** 金额以十进制文本传输；未开金额逐来源封底后求和，不能跨来源抵销。 */
public record InvoiceReportAmounts(
        String receivableAmount,
        String receivedAmount,
        String invoicedAmount,
        String uninvoicedAmount) {
    public static InvoiceReportAmounts of(Map<String, Object> row) {
        return new InvoiceReportAmounts(
                money(row, "basis"),
                money(row, "paid"),
                money(row, "invoiced"),
                money(row, "unissued"));
    }

    private static String money(Map<String, Object> row, String key) {
        return new BigDecimal(Objects.toString(row.get(key), "0")).setScale(2).toPlainString();
    }
}
