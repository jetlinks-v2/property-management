package org.jetlinks.pro.property.base.command;

import org.jetlinks.core.command.AbstractCommand;
import reactor.core.publisher.Flux;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;

/** 查询当前租户或应用资产范围内有权读取的用户身份。ids 为空查询全部；employeesOnly 仅返回启用的员工，最多20000项。
 * 调用方保留 Authentication、AssetsHolder、TenantHolder 上下文；不接受伪造身份参数，不触发同步。 */
public class QueryPropertyUsersCommand extends AbstractCommand<Flux<PropertyUserInfo>, QueryPropertyUsersCommand> {
    public static final String SERVICE_ID = "propertyService:directory";

    @SuppressWarnings("unchecked")
    public java.util.List<String> getIds() {
        var ids = getOrNull("ids", java.util.List.class);
        return ids == null ? java.util.List.of() : ids;
    }

    /** 参数写入框架负载，远程提供方才能执行同样的用户和员工筛选。 */
    public void setIds(java.util.List<String> ids) {
        with("ids", ids);
    }

    public boolean isEmployeesOnly() {
        return Boolean.TRUE.equals(getOrNull("employeesOnly", Boolean.class));
    }

    public void setEmployeesOnly(boolean employeesOnly) {
        with("employeesOnly", employeesOnly);
    }
}
