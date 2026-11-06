package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

/** 项目自动月结配置，数据库行锁同时串行化配置编辑和整期执行。 */
@Getter
@Setter
@Table(
        name = "pm_fin_month_schedule",
        indexes =
                @Index(
                        name = "uk_pm_fin_month_schedule",
                        columnList = "tenantId,projectId",
                        unique = true))
public class MonthClosingScheduleEntity extends FinanceEntity {
    @JsonIgnore
    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(length = 16, nullable = false)
    private String mode;

    @Column private Integer day;

    @JsonIgnore
    @Column(length = 60000)
    private String executorSnapshot;

    @JsonIgnore
    @Column(nullable = false)
    private Long nextAttemptAt;
}
