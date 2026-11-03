package org.jetlinks.pro.property.base.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;

import javax.persistence.Column;
import javax.persistence.Table;

/** 仅保存 SaaS 用户的直接组织，供分页关联筛选；不把祖先组织当作直接归属。 */
@Getter
@Setter
@Table(name = "pm_user_org")
public class PropertyUserOrganizationEntity extends GenericEntity<String> {
    @Column(length = 64, nullable = false)
    @JsonIgnore
    private String userId;

    @Column(length = 64, nullable = false)
    private String orgId;

    @Column(length = 128)
    private String name;

    @Column(length = 2048)
    private String fullName;

    @Column(nullable = false)
    @JsonIgnore
    private Boolean deleted = false;
}
