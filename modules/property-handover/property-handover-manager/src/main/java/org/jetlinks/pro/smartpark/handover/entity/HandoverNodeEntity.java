package org.jetlinks.pro.smartpark.handover.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** Persisted handover node; tenant/application/project fields are server controlled. */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "sp_handover_node",
        indexes = {
            @Index(
                    name = "idx_ho_node_scope",
                    columnList = "tenantId,applicationId,projectId,deleted")
        })
public class HandoverNodeEntity extends HandoverEntity {
    @Column(length = 64, nullable = false, updatable = false)
    private String problemId;

    @Column(length = 32, updatable = false)
    private String action;

    @Column(length = 64, updatable = false)
    private String operatorId;

    @Column(length = 128, updatable = false)
    private String operatorName;

    @Column(updatable = false)
    private Long time;

    @Column(length = 4000, updatable = false)
    private String description;

    @Column(updatable = false)
    private Integer beforeStatus;

    @Column(updatable = false)
    private Integer afterStatus;
}
