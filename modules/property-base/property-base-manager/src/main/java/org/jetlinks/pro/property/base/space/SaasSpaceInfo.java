package org.jetlinks.pro.property.base.space;

import lombok.Getter;
import lombok.Setter;

/** 只接收空间命令的来源字段，不依赖 SaaS ORM 或 manager，实现 RPC Map 的类型还原。 */
@Getter
@Setter
public class SaasSpaceInfo {
    private String id;
    private String name;
    private String parentId;
    private Long sortIndex;
    private String spaceTypeId;
    private String spaceTypeName;
}
