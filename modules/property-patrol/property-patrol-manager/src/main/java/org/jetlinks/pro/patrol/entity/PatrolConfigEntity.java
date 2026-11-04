package org.jetlinks.pro.patrol.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.api.crud.entity.ExtendableEntity;
import org.hswebframework.web.api.crud.entity.RecordCreationEntity;
import org.hswebframework.web.api.crud.entity.RecordModifierEntity;

import javax.persistence.Column;

/** 配置资产的审计字段。项目与应用归属由平台实体事件及资产绑定维护，不接受前端归属字段。 */
@Getter
@Setter
public abstract class PatrolConfigEntity extends ExtendableEntity<String>
        implements RecordCreationEntity, RecordModifierEntity {
    @Column(length = 128, nullable = false)
    @NotBlank
    @Size(max = 128)
    private String name;

    @Column(length = 160, nullable = false, updatable = false)
    private String scopeKey;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(length = 512)
    @Size(max = 512)
    private String description;

    @Column(length = 64, updatable = false)
    private String creatorId;

    /** 操作时的可信姓名快照；历史缺失留空，不按当前人员名称回填。 */
    @Column(length = 128, updatable = false)
    private String creatorName;

    @Column(length = 128)
    private String modifierName;

    @Column(updatable = false)
    private Long createTime;

    @Column(length = 64)
    private String modifierId;

    @Column private Long modifyTime;
}
