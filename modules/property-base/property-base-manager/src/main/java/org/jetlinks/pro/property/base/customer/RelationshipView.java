package org.jetlinks.pro.property.base.customer;

import lombok.Getter;
import lombok.Setter;

import org.jetlinks.pro.property.base.entity.PropertyCustomerEntity;
import org.jetlinks.pro.property.base.entity.PropertyCustomerRelationshipEntity;

/** 历史详情携带当前完整主档，即使主档已逻辑删除仍可解析。 */
@Getter
@Setter
public class RelationshipView extends PropertyCustomerRelationshipEntity {
    private String spaceName;
    private String fullName;
    private String spaceTypeName;
    private String spaceLocation;
    private String familyName;
    private PropertyCustomerEntity customer;
}
