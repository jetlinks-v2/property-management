package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 每设置或历史根账、任务类型、业务日只有一个持久执行身份；失败和重试不覆盖原业务日期。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_billing_task",
        indexes = {
            @Index(name = "idx_pm_fin_task_scope", columnList = "projectId,spaceId,businessDate"),
            @Index(name = "idx_pm_fin_task_state", columnList = "status,leaseUntil")
        })
public class BillingTaskEntity extends GenericEntity<String> {
    @javax.persistence.Transient
    public String getErrorMessage() {
        return errorCode == null
                ? null
                : org.hswebframework.web.i18n.LocaleUtils.resolveMessage(
                        errorCode,
                        org.hswebframework.web.i18n.LocaleUtils.resolveMessage(
                                "error.property_finance_task_failed", "任务执行失败，请处理后重试"));
    }

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 2000, nullable = false)
    private String fullName;

    @Column(length = 64)
    private String settingId;

    /** 无收费设置的历史根账单任务，和 settingId 二选一；身份不能通过HTTP改写。 */
    @Column(length = 64)
    private String receivableId;

    @JsonIgnore
    @Column(length = 64)
    private String tenantId;

    @Column(length = 20, nullable = false)
    private String kind;

    @Column(length = 10, nullable = false)
    private String businessDate;

    @Column(length = 20, nullable = false)
    private String status;

    @Column(nullable = false)
    private Integer attempts;

    @Column(nullable = false)
    private Long createTime;

    @Column private Long startTime;
    @Column private Long finishTime;

    @JsonIgnore
    @Column(length = 64)
    private String leaseOwner;

    @JsonIgnore
    @Column(nullable = false)
    private Long leaseUntil;

    @Column private Integer generated;
    @Column private Integer retained;

    @Column(length = 200)
    private String errorCode;
}
