package org.jetlinks.pro.property.base.command;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.base.organization.PropertyProjectSeal;
import reactor.core.publisher.Mono;

/** 读取有权project的章；继承调用者认证/资产范围，无章返回空，故障直接传播。 */
public class GetPropertyProjectSealCommand extends AbstractCommand<Mono<PropertyProjectSeal>, GetPropertyProjectSealCommand> {
    public static final String SERVICE_ID = QueryPropertyOrganizationTreeCommand.SERVICE_ID;
    public String getProjectId() { return getOrNull("projectId", String.class); }
    public void setProjectId(String id) { with("projectId", id); }
}
