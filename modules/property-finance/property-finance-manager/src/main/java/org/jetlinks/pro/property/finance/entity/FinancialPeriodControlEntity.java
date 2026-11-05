package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Table;

/** 每租户、项目一行的财务期间控制记录。资金写入和月结在同一业务事务中锁定此行， closedThrough 包含结束当天；控制记录仅供内部服务使用，不开放维护和删除接口。 */
@Getter
@Setter
@Table(name = "pm_fin_period_control")
public class FinancialPeriodControlEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    @JsonIgnore
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 10)
    private String closedThrough;

    /** 历史空值与未配置均不强制应收审核；不影响交账必须审核的独立约束。 */
    @Column private Boolean requireReceivableReview = false;
}
