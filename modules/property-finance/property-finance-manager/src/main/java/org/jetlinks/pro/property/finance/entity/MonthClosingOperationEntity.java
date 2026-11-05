package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 月结/反月结成功身份及不可变结果，与全部来源和期间边界同事务写入。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_month_operation",
        indexes =
                @Index(
                        name = "idx_pm_fin_month_op",
                        columnList = "tenantId,projectId,accountMonth,createTime"))
public class MonthClosingOperationEntity extends FinanceEntity {
    @JsonIgnore
    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 7, nullable = false)
    private String accountMonth;

    @Column(length = 16, nullable = false)
    private String action;

    @JsonIgnore
    @Column(length = 64, nullable = false)
    private String requestFingerprint;

    @JsonIgnore
    @Column(length = 6000, nullable = false)
    private String resultJson;

    @Column(length = 10)
    private String previousClosedThrough;

    @Column(length = 64)
    private String closeOperationId;

    @Column(nullable = false)
    private Integer memberCount;

    @Column(length = 1000, nullable = false)
    private String reason;
}
