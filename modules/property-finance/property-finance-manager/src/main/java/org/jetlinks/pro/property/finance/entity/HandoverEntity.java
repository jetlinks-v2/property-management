package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.*;

/** 交账是独立登记，金额从原资金事实冻结，不产生第二笔收款。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_handover",
        indexes =
                @Index(name = "idx_pm_fin_handover", columnList = "tenantId,projectId,closingDate"))
public class HandoverEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    @JsonIgnore
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    @JsonIgnore
    private String requestFingerprint;

    @Column(length = 20, nullable = false)
    private String status;

    @Column(length = 10, nullable = false)
    private String closingDate;

    @Column(length = 30, nullable = false)
    private String submittedAt;

    @Column(length = 128, nullable = false)
    private String submittedBy;

    @Column(nullable = false)
    private Integer sourceCount;

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal amount;

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal cashAmount;

    @Column(nullable = false)
    private Boolean monthClosed = false;

    @Column(length = 1000)
    private String remark;

    @Column(length = 64)
    private String attachmentFileId;

    @Column(length = 255)
    private String attachmentName;

    @Column(length = 30)
    private String reviewedAt;

    @Column(length = 128)
    private String reviewedBy;

    @Column(length = 1000)
    private String reviewRemark;
}
