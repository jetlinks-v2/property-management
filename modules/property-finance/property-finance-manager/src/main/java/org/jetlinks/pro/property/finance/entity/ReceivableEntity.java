package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.*;

/** 自动应收保存独立计算快照和财务事实；稳定账期身份在逻辑删除后仍保留。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_receivable",
        indexes = {
            @Index(
                    name = "uk_pm_fin_recv_period",
                    columnList = "settingId,periodStart,periodEnd",
                    unique = true),
            @Index(
                    name = "idx_pm_fin_recv_scope",
                    columnList = "projectId,spaceId,subjectId,deleted"),
            @Index(name = "idx_pm_fin_recv_customer", columnList = "customerId,deleted")
        })
public class ReceivableEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 2000, nullable = false)
    private String fullName;

    @Column(length = 64, nullable = false)
    private String subjectId;

    @Column(length = 100, nullable = false)
    private String subjectName;

    @Column(length = 64, nullable = false)
    private String standardId;

    @Column(length = 30, nullable = false)
    private String standardName;

    @Column(length = 64, nullable = false)
    private String settingId;

    @Column(nullable = false)
    private Integer configVersion;

    @Column(length = 64, nullable = false)
    private String customerId;

    @Column(length = 64, nullable = false)
    private String customerRelationshipId;

    @Column(length = 128, nullable = false)
    private String customerName;

    @Column(length = 10, nullable = false)
    private String periodStart;

    @Column(length = 10, nullable = false)
    private String periodEnd;

    @Column(length = 10, nullable = false)
    private String calcStartDate;

    @Column(length = 10, nullable = false)
    private String calcEndDate;

    @Column(length = 10, nullable = false)
    private String dueDate;

    @Column(length = 10, nullable = false)
    private String accruedAsOfDate;

    @Column(length = 10, nullable = false)
    private String calculatedAsOfDate;

    @Column(length = 20, nullable = false)
    private String periodStatus;

    @JsonIgnore
    @Column(length = 60000, nullable = false)
    private String snapshotJson;

    @Column(nullable = false)
    private Integer moneyScale;

    @Column(nullable = false)
    private Boolean frozen;

    @Column(nullable = false)
    private Boolean paymentLocked;

    @Column(nullable = false)
    private Boolean bankCollectionLocked;

    @Column(nullable = false)
    private Boolean lateFeeLocked;

    @Column(nullable = false)
    private Boolean financialClosed;

    // 可空兼容已出账数据；null与false均表示未占用，新账明确写false。
    @Column private Boolean discountLocked;
    @Column private Boolean chargeLocked;
    @Column private Boolean split;

    @Column(length = 10)
    private String paidDate;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal contractReceivableAmount;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal accruedReceivableAmount;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal adjustmentAmount;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal assessmentAmount;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal temporaryDispatchAmount;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal feeReductionAmount;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal accruedLateFeeAmount;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal lateFeeReductionAmount;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal finalLateFeeAmount;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal actualReceivableAmount;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal paidAmount;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal arrears;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal invoiceOccupiedAmount;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal paymentOccupiedAmount;
}
