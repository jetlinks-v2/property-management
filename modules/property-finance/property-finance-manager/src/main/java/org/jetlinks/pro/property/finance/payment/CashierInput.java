package org.jetlinks.pro.property.finance.payment;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/** 柜台人工收款输入；欠款客户和账单金额来自后端，缴款人另行记录。 */
@Getter
@Setter
public class CashierInput {
    private String companyId;
    private String payerName;
    private String payerRelationshipId;
    private String paidAt;
    private String remark;
    private String attachmentName;
    private String attachmentFileId;
    private List<Item> items;
    private List<Tender> tenders;
    private Reduction reduction;

    /** 柜台当次本金减免，后端独立分配并与收款同事务落账。 */
    public record Reduction(String mode, BigDecimal value, String reason) {}

    public record Item(String id, Long version, BigDecimal amount) {}

    /** 非现金使用当前项目PC入账配置ID，不能以字典名称充当支付凭证。 */
    public record Tender(String method, String recordedId, BigDecimal amount) {}
}
