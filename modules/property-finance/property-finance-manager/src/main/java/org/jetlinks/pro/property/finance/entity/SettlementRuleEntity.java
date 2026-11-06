package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.Column;
import javax.persistence.Table;

/** 费率阶梯是入账配置的子项；更新时保留旧子项的逻辑删除及审计记录。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pm_fin_settlement_rule")
public class SettlementRuleEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String recordedId;

    @Column(nullable = false)
    private Integer sortIndex;

    @Column(precision = 18, scale = 2, nullable = false)
    private BigDecimal minimumAmount;

    @Column(precision = 18, scale = 2)
    private BigDecimal maximumAmount;

    @Column(precision = 12, scale = 6, nullable = false)
    private BigDecimal procedureRate;
}
