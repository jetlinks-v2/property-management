package org.jetlinks.pro.property.finance.tax;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** 本地税目维护契约。税率使用0到1的小数；征收方式属于标准/票据，不属于税目主档。 */
@Getter
@Setter
public class TaxItemInput {
    private Long version;
    private String companyId;
    private String goodsName;
    private String goodsShortName;
    private String goodsCode;
    private String classificationName;
    private String specAndTypes;
    private BigDecimal taxRate;
    private String unit;
    private BigDecimal price;
    private Integer includingTaxFlag;
    private Integer favouredPolicyFlag;
    private String favouredPolicyName;
}
