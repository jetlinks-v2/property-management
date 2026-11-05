package org.jetlinks.pro.smartpark.handover.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.api.crud.entity.GenericEntity;

import javax.persistence.Column;

/** 应用级上下文和审计；项目业务实体另行要求 projectId，不将资料分类绑定到虚构项目。 */
@Getter
@Setter
public abstract class HandoverApplicationEntity extends GenericEntity<String> {
    @Column(length = 64, nullable = false, updatable = false)
    private String tenantId;

    @Column(length = 64, nullable = false, updatable = false)
    private String applicationId;

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
