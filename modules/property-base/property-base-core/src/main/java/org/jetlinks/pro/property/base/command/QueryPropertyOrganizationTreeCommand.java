package org.jetlinks.pro.property.base.command;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import reactor.core.publisher.Flux;

import java.io.Serial;

/**
 * 查询物业本地镜像的完整组织树，返回根节点流，节点携带物业类型及下级组织。
 * 无查询参数，不按类型裁剪；只返回调用者有权查看且未逻辑删除的组织，未标注的上级保留。
 * 调用方必须沿用当前 Authentication / AssetsHolder 上下文，匿名与查询异常直接传播。
 * 本命令只读，不触发 SaaS 同步，不开放组织或类型的修改能力。
 *
 * @see PropertyOrganizationInfo
 * @since 2.20.0
 */
public class QueryPropertyOrganizationTreeCommand
        extends AbstractCommand<
                Flux<PropertyOrganizationInfo>, QueryPropertyOrganizationTreeCommand> {
    @Serial private static final long serialVersionUID = 1L;

    /** 基础组织服务的稳定标识；消费方仅依赖本 core，通过框架执行显式命令。 */
    public static final String SERVICE_ID = "propertyService:organization";
}
