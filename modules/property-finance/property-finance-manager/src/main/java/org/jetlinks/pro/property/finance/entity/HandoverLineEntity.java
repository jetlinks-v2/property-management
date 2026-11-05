package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.*;

/** 冻结交账明细，不随客户、科目或原收款后续退款变化；资产权限仍用历史spaceId。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_handover_line",
        indexes =
                @Index(name = "idx_pm_fin_handover_line", columnList = "projectId,batchId,spaceId"))
public class HandoverLineEntity extends FinanceEntity {
    @Transient private String receiptNumbers;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String batchId;

    @Column(length = 20, nullable = false)
    private String sourceType;

    @Column(length = 64, nullable = false)
    private String sourceId;

    @Column(length = 64, nullable = false)
    private String originalLineId;

    @Column(length = 64)
    private String receivableId;

    @Column(length = 30)
    private String businessType;

    @Column(length = 10)
    private String calcStartDate;

    @Column(length = 10)
    private String calcEndDate;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 2000, nullable = false)
    private String fullName;

    @Column(length = 128, nullable = false)
    private String customerName;

    @Column(length = 64, nullable = false)
    private String companyId;

    @Column(length = 100, nullable = false)
    private String companyName;

    @Column(length = 100, nullable = false)
    private String subjectName;

    @Column(length = 100, nullable = false)
    private String methodText;

    @Column(length = 100)
    private String bankName;

    @Column(length = 30, nullable = false)
    private String occurredAt;

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal amount;

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal cashAmount;
}
