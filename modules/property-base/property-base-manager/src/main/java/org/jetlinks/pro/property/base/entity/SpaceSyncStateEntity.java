package org.jetlinks.pro.property.base.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

import javax.persistence.Column;
import javax.persistence.Table;

/** 固定 SaaS 来源的全量同步租约及最近结果；不存凭据或个人查询条件。 */
@Getter
@Setter
@Table(name = "pm_space_sync_state")
public class SpaceSyncStateEntity extends GenericEntity<String> {
    @Column private String leaseOwner;
    @Column private Long leaseUntil;
    @Column private Long lastSuccessTime;
    @Column private Long lastFailureTime;

    @Column(length = 128)
    private String lastError;

    @Column private Integer added;
    @Column private Integer updated;
    @Column private Integer deleted;
}
