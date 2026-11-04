package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.*;

/** 不可改删的资金事实；cashAmount区分现金流和内部转账，transfer两边共用transactionId。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_fund_entry",
        indexes = {
            @Index(name = "idx_pm_fin_fund_entry", columnList = "projectId,accountId,createTime"),
            @Index(name = "idx_pm_fin_fund_tx", columnList = "transactionId")
        })
public class FundEntryEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 64, nullable = false)
    private String accountId;

    @Column(length = 64, nullable = false)
    private String transactionId;

    @Column(length = 30, nullable = false)
    private String action;

    @Column(length = 64, nullable = false)
    private String companyId;

    @Column(length = 100, nullable = false)
    private String companyName;

    @Column(length = 2000, nullable = false)
    private String fullName;

    @Column(length = 128, nullable = false)
    private String customerName;

    @Column(length = 100, nullable = false)
    private String subjectName;

    @Column(length = 100, nullable = false)
    private String standardName;

    @Column(length = 64, nullable = true)
    private String peerAccountId;

    @Column(length = 64, nullable = true)
    private String paymentId;

    @Column(length = 64)
    private String sourceEntryId;

    @Column(length = 64)
    private String refundId;

    @Column(length = 30, nullable = false)
    private String occurredAt;

    @Column(length = 128, nullable = false)
    private String payerName;

    @Column(length = 100, nullable = false)
    private String method;

    @Column(length = 100, nullable = false)
    private String methodText;

    @Column(length = 64, nullable = true)
    private String recordedId;

    @Column(length = 100, nullable = true)
    private String bankName;

    @Column(length = 1000, nullable = false)
    private String reason;

    @Column(length = 64, nullable = true)
    private String attachmentFileId;

    @Column(length = 255, nullable = true)
    private String attachmentName;

    @JsonIgnore
    @Column(length = 64, nullable = false)
    private String requestFingerprint;

    @JsonIgnore
    @Column(length = 12000, nullable = false)
    private String inputSnapshotJson;

    /** 原始存入的客户、科目及标准税快照；内部来源使用，普通详情不暴露原始JSON。 */
    @JsonIgnore
    @Column(length = 16000)
    private String documentSnapshotJson;

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal amount;

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal cashAmount;

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal beforeBalance;

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal afterBalance;
}
