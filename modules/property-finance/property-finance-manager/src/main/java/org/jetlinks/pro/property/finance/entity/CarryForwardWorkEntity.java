package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 调度尝试与不可变的入账item分开。失败可重试，已完成的账户仍复用原请求身份。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_carry_work",
        indexes = {
            @Index(name = "uk_pm_fin_carry_work", columnList = "runId,receivableId", unique = true),
            @Index(name = "idx_pm_fin_carry_work_pending", columnList = "runId,status,id")
        })
public class CarryForwardWorkEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String runId;

    @Column(length = 64, nullable = false)
    private String receivableId;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 2000, nullable = false)
    private String fullName;

    @Column(length = 16, nullable = false)
    private String status;

    @JsonIgnore
    @Column(length = 60000)
    private String accountIdsJson;

    @Column(nullable = false)
    private Integer attempts;

    @Column private Long finishTime;

    @Column(length = 128)
    private String errorCode;
}
