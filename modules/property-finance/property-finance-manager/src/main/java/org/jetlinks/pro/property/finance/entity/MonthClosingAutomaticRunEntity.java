package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

/** 自动月结安全摘要；金额及冻结来源只能从原月结记录按完整资产授权读取。 */
@Getter
@Setter
@Table(
        name = "pm_fin_month_auto_run",
        indexes =
                @Index(
                        name = "idx_pm_fin_month_auto_run",
                        columnList = "tenantId,projectId,createTime"))
public class MonthClosingAutomaticRunEntity extends FinanceEntity {
    @JsonIgnore
    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(nullable = false)
    private Long scheduleVersion;

    @Column(length = 7)
    private String accountMonth;

    @Column(length = 16, nullable = false)
    private String status;

    @Column(length = 128)
    private String errorCode;

    @Column(length = 64)
    private String operationId;
}
