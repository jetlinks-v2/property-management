package org.jetlinks.pro.property.finance.standard;

import lombok.Getter;
import lombok.Setter;

/** 金额使用十进制字符串，避免浏览器对18位整数单价产生舍入。 */
@Getter
@Setter
public class StandardInput {
    /** 仅兼容旧请求字段；创建由后端生成，编辑始终保留原编码。 */
    private String standardCode;

    private String standardName;
    private String subjectId;
    private String billingMethod;
    private String formulaExpression;
    private String formulaDisplay;
    private String price;
    private Integer chargeRoundType;
    private Integer mantissa;
    private String settlementMethod;
    private String taxItemId;
    private String companyId;
    private Integer isDelay;
    private Integer delayCalcForm;
    private Integer delayRoundType;
    private String rateAmount;
    private String delayBaseType;
    private String executeDate;
    private String cancelDate;
    private String remark;
    private Long version;
}
