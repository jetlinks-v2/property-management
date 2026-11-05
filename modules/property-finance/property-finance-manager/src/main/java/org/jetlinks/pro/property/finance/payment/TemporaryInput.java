package org.jetlinks.pro.property.finance.payment;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/** 临时收款只提交选择与金额，标准、客户及税务快照由服务器生成。 */
@Getter
@Setter
public class TemporaryInput {
    private String spaceId;
    private String relationshipId;
    private String standardId;
    private String subjectId;
    private String companyId;
    private String startDate;
    private String endDate;
    private String accountPeriod;
    private BigDecimal quantity;
    private BigDecimal amount;
    private String paidAt;
    private String payerName;
    private String remark;
    private String attachmentName;
    private String attachmentFileId;
    private List<CashierInput.Tender> tenders;

    public CashierInput fundingInput() {
        var input = new CashierInput();
        input.setCompanyId(companyId);
        input.setTenders(tenders);
        input.setPaidAt(paidAt);
        input.setPayerName(payerName);
        input.setRemark(remark);
        input.setAttachmentName(attachmentName);
        input.setAttachmentFileId(attachmentFileId);
        return input;
    }
}
