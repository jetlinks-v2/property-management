package org.jetlinks.pro.property.base.customer;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import org.jetlinks.pro.property.base.entity.PropertyCustomerEntity;
import java.util.List;
import java.util.Objects;

/** 关系只包含当前请求可访问的空间范围，不返回客户的其他房屋。 */
@Getter
@Setter
public class CustomerView extends PropertyCustomerEntity {
    private List<RelationshipView> relationships = List.of();

    /** 仅用于当前列表查询，基于已经按资产范围裁剪的当前关系派生。 */
    @JsonIgnore
    public List<String> getNature() {
        return relationships.stream()
                .map(RelationshipView::getNature)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
