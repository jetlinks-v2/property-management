package org.jetlinks.pro.property.finance.payment;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import java.time.*;
import java.util.Arrays;

/** 整笔撤销原存入；客户端不提供可编辑金额或收款方式。 */
public record FundRevokeInput(
        String sourceEntryId,
        Long accountVersion,
        String occurredAt,
        String reason,
        String attachmentFileId,
        String attachmentName) {
    public void validate(LocalDate date) {
        if (sourceEntryId == null
                || sourceEntryId.isBlank()
                || sourceEntryId.length() > 64
                || accountVersion == null
                || accountVersion < 0
                || reason == null
                || reason.isBlank()
                || reason.length() > 1000
                || date == null) throw error("fund_revoke_invalid", 400);
        try {
            if (LocalDateTime.parse(occurredAt).toLocalDate().isAfter(date))
                throw error("fund_revoke_invalid", 400);
        } catch (java.time.format.DateTimeParseException | NullPointerException invalid) {
            throw error("fund_revoke_invalid", 400);
        }
        if (FundRules.blank(attachmentFileId) != FundRules.blank(attachmentName)
                || attachmentFileId != null && attachmentFileId.length() > 64
                || attachmentName != null && attachmentName.length() > 255)
            throw error("fund_revoke_invalid", 400);
    }

    public String fingerprint() {
        return PaymentRules.digest(
                JSON.toJSONString(
                        Arrays.asList(
                                sourceEntryId,
                                accountVersion,
                                occurredAt,
                                reason,
                                attachmentFileId,
                                attachmentName)));
    }
}
