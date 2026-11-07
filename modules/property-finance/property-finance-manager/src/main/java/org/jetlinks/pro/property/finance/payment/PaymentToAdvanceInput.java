package org.jetlinks.pro.property.finance.payment;

import com.alibaba.fastjson.JSON;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 实收来源与预存目标分别声明公司，允许同项目跨房屋、客户和科目转存。 */
@Getter
@Setter
public class PaymentToAdvanceInput {
    private List<RefundInput.Item> items;
    private String companyId;
    private String targetCompanyId;
    private String spaceId;
    private String relationshipId;
    private String standardId;
    private BigDecimal amount;
    private String occurredAt;
    private String reason;
    private String startDate;
    private String endDate;
    private String attachmentFileId;
    private String attachmentName;

    public void validate(LocalDate date) {
        FundRules.validate(target(), date);
        RefundRules.validateToAdvance(refund("target", "实收转预存"), date);
    }

    /** 仅在来源准备阶段复用预收目标解析，不走现金收取接口。 */
    public FundsInput target() {
        var i = new FundsInput();
        i.setAction("TOPUP");
        i.setType("ADVANCE");
        i.setCompanyId(targetCompanyId);
        i.setSpaceId(spaceId);
        i.setRelationshipId(relationshipId);
        i.setStandardId(standardId);
        i.setAmount(amount);
        i.setOccurredAt(occurredAt);
        i.setReason(reason);
        i.setPayerName("实收转预存");
        i.setMethod("1");
        i.setStartDate(startDate);
        i.setEndDate(endDate);
        i.setAttachmentFileId(attachmentFileId);
        i.setAttachmentName(attachmentName);
        return i;
    }

    public RefundInput refund(String account, String customer) {
        var i = new RefundInput();
        i.setItems(items);
        i.setCompanyId(companyId);
        i.setMethod("TO_ADVANCE");
        i.setRecordedId(account);
        i.setReceiverName(customer);
        i.setAmount(amount);
        i.setRefundedAt(occurredAt);
        i.setReason(reason);
        i.setAttachmentFileId(attachmentFileId);
        i.setAttachmentName(attachmentName);
        return i;
    }

    public String fingerprint() {
        return PaymentRules.digest(
                JSON.toJSONString(
                        List.of(
                                companyId,
                                items.stream().map(i -> List.of(i.id(), i.version())).toList(),
                                FundRules.canonical(target()))));
    }
}
