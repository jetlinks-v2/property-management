package org.jetlinks.pro.property.finance.payment;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import java.math.BigDecimal;
import java.time.*;

/** 只能将原冲抵退回原账户，客户端不能指定目标房屋、客户或出款方式。 */
public record FundReturnInput(
        String sourceEntryId,
        Long accountVersion,
        BigDecimal amount,
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
                || date == null) throw error("fund_return_invalid", 400);
        FundRules.money(amount);
        try {
            if (LocalDateTime.parse(occurredAt).toLocalDate().isAfter(date))
                throw error("fund_return_invalid", 400);
        } catch (java.time.format.DateTimeParseException | NullPointerException invalid) {
            throw error("fund_return_invalid", 400);
        }
        if (FundRules.blank(attachmentFileId) != FundRules.blank(attachmentName)
                || attachmentFileId != null && attachmentFileId.length() > 64
                || attachmentName != null && attachmentName.length() > 255)
            throw error("fund_return_invalid", 400);
    }

    public String fingerprint() {
        // Fastjson 1.x 不读取 record 访问器，显式列出所有提交字段，避免不同金额得到同一指纹。
        return PaymentRules.digest(
                JSON.toJSONString(
                        java.util.Arrays.asList(
                                sourceEntryId,
                                accountVersion,
                                amount == null ? null : amount.toPlainString(),
                                occurredAt,
                                reason,
                                attachmentFileId,
                                attachmentName)));
    }
}
