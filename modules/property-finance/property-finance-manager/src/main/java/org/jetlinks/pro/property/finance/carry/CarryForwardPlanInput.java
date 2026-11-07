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
    private List<String> subjectIds;

    /** 空列表沿用历史科目范围；指定时仅结转这些标准产生的账单。 */
    private List<String> standardIds;

    private String periodStart, periodEnd;
    private String frequency, executeDay, executeAt, zone;
    private Boolean enabled = true, includeLateFee = false;
    private String remissionMode = "NONE";

    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal remissionValue;
}
