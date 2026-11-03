package org.jetlinks.pro.property.base.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.EnumCodec;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.property.base.organization.OrganizationType;

import javax.persistence.Column;
import javax.persistence.Table;
import java.util.List;

/**
 * SaaS 组织镜像，主键直接复用全局组织 ID，权限仍关联 SaaS organization 资产。
 * 同步仅写来源字段，类型及标注版本只由专用接口修改，不开放通用 CRUD。
 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pm_organization")
public class PropertyOrganizationEntity extends GenericEntity<String> {
    @Column(length = 64, nullable = false)
    private String name;

    @Column(length = 64)
    private String parentId;

    @Column(length = 128)
    private String code;

    @Column private Long sortIndex;

    @Column(length = 32)
    @EnumCodec
    @ColumnType(javaType = String.class)
    private OrganizationType organizationType;

    @Column(nullable = false)
    private Long version;

    @Column(nullable = false)
    @JsonIgnore
    private Boolean deleted;

    @Column @JsonIgnore private Long deletedTime;
    @Column private Long sourceUpdateTime;

    @Column(length = 64)
    private String modifierId;

    @Column(length = 128)
    private String modifierName;

    @Column private Long modifyTime;

    @Schema(description = "权限过滤后可见的下级组织")
    private List<PropertyOrganizationEntity> children;
}
