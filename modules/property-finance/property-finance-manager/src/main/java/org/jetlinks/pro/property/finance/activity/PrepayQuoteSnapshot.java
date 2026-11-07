package org.jetlinks.pro.property.finance.activity;

import java.util.List;

/** 日期和金额明确使用字符串；快照只重复逐期差异，避免为每期复制整份计费规则。 */
public record PrepayQuoteSnapshot(
        PrepayQuoteInput input,
        String businessDate,
        String houseName,
        String payerName,
        String payerCustomerId,
        String sourceFingerprint,
        String settingJson,
        String activityJson,
        String payAmount,
        String originalAmount,
        List<Period> periods) {
    public record Period(
            String type,
            String receivableId,
            Long expectedVersion,
            String rowFingerprint,
            String billKey,
            String start,
            String end,
            String originalAmount,
            String paidAmount,
            String discountAmount) {}
}
