package org.jetlinks.pro.property.base.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import javax.persistence.Column;
import javax.persistence.Table;

/** 主键即真实根空间 ID，保证一个根仅绑定一个组织，解绑保留版本。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pm_space_binding")
public class PropertySpaceBindingEntity extends ArchiveEntity {
    @Column(length = 64)
    private String organizationId;
}
