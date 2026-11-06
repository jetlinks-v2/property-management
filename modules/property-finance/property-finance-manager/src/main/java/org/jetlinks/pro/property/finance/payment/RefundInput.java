package org.jetlinks.pro.property.finance.payment;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/** 人工退款只提交来源、金额及已发生退款信息；不接受前端标记通道成功。 */
@Getter
@Setter
public class RefundInput {
    private List<Item> items;
    private BigDecimal amount;
    private String companyId;
    private String method;
    private String recordedId;
    private String refundedAt;
    private String receiverName;
    private String reason;
    private String attachmentFileId;
    private String attachmentName;

    public record Item(String id, Long version) {}

    public CashierInput fundingInput() {
        var input = new CashierInput();
        input.setCompanyId(companyId);
        input.setTenders(List.of(new CashierInput.Tender(method, recordedId, amount)));
        return input;
    }
}
