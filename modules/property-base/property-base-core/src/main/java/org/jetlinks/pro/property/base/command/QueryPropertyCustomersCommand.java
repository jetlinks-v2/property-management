package org.jetlinks.pro.property.base.command;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.base.customer.PropertyCustomerInfo;
import reactor.core.publisher.Flux;

/** 已发布客户目录契约。参数保存在命令容器中，跨RPC保留项目及可选空间过滤。 */
public class QueryPropertyCustomersCommand extends AbstractCommand<Flux<PropertyCustomerInfo>, QueryPropertyCustomersCommand> {
    public static final String SERVICE_ID = "propertyService:customer";
    public String getProjectId() { return getOrNull("projectId", String.class); }
    public void setProjectId(String value) { with("projectId", value); }
    public String getSpaceId() { return getOrNull("spaceId", String.class); }
    public void setSpaceId(String value) { with("spaceId", value); }
}
