package org.jetlinks.pro.smartpark.handover.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** Persisted handover item; tenant/application/project fields are server controlled. */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "sp_handover_item",
        indexes = {
            @Index(
                    name = "idx_ho_item_scope",
                    columnList = "tenantId,applicationId,projectId,deleted")
        })
public class HandoverItemEntity extends HandoverEntity {
    @Column(length = 32, nullable = false)
    private String source;

    @Column(length = 256, nullable = false)
    private String name;

    @Column(length = 128)
    private String code;

    @Column(length = 512)
    private String specification;

    @Column(length = 4000)
    private String standard;

    @Column(precision = 20, scale = 6)
    private java.math.BigDecimal quantity;

    @Column(length = 32)
    private String unit;

    @Column(length = 2000)
    private String remark;

    @Column private Boolean confirmed;
    @Column private Long confirmTime;
}
