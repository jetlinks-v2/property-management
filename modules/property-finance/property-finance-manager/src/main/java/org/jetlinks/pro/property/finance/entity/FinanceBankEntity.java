package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.Column;
import javax.persistence.Table;

/** 公司入账银行采用稳定 ID；编辑资料不得删除重建，入账规则按 ID 引用。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pm_fin_bank")
public class FinanceBankEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String companyId;

    @Column(length = 100, nullable = false)
    private String bankName;

    @Column(length = 100, nullable = false)
    private String bankAccount;

    @Column(nullable = false)
    private Boolean defaultBank;

    @Column(length = 100)
    private String financingInstitution;

    @Column(length = 100)
    private String jointLineNumber;

    @Column(length = 100)
    private String accountName;

    @Column(length = 100)
    private String accountNumber;

    @Column(length = 64)
    private String accountBookId;
}
