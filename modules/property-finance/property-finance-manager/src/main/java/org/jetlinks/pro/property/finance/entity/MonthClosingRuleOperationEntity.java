package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

/** 月结审核规则变更审计；不保存或开放修改关闭日期。 */
@Getter
@Setter
@Table(
        name = "pm_fin_month_rule_log",
        indexes =
                @Index(
                        name = "idx_pm_fin_month_rule",
                        columnList = "tenantId,projectId,createTime"))
public class MonthClosingRuleOperationEntity extends FinanceEntity {
    @JsonIgnore
    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(nullable = false)
    private Boolean previousRequireReview;

    @Column(nullable = false)
    private Boolean requireReview;

    @Column(length = 1000, nullable = false)
    private String reason;
}
