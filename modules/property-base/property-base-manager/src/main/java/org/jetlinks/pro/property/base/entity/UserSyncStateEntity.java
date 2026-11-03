package org.jetlinks.pro.property.base.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

import javax.persistence.Column;
import javax.persistence.Table;

/** 用户同步的集群租约和最近任务结果；不存凭据或来源完整响应。 */
@Getter
@Setter
@Table(name = "pm_user_sync_state")
public class UserSyncStateEntity extends GenericEntity<String> {
    @Column private String taskId;
    @Column private String status;
    @Column private Long leaseUntil;
    @Column private Long lastSuccessTime;
    @Column private Long lastFailureTime;

    @Column(length = 128)
    private String lastError;

    @Column private Integer added;
    @Column private Integer updated;
    @Column private Integer deleted;
}
