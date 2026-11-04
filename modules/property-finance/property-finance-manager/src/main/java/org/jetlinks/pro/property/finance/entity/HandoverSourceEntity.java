package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 稳定来源身份只允许一个有效交账批次；释放不删除身份或历史批次。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_handover_source",
        indexes = {
            @Index(
                    name = "uk_pm_fin_handover_source",
                    columnList = "tenantId,projectId,sourceType,sourceId",
                    unique = true),
            @Index(name = "idx_pm_fin_handover_batch", columnList = "batchId")
        })
public class HandoverSourceEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    @JsonIgnore
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 20, nullable = false)
    private String sourceType;

    @Column(length = 64, nullable = false)
    private String sourceId;

    @Column(length = 64)
    private String batchId;
}
