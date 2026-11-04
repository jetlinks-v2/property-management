package org.jetlinks.pro.property.api;

import lombok.Getter;
import lombok.Setter;
import java.util.List;
/** Cloud 公开目录查询的最小投影；不包含密码、令牌或认证配置。 */
@Getter @Setter
public class CloudDirectoryRecord {
 private String id;
 private String name;
 private String username;
 private String parentId;
 private String path;
 private String code;
 private String spaceTypeId;
 private Byte status;
 private List<String> organizationIds;
 private List<String> roleIds;
}
