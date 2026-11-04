package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.*;

/** 一行代表一笔应收在一种支付方式上的核销；退款沿此来源恢复余额，不覆盖原金额。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_payment_line",
        indexes = {
            @Index(name = "idx_pm_fin_payment_line", columnList = "paymentId"),
            @Index(name = "idx_pm_fin_payment_bill", columnList = "receivableId")
        })
public class PaymentAllocationEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String paymentId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 64, nullable = false)
    private String receivableId;

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
}
