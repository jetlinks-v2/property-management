package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.Column;
import javax.persistence.Table;

@Getter
@Setter
@EnableEntityEvent
@Table(name = "pm_fin_arrears_rule")
public class ArrearsRuleEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String configId;

    @Column(nullable = false)
    private Integer overdueDays;

    @Column(length = 64, nullable = false)
    private String levelCode;

    @Column(nullable = false)
    private Integer sortIndex;
}
