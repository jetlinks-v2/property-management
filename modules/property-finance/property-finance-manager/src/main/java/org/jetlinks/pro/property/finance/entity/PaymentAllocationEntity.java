package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.*;

/** 一行代表一种支付方式的实收来源；可关联应收或临时款，退款保留原金额及来源。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_payment_line",
        indexes = {
            @Index(name = "idx_pm_fin_payment_line", columnList = "paymentId"),
            @Index(name = "idx_pm_fin_payment_bill", columnList = "receivableId"),
            @Index(name = "idx_pm_fin_payment_standard", columnList = "standardId")
        })
public class PaymentAllocationEntity extends FinanceEntity {
    @Column(length = 32, nullable = false)
    private String sourceType = "RECEIVABLE";

    @Column(length = 64, nullable = false)
    private String paymentId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String spaceId;

    /** 临时收款没有应收来源，不生成占位账单。 */
    @Column(length = 64)
    private String receivableId;

    @Column(length = 64)
    private String standardId;

    @Column(length = 64)
    private String subjectId;

    @Column(length = 64, nullable = false)
    private String customerId;

    @Column(length = 128, nullable = false)
    private String customerName;

    @Column(length = 2000, nullable = false)
    private String fullName;

    @Column(length = 100, nullable = false)
    private String subjectName;

    @Column(length = 10, nullable = false)
    private String calcStartDate;

    @Column(length = 10, nullable = false)
    private String calcEndDate;

    @Column(nullable = false)
    private Integer sortIndex;

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

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal amount;

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal refundedAmount;

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal invoiceOccupiedAmount = BigDecimal.ZERO.setScale(2);

    /** 待审核退款的额度占用，不代表已经发生退款。 */
    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal refundReservedAmount = BigDecimal.ZERO.setScale(2);
}
