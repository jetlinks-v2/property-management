package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.*;

/** 退款逐笔关联原核销，不复用原行或将原始实收金额改成净额。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_refund_line",
        indexes = {
            @Index(name = "idx_pm_fin_refund_line", columnList = "refundId"),
            @Index(name = "idx_pm_fin_refund_source", columnList = "sourceLineId"),
            @Index(name = "idx_pm_fin_refund_room", columnList = "projectId,spaceId")
        })
public class RefundAllocationEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String refundId;

    @Column(length = 64, nullable = false)
    private String sourceLineId;

    @Column(length = 64, nullable = false)
    private String paymentId;

    @Column(length = 64)
    private String receivableId;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 128, nullable = false)
    private String customerName;

    @Column(length = 2000, nullable = false)
    private String fullName;

    @Column(length = 100, nullable = false)
    private String subjectName;

    @Column(nullable = false)
    private Integer sortIndex;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal amount;
}
