package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.*;

/** 同计划同应收只享一次减免。来源身份和金额永久保留，余额补入或任务重跑不能重复优惠。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_carry_remission",
        indexes =
                @Index(
                        name = "uk_pm_fin_carry_remission",
                        columnList = "projectId,planId,receivableId",
                        unique = true))
public class CarryForwardRemissionEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String planId;

    @Column(length = 64, nullable = false)
    private String receivableId;

    @Column(length = 64, nullable = false)
    private String itemId;

    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal feeReduction;

    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal lateFeeReduction;
}
