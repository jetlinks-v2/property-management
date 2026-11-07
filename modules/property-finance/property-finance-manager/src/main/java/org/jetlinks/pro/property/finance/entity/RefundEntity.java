package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.*;

/** 不可修改的人工退款资金事实，金额为正；统计净实收时以收款减退款。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_refund",
        indexes = @Index(name = "idx_pm_fin_refund_scope", columnList = "projectId,refundedAt"))
public class RefundEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    /** CASH为实际出款，BALANCE为原冲抵退回；报表不得把BALANCE计入现金支出。 */
    @Column(length = 16, nullable = false)
    private String destination = "CASH";

    @Column(length = 64)
    private String accountId;

    @Column(length = 64, nullable = false)
    private String companyId;

    @Column(length = 100, nullable = false)
    private String companyName;

    @JsonIgnore
    @Column(length = 64, nullable = false)
    private String requestFingerprint;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(length = 128, nullable = false)
    private String receiverName;

    @Column(length = 30, nullable = false)
    private String refundedAt;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(length = 100, nullable = false)
    private String method;

    @Column(length = 100, nullable = false)
    private String methodText;

    @Column(length = 64)
    private String recordedId;

    @Column(length = 64)
    private String bankId;

    @Column(length = 100)
    private String bankName;

    @Column(length = 100)
    private String bankAccount;

    @Column(length = 1000, nullable = false)
    private String reason;

    @Column(length = 64)
    private String attachmentFileId;

    @Column(length = 255)
    private String attachmentName;
}
