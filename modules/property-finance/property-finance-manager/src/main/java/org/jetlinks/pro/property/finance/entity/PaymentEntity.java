package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.*;

/** 实收主单不开放直接改金额/删除；重复请求沿稳定主键复用同一笔已记账事实。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_payment",
        indexes = {@Index(name = "idx_pm_fin_payment_scope", columnList = "projectId,paidAt")})
public class PaymentEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String companyId;

    @Column(length = 100, nullable = false)
    private String companyName;

    @Column(length = 64, nullable = false)
    @JsonIgnore
    private String requestFingerprint;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(length = 128, nullable = false)
    private String payerName;

    @Column(length = 64)
    private String payerRelationshipId;

    @Column(length = 64)
    private String payerUserId;

    @Column(length = 30, nullable = false)
    private String paidAt;

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal amount;

    @Column(length = 1000)
    private String remark;

    @Column(length = 255)
    private String attachmentName;

    @Column(length = 64)
    private String attachmentFileId;
}
