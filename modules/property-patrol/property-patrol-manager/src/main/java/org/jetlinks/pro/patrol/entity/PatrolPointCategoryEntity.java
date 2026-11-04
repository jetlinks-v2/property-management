package org.jetlinks.pro.patrol.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 点位与分类的独立关系；取消保留停用行，物理点位和历史事实继续保留。 */
@Getter
@Setter
@Table(name = "pt_point_category", indexes = {
        @Index(name = "pt_point_category_unique", columnList = "pointId,categoryId", unique = true),
        @Index(name = "pt_category_points", columnList = "categoryId,enabled,pointId")
})
public class PatrolPointCategoryEntity extends GenericEntity<String> {
    @Column(length = 64, nullable = false, updatable = false)
    private String pointId;
    @Column(length = 64, nullable = false, updatable = false)
    private String categoryId;
    @Column(length = 160, nullable = false, updatable = false)
    private String scopeKey;
    @Column(nullable = false)
    private boolean enabled;
    @Column(length = 64, updatable = false)
    private String creatorId;
    @Column(updatable = false)
    private Long createTime;
    @Column(length = 64)
    private String modifierId;
    @Column
    private Long modifyTime;
}
