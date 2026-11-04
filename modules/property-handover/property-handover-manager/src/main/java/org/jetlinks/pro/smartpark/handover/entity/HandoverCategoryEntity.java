package org.jetlinks.pro.smartpark.handover.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** Persisted handover category; tenant/application/project fields are server controlled. */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "sp_handover_category",
        indexes = {
            @Index(
                    name = "idx_ho_category_scope",
                    columnList = "tenantId,applicationId,projectId,deleted"),
            @Index(name = "uk_ho_category_active", columnList = "activeKey", unique = true)
        })
public class HandoverCategoryEntity extends HandoverEntity {
    @Column(length = 64)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String activeKey;

    @Column(length = 128, nullable = false)
    private String name;

    @Column(length = 64)
    private String parentId;

    @Column private Integer sortIndex;

    @Column(length = 2000)
    private String description;

    @Column private Boolean enabled;
}
