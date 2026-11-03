package org.jetlinks.pro.property.base.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import javax.persistence.Column;
import javax.persistence.Table;

/** 家庭限定在一个项目的一套房屋；可选列表由当前有效成员决定。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pm_customer_family")
public class PropertyCustomerFamilyEntity extends ArchiveEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 128, nullable = false)
    private String name;
}
