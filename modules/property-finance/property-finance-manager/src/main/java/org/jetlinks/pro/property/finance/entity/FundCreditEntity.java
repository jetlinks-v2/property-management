package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.*;

/** 每笔正向资金事实的来源余额控制账；原资金流水仍然不可改删。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_fund_credit",
        indexes = {
            @Index(
                    name = "idx_pm_fin_credit_source",
                    columnList = "projectId,accountId,createTime,id")
        })
public class FundCreditEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String accountId;

    @Column(length = 64, nullable = false)
    private String entryId;

    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal originalAmount;

    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal remainingAmount;

    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal invoiceOccupiedAmount;

    @Column(nullable = false)
    private Boolean dayClosed;

    @Column(nullable = false)
    private Boolean monthClosed;
}
