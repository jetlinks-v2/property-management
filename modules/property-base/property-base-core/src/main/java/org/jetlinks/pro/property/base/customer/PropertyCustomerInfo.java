package org.jetlinks.pro.property.base.customer;
import lombok.Getter;import lombok.Setter;import java.util.List;
/** 跨应用最小客户投影，不含证件及家庭隐私；房屋ID仅包含当前有效且调用人有权的关系。 */
@Getter @Setter public class PropertyCustomerInfo {
 private String id;private String projectId;private String userId;private String name;private String telephone;private List<String> spaceIds;
}
