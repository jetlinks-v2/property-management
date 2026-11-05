package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 票本和单号操作事实，与业务修改共用事务，逻辑删除后仍可追溯。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_allocation_log",
        indexes = {
            @Index(
                    name = "idx_pm_fin_alloc_log_book",
                    columnList = "tenantId,projectId,bookId,createTime")
        })
public class AllocationLogEntity extends FinanceEntity {
    @JsonIgnore
    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String bookId;

    @Column(length = 64)
    private String numberId;

    @Column(length = 32, nullable = false)
    private String action;

    @Column(length = 500)
    private String reason;

    @Column(length = 16000)
    private String beforeSnapshot;

    @Column(length = 16000)
    private String afterSnapshot;
}
