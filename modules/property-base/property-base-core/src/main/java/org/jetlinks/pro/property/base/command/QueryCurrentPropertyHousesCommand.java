package org.jetlinks.pro.property.base.command;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.base.archive.PropertyOwnerHouse;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 读取认证本人有效客户关系，不接受用户ID；项目、空间仅为缩小条件。 提供方校验已激活的应用 Scope 或租户资产及有效档案；存在租户时继续取交集，故障不降级为空授权。
 *
 * @since 2.20.0
 * @see PropertyOwnerHouse
 */
public class QueryCurrentPropertyHousesCommand
        extends AbstractCommand<Mono<List<PropertyOwnerHouse>>, QueryCurrentPropertyHousesCommand> {
    public static final String SERVICE_ID = "propertyService:ownerHouse";

    public String getProjectId() {
        return getOrNull("projectId", String.class);
    }

    public void setProjectId(String value) {
        with("projectId", value);
    }

    public String getSpaceId() {
        return getOrNull("spaceId", String.class);
    }

    public void setSpaceId(String value) {
        with("spaceId", value);
    }
}
