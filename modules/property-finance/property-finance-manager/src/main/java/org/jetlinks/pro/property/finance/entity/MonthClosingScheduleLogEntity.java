package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

/** 自动月结配置变更审计，不保存可恢复授权的认证快照。 */
@Getter
@Setter
@Table(name = "pm_fin_month_schedule_log")
public class MonthClosingScheduleLogEntity extends FinanceEntity {
    @JsonIgnore
    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(nullable = false)
    private Long scheduleVersion;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(length = 16, nullable = false)
    private String mode;

    @Column private Integer day;

    @Column(length = 1000, nullable = false)
    private String reason;
}
