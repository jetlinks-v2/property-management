package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.*;

/** 柜台退款申请；只有审核执行成功后才关联不可变的资金事实。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_refund_application",
        indexes = @Index(name = "idx_pm_fin_refund_app", columnList = "projectId,status"))
public class RefundApplicationEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String number;

    @Column(length = 20, nullable = false)
    private String sourceType;

    @Column(length = 20, nullable = false)
    private String status;

    @Column(length = 64, nullable = false)
    private String companyId;

    @Column(length = 100, nullable = false)
    private String companyName;

    @Column(length = 128, nullable = false)
    private String receiverName;

    @Column(length = 100, nullable = false)
    private String methodText;

    @Column(length = 30, nullable = false)
    private String refundedAt;

    @Column(length = 1000, nullable = false)
    private String reason;

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal amount;

    @Column(length = 64)
    private String resultId;

    @Column(length = 64)
    private String reviewedBy;

    @Column(length = 128)
    private String reviewerName;

    @Column private Long reviewedAt;

    @Column(length = 1000)
    private String reviewReason;

    @Column(length = 100)
    @JsonIgnore
    private String actionRequestId;

    @Column(length = 64, nullable = false)
    @JsonIgnore
    private String fingerprint;

    @Column(length = 65535, nullable = false)
    @JsonIgnore
    private String inputJson;
}
