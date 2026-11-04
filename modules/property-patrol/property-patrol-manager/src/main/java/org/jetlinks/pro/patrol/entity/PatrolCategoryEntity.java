package org.jetlinks.pro.patrol.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.assets.annotation.Asset;

import javax.persistence.Column;
import javax.persistence.Table;

/** 巡更分类配置；只保存业务关系，身份与授权仍由 SaaS 管理。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pt_category", indexes = @javax.persistence.Index(name = "pt_category_project", columnList = "scopeKey,projectId,ownerOrganizationId"))
@Asset(type = "patrolCategory")
public class PatrolCategoryEntity extends PatrolConfigEntity {
    @Column(length = 64)
    private String parentId;
}
