package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.*;

/** 以房屋为查询授权边界的申请明细；记录实际占用额，驳回按原额释放。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_refund_app_line",
        indexes =
                @Index(name = "idx_pm_fin_refund_app_line", columnList = "projectId,applicationId"))
public class RefundApplicationLineEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 20, nullable = false)
    private String status = "PENDING";

    @Column(length = 64, nullable = false)
    private String applicationId;

    @Column(length = 64, nullable = false)
    private String sourceId;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 2000, nullable = false)
    private String fullName;

    @Column(length = 128, nullable = false)
    private String customerName;

    @Column(length = 100, nullable = false)
    private String subjectName;

    @Column(length = 10)
    private String calcStartDate;

    @Column(length = 10)
    private String calcEndDate;

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal amount;
}
