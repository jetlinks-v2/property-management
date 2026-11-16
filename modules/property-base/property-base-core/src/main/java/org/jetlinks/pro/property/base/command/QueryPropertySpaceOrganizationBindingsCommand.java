package org.jetlinks.pro.property.base.command;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.base.space.PropertySpaceOrganizationBinding;
import reactor.core.publisher.Flux;
import java.util.List;

/**
 * 消费服务的空间资产绑定读取边界：沿用调用者认证、角色与作用域，返回其授权组织绑定。
 * 空列表表示不指定候选范围；单次最多 20000 个候选。失败必须传播，不能回退到过期副本。
 * @since 2.20.0
 * @see QueryPropertySpaceNodesCommand
 */
public class QueryPropertySpaceOrganizationBindingsCommand extends AbstractCommand<
        Flux<PropertySpaceOrganizationBinding>, QueryPropertySpaceOrganizationBindingsCommand> {
    public static final String SERVICE_ID = QueryPropertySpaceNodesCommand.SERVICE_ID;

    @SuppressWarnings("unchecked")
    public List<String> getSpaceIds() { return getOrNull("spaceIds", List.class); }
    public void setSpaceIds(List<String> ids) { with("spaceIds", ids); }
    @SuppressWarnings("unchecked")
    public List<String> getOrganizationIds() { return getOrNull("organizationIds", List.class); }
    public void setOrganizationIds(List<String> ids) { with("organizationIds", ids); }
}
