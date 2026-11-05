package org.jetlinks.pro.smartpark.handover.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** Persisted handover responsibility; tenant/application/project fields are server controlled. */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "sp_handover_responsibility",
        indexes = {
            @Index(
                    name = "idx_ho_responsibility_scope",
                    columnList = "tenantId,applicationId,projectId,deleted"),
            @Index(name = "uk_ho_responsibility_active", columnList = "activeKey", unique = true)
        })
public class HandoverResponsibilityEntity extends HandoverEntity {
    @Column(length = 64)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String activeKey;

    @Column(length = 64, nullable = false)
    private String organizationId;

    @Column(length = 128)
    private String name;

    @Column(length = 128)
    private String contactName;

    @Column(length = 64)
    private String telephone;

    @Column(length = 256)
    private String email;

    @Column(length = 512)
    private String address;

    @Column private Integer sortIndex;
    @Column private Boolean enabled;

    @Column(length = 2000)
    private String remark;
}
