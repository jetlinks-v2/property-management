package org.jetlinks.pro.property.base.customer;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

/** 供既有业务消费的只读客户快照；房屋仅包含当前调用者有权读取的有效关系。 */
@Getter @Setter
public class PropertyCustomerInfo {
    private String id;
    private String projectId;
    private String userId;
    private String name;
    private String telephone;
    private List<String> spaceIds;
}
