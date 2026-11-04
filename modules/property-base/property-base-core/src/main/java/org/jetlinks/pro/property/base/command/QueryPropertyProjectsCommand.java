package org.jetlinks.pro.property.base.command;

import lombok.Getter;
import lombok.Setter;
import org.jetlinks.core.command.AbstractCommand;
import reactor.core.publisher.Flux;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;

/** 查询当前租户及当前资产范围内标注为项目的组织；无参数，真实无数据返回空流。
 * 调用方保留 Authentication、AssetsHolder、TenantHolder 上下文；不接受伪造身份参数，不触发同步。 */
@Getter @Setter
public class QueryPropertyProjectsCommand extends AbstractCommand<Flux<PropertyOrganizationInfo>, QueryPropertyProjectsCommand> {
    public static final String SERVICE_ID = "propertyService:directory";
}
