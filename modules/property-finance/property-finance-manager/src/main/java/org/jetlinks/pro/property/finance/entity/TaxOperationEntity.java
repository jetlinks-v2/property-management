package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Table;

/** 与税目写入同事务的审计快照；逻辑删除后仍可追溯，不允许外部写入日志。 */
@Getter
@Setter
@Table(name = "pm_fin_tax_operation")
public class TaxOperationEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String taxItemId;

    @Column(length = 64, nullable = false)
    private String companyId;

    @Column(length = 32, nullable = false)
    private String operation;

    @Column(length = 4000)
    private String beforeSnapshot;

    @Column(length = 4000)
    private String afterSnapshot;
}
