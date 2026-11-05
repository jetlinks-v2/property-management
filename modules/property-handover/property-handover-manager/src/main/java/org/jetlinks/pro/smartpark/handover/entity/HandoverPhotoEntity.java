package org.jetlinks.pro.smartpark.handover.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** Persisted handover photo; tenant/application/project fields are server controlled. */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "sp_handover_photo",
        indexes = {
            @Index(
                    name = "idx_ho_photo_scope",
                    columnList = "tenantId,applicationId,projectId,deleted")
        })
public class HandoverPhotoEntity extends HandoverEntity {
    @Column(length = 64, nullable = false, updatable = false)
    private String problemId;

    @Column(length = 64, nullable = false, updatable = false)
    private String fileId;

    @Column(length = 256)
    private String name;

    @Column(length = 128)
    private String type;

    @Column private Long size;

    @Column(length = 32)
    private String photoType;

    @Column private Integer sortIndex;
}
