package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.*;

/** 单笔自动结转的不可变结果，与减免及资金账本同事务发布；稳定ID负责同槽位重放。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_carry_item",
        indexes = @Index(name = "idx_pm_fin_carry_run", columnList = "projectId,planId,runId"))
public class CarryForwardItemEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String planId;

    @Column(nullable = false)
    private Long planVersion;

    @Column(length = 100, nullable = false)
    private String runId;

    @Column(length = 64, nullable = false)
    private String receivableId;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 64)
    private String accountId;

    @Column(length = 64)
    private String paymentId;

    @Column(length = 32, nullable = false)
    private String occurredAt;

    @Column(length = 10, nullable = false)
    private String businessDate;

    @Column(length = 16, nullable = false)
    private String status;

    @Column(length = 64)
    private String reason;

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal amount;

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal feeReduction;

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal lateFeeReduction;

    @Column(length = 64, nullable = false)
    @JsonIgnore
    private String requestFingerprint;
}
