package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 会计日历变更审计，与整年目录在同一事务提交；modifier 保留实际操作人。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_calendar_log",
        indexes =
                @Index(
                        name = "idx_pm_fin_calendar_log",
                        columnList = "tenantId,projectId,accountYear"))
public class AccountingCalendarOperationEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    @JsonIgnore
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(nullable = false)
    private Integer accountYear;

    @Column(length = 16, nullable = false)
    private String action;

    @Column(length = 60000, nullable = false)
    private String detailJson;
}
