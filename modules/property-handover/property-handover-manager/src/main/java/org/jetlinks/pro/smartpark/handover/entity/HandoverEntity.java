package org.jetlinks.pro.smartpark.handover.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.api.crud.entity.GenericEntity;

import javax.persistence.Column;

/** Mandatory context, logical deletion and compare-and-set version shared by business records. */
@Getter
@Setter
public abstract class HandoverEntity extends GenericEntity<String> {
    @Column(length = 64, nullable = false, updatable = false)
    private String tenantId;

    @Column(length = 64, nullable = false, updatable = false)
    private String applicationId;

    @Column(length = 64, nullable = false, updatable = false)
    private String projectId;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false)
    private Boolean deleted;

    @Column(length = 64, updatable = false)
    private String creatorId;

    @Column(length = 128, updatable = false)
    private String creatorName;

    @Column(updatable = false)
    private Long createTime;

    @Column private Long modifyTime;
}
