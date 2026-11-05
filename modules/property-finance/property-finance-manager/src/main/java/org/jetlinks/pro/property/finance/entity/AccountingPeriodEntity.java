package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 服务端会计账期目录，月份是稳定身份；实际日期与自然计费月独立，不开放通用删除或更新。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_account_period",
        indexes =
                @Index(
                        name = "uk_pm_fin_account_period",
                        columnList = "tenantId,projectId,accountMonth",
                        unique = true))
public class AccountingPeriodEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    @JsonIgnore
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(nullable = false)
    private Integer accountYear;

    @Column(length = 7, nullable = false)
    private String accountMonth;

    @Column(length = 10, nullable = false)
    private String startDate;

    @Column(length = 10, nullable = false)
    private String endDate;

    @Column(length = 16, nullable = false)
    private String mode;

    @Column(nullable = false)
    private Integer openingDay;

    @Column(length = 16, nullable = false)
    private String status;

    /** 当前有效关闭批次；反结清空，历史操作及来源快照仍保留。 */
    @Column(length = 64)
    private String closingOperationId;

    @Column(length = 10)
    private String closedOn;

    @Column(length = 64)
    private String closedBy;

    @Column(length = 128)
    private String closedName;
}
