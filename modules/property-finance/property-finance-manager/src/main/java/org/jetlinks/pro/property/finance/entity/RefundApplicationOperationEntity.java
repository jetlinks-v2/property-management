package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 申请资料和审核状态的变更日志，与额度占用、资金事实同事务保存。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_refund_app_log",
        indexes =
                @Index(
                        name = "idx_pm_fin_refund_log",
                        columnList = "projectId,applicationId,createTime"))
public class RefundApplicationOperationEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String applicationId;

    @Column(length = 20, nullable = false)
    private String action;

    @Column(length = 1000)
    private String reason;

    @Column(length = 65535, nullable = false)
    private String snapshotJson;
}
