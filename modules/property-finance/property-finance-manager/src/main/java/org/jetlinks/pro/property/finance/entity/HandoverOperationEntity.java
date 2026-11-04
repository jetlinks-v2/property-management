package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 每次状态变更和业务登记同事务；操作身份同时用于成功请求恢复。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_handover_log",
        indexes =
                @Index(
                        name = "idx_pm_fin_handover_log",
                        columnList = "projectId,batchId,createTime"))
public class HandoverOperationEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String batchId;

    @Column(length = 20, nullable = false)
    private String action;

    @Column(length = 64, nullable = false)
    @JsonIgnore
    private String requestFingerprint;

    @Column(length = 20)
    private String beforeStatus;

    @Column(length = 20, nullable = false)
    private String afterStatus;

    @Column(length = 1000)
    private String reason;
}
