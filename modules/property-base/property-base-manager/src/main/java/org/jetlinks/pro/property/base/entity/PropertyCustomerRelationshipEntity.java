package org.jetlinks.pro.property.base.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 一次客户与房屋的关联；结束保留历史，误建仅逻辑删除。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_customer_relation",
        indexes = {
            @Index(name = "idx_pm_relation_space", columnList = "projectId,spaceId"),
            @Index(name = "idx_pm_relation_customer", columnList = "customerId")
        })
public class PropertyCustomerRelationshipEntity extends ArchiveEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String customerId;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 64)
    private String nature;

    @Column(length = 64)
    private String familyId;

    @Column(nullable = false)
    private Long startTime;

    @Column private Long endTime;

    @Column(length = 512)
    private String endReason;
}
