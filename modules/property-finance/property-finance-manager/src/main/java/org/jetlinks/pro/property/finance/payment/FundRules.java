package org.jetlinks.pro.property.finance.payment;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 余额金额用十进制，所有入口共用校验和输入指纹。 */
public final class FundRules {
    private FundRules() {}

    public static final Set<String> TYPES = Set.of("ADVANCE", "DEPOSIT");

    /** 新预收款必须明确计费期间；已入账的旧请求仍按原指纹恢复。 */
    public static void validateNewCollection(FundsInput input) {
        if ("TOPUP".equals(input.getAction())
                && "ADVANCE".equals(input.getType())
                && (blank(input.getStartDate()) || blank(input.getEndDate())))
            throw error("fund_period_required", 400);
    }

    public static void validate(FundsInput i, LocalDate date) {
        if (i == null
                || date == null
                || !Set.of("TOPUP", "REFUND", "TRANSFER", "DEDUCT", "OFFSET")
                        .contains(Objects.toString(i.getAction(), ""))
                || !TYPES.contains(Objects.toString(i.getType(), ""))
                || !blank(i.getSourceEntryId())) throw error("fund_invalid", 400);
        money(i.getAmount());
        if (blank(i.getCompanyId())
                || blank(i.getPayerName())
                || i.getPayerName().length() > 128
                || n(i.getReason()).length() > 1000
                || (!"TOPUP".equals(i.getAction()) && blank(i.getReason())))
            throw error("fund_invalid", 400);
        for (var id :
                List.of(
                        n(i.getCompanyId()),
                        n(i.getAccountId()),
                        n(i.getSourceEntryId()),
                        n(i.getSpaceId()),
                        n(i.getRelationshipId()),
                        n(i.getStandardId()),
                        n(i.getSubjectId()),
                        n(i.getRecordedId()),
                        n(i.getTargetSpaceId()),
                        n(i.getTargetRelationshipId()),
                        n(i.getTargetStandardId()),
                        n(i.getAttachmentFileId())))
            if (id.length() > 64) throw error("fund_invalid", 400);
        if (n(i.getAttachmentName()).length() > 255
                || blank(i.getAttachmentName()) != blank(i.getAttachmentFileId()))
            throw error("fund_invalid", 400);
        try {
            if (LocalDateTime.parse(i.getOccurredAt()).toLocalDate().isAfter(date))
                throw error("fund_invalid", 400);
            if (!blank(i.getStartDate())
                    && !blank(i.getEndDate())
                    && LocalDate.parse(i.getEndDate()).isBefore(LocalDate.parse(i.getStartDate())))
                throw error("fund_invalid", 400);
        } catch (java.time.format.DateTimeParseException | NullPointerException ex) {
            throw error("fund_invalid", 400);
        }
        if (blank(i.getStartDate()) != blank(i.getEndDate())) throw error("fund_invalid", 400);
        if ("TOPUP".equals(i.getAction())) {
            if (blank(i.getSpaceId())
                    || blank(i.getRelationshipId())
                    || (blank(i.getStandardId())
                            && (!"DEPOSIT".equals(i.getType()) || blank(i.getSubjectId()))))
                throw error("fund_invalid", 400);
        } else if (blank(i.getAccountId()) || i.getVersion() == null || i.getVersion() < 0)
            throw error("fund_invalid", 400);
        if ("TOPUP".equals(i.getAction()) || "REFUND".equals(i.getAction())) {
            if (!PaymentRules.MANUAL_METHODS.contains(n(i.getMethod()))
                    || "1".equals(i.getMethod()) != blank(i.getRecordedId()))
                throw error("fund_invalid", 400);
        }
        if ("TRANSFER".equals(i.getAction())
                && (blank(i.getTargetSpaceId())
                        || blank(i.getTargetRelationshipId())
                        || blank(i.getTargetStandardId())
                        || !"ADVANCE".equals(i.getTargetType()))) throw error("fund_invalid", 400);
        if ("DEDUCT".equals(i.getAction())
                && (!"DEPOSIT".equals(i.getType()) || blank(i.getTargetStandardId())))
            throw error("fund_invalid", 400);
        if (i.getQuantity() != null
                && (i.getQuantity().signum() <= 0
                        || i.getQuantity().scale() > 4
                        || i.getQuantity().precision() > 12)) throw error("fund_invalid", 400);
        if ("OFFSET".equals(i.getAction())
                && (i.getItems() == null || i.getItems().isEmpty() || i.getItems().size() > 100))
            throw error("fund_invalid", 400);
        if ("OFFSET".equals(i.getAction())) {
            var ids = new HashSet<String>();
            for (var item : i.getItems()) {
                if (item == null
                        || blank(item.id())
                        || item.id().length() > 64
                        || item.version() == null
                        || item.version() < 0
                        || !ids.add(item.id())) throw error("fund_invalid", 400);
                money(item.amount());
            }
        }
    }

    public static String canonical(FundsInput i) {
        var fields = new ArrayList<Object>();
        Collections.addAll(
                fields,
                i.getAction(),
                i.getType(),
                n(i.getAccountId()),
                n(i.getSourceEntryId()),
                i.getVersion(),
                n(i.getSpaceId()),
                n(i.getRelationshipId()),
                n(i.getStandardId()),
                i.getCompanyId(),
                money(i.getAmount()).toPlainString(),
                n(i.getMethod()),
                n(i.getRecordedId()),
                i.getOccurredAt(),
                i.getPayerName(),
                i.getReason(),
                n(i.getAttachmentFileId()),
                n(i.getAttachmentName()),
                n(i.getStartDate()),
                n(i.getEndDate()),
                i.getQuantity(),
                n(i.getTargetSpaceId()),
                n(i.getTargetRelationshipId()),
                n(i.getTargetStandardId()),
                n(i.getTargetType()));
        fields.add(
                i.getItems() == null
                        ? List.of()
                        : i.getItems().stream()
                                .map(
                                        v ->
                                                List.of(
                                                        v.id(),
                                                        v.version(),
                                                        money(v.amount()).toPlainString()))
                                .toList());
        // 已持久化的旧请求指纹保持不变；科目押金的新字段必须参与幂等校验。
        if (!blank(i.getSubjectId())) fields.add(i.getSubjectId());
        return JSON.toJSONString(fields);
    }

    public static String fingerprint(FundsInput i) {
        return PaymentRules.digest(canonical(i));
    }

    public static BigDecimal money(BigDecimal n) {
        if (n == null || n.signum() <= 0 || n.scale() > 2 || n.precision() - n.scale() > 18)
            throw error("fund_invalid", 400);
        return n.setScale(2);
    }

    public static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String n(String s) {
        return Objects.toString(s, "");
    }
}
