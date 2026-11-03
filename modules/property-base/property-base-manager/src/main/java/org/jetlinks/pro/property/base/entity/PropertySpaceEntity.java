package org.jetlinks.pro.property.base.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.Column;
import javax.persistence.Table;

/** SaaS 空间全量镜像。同步只维护来源字段，项目绑定与物业档案由独立表保存。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pm_space")
public class PropertySpaceEntity extends GenericEntity<String> {
    @Column(length = 128, nullable = false)
    private String name;

    @Column(length = 64)
    private String parentId;

    @Column(length = 64, nullable = false)
    private String rootId;

    @Column(length = 4096, nullable = false)
    private String fullName;

    /** 来源空间类型，不使用物业字典重新定义层级。 */
    @Column(length = 64)
    private String spaceTypeId;

    @Column(length = 128)
    private String spaceTypeName;

    /** 每级类型及名称组成的可读位置，随来源类型改名一起刷新。 */
    @Column(length = 32768)
    private String spaceLocation;

    @Column private Long sortIndex;

    @Column(nullable = false)
    private Boolean leaf;

    @Column(nullable = false)
    @JsonIgnore
    private Boolean deleted;

    @Column private Long sourceUpdateTime;
}
