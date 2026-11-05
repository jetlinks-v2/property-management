package org.jetlinks.pro.smartpark.handover.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.smartpark.handover.api.FileRef;

import java.util.List;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** Persisted handover problem; tenant/application/project fields are server controlled. */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "sp_handover_problem",
        indexes = {
            @Index(
                    name = "idx_ho_problem_scope",
                    columnList = "tenantId,applicationId,projectId,deleted")
        })
public class HandoverProblemEntity extends HandoverEntity {
    @Column(length = 32, nullable = false, updatable = false)
    private String source;

    @Column(length = 64, updatable = false)
    private String itemId;

    @Column(length = 128, updatable = false)
    private String clientRequestId;

    @Column(length = 64, updatable = false)
    private String requestHash;

    @Column(length = 64, updatable = false)
    private String sourceReportId;

    @Column(length = 256)
    private String title;

    @Column(length = 4000, nullable = false)
    private String description;

    @Column(length = 512)
    private String location;

    @Column(length = 64)
    private String spaceId;

    @Column(length = 512)
    private String spaceName;

    @Column(length = 64)
    private String categoryId;

    @Column(length = 128)
    private String categoryName;

    @Column(length = 64)
    private String organizationId;

    @Column(length = 128)
    private String organizationName;

    @Column(length = 32)
    private String inspectionType;

    @Column private Long inspectionTime;

    @Column(length = 64)
    private String initiatorId;

    @Column(length = 128)
    private String initiatorName;

    @Column private Integer status;
    @Column private Integer auditStage;
    @javax.persistence.Transient private List<FileRef> photos;
    @javax.persistence.Transient private List<HandoverNodeEntity> nodes;
}
