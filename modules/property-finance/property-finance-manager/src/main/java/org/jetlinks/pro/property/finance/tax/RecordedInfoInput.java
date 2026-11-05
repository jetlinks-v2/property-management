package org.jetlinks.pro.property.finance.tax;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/** 新增按来源与支付方式笛卡尔积创建；编辑只允许一个组合，版本由调用方提交。 */
@Getter
@Setter
public class RecordedInfoInput {
    private Long version;
    private String companyId;
    private String bankId;
    private List<String> sourceCharges;
    private List<String> patternPayments;
    private String facilitatorId;
    private Integer serviceChargeType;
    private BigDecimal minimumFee;
    private BigDecimal maximumFee;
    private BigDecimal fixedAmount;
    private List<Tier> tiers;

    public record Tier(
            BigDecimal minimumAmount, BigDecimal maximumAmount, BigDecimal procedureRate) {}
}
