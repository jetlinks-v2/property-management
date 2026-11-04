package org.jetlinks.pro.property.base.command;

import org.jetlinks.core.command.AbstractCommand;
import reactor.core.publisher.Flux;
import org.jetlinks.pro.property.base.space.PropertySpaceInfo;

/** 读取有权项目当前根绑定下的空间；同时检查组织及空间资产，解绑立即影响后续查询。
 * 调用方保留 Authentication、AssetsHolder、TenantHolder 上下文；不接受伪造身份参数，不触发同步。 */
public class QueryPropertySpacesCommand extends AbstractCommand<Flux<PropertySpaceInfo>, QueryPropertySpacesCommand> {
    public static final String SERVICE_ID = "propertyService:directory";

    public String getProjectId() {
        return getOrNull("projectId", String.class);
    }

    /** 使用命令参数容器，确保 RPC 传输及提供方重建时保留项目范围。 */
    public void setProjectId(String projectId) {
        with("projectId", projectId);
    }
}
