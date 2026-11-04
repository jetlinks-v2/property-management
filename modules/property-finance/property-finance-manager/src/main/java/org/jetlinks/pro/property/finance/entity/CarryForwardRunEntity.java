package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 一个计划在一个日历槽位只有一次运行，计划编辑或重试均不能生成第二个槽位身份。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_carry_run",
        indexes = {
            @Index(name = "uk_pm_fin_carry_slot", columnList = "planId,slot", unique = true),
            @Index(name = "idx_pm_fin_carry_resume", columnList = "status,leaseUntil")
        })
public class CarryForwardRunEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String planId;

    @Column(nullable = false)
    private Long planVersion;

    @Column(length = 32, nullable = false)
    private String slot;

    @Column(length = 10, nullable = false)
    private String businessDate;

    @Column(length = 16, nullable = false)
    private String status;

    @JsonIgnore
    @Column(length = 64)
    private String tenantId;

    @JsonIgnore
    @Column(length = 16000, nullable = false)
    private String configJson;

    @JsonIgnore
    @Column(length = 64)
    private String cursorId;

    @JsonIgnore
    @Column(length = 64)
    private String leaseOwner;

    @JsonIgnore
    @Column(nullable = false)
    private Long leaseUntil;

    @Column(nullable = false)
    private Integer attempts;

    @Column(nullable = false)
    private Integer succeeded;

    @Column(nullable = false)
    private Integer failed;

    @Column(nullable = false)
    private Integer skipped;

    @Column private Long finishTime;

    @Column(length = 128)
    private String errorCode;
}
