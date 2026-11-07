package org.jetlinks.pro.property.finance.carry;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/** 自动结转计划仅接受业务配置；项目来自受权路径，调度标识和执行状态由服务器生成。 */
@Getter
@Setter
public class CarryForwardPlanInput {
    private Long version;
    private String name;
    /** 保留既有数组契约，仅允许一个收费科目。 */
    private List<String> subjectIds;

    /** 必填且仅允许一个收费标准，必须属于所选科目。 */
    private List<String> standardIds;

    private String periodStart, periodEnd;
    private String frequency, executeDay, executeAt, zone;
    private Boolean enabled = true, includeLateFee = false;
    private String remissionMode = "NONE";

    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal remissionValue;
}
