package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.*;

/** 不可改删的资金来源消耗分配，任何出账均能追溯到原存入或转入流水。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_fund_consumption",
        indexes = {
            @Index(
                    name = "idx_pm_fin_consumption_entry",
                    columnList = "projectId,accountId,debitEntryId"),
            @Index(name = "idx_pm_fin_consumption_credit", columnList = "projectId,creditId")
        })
public class FundConsumptionEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String accountId;

    @Column(length = 64, nullable = false)
    private String creditId;

    @Column(length = 64, nullable = false)
    private String debitEntryId;

    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal amount;
}
