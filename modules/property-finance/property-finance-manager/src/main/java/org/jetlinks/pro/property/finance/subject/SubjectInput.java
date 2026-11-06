package org.jetlinks.pro.property.finance.subject;

import lombok.Getter;
import lombok.Setter;

/** 明确的写入字段白名单，不接收编码、审计字段或项目关系。 */
@Getter
@Setter
public class SubjectInput {
    private Long version;
    private String categoryId;
    private String name;
    private String feeType;
    private Boolean enabled;
    private Boolean autoInvoice;
    private String arrearsConfigId;
    private String remark;
}
