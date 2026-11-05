package org.jetlinks.pro.property.base.command;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.base.house.PropertyHouseholdInfo;
import reactor.core.publisher.Flux;
import java.io.Serial;

/**
 * 查询当前有权项目内的有效客户房屋关系，不推导业主业务资格。
 * mine=true 只读当前认证账号关系；false 仍受项目和空间 AssetsHolder 限制。
 * 参数写入命令负载以支持远程传输；依赖故障直接传播，禁止回退为无权限目录。
 * @see PropertyHouseholdInfo
 * @since 2.20.0
 */
public class QueryPropertyHouseholdsCommand extends AbstractCommand<Flux<PropertyHouseholdInfo>, QueryPropertyHouseholdsCommand> {
    @Serial private static final long serialVersionUID = 1L;
    public static final String SERVICE_ID = "propertyService:household";
    public String getProjectId() { return getOrNull("projectId", String.class); }
    public void setProjectId(String projectId) { with("projectId", projectId); }
    public String getSpaceId() { return getOrNull("spaceId", String.class); }
    public void setSpaceId(String spaceId) { with("spaceId", spaceId); }
    /** 可按客户主档查询其有效房屋；spaceId 与 customerId 同时提供时取交集。 */
    public String getCustomerId() { return getOrNull("customerId", String.class); }
    public void setCustomerId(String customerId) { with("customerId", customerId); }
    public boolean isMine() { return Boolean.TRUE.equals(getOrNull("mine", Boolean.class)); }
    public void setMine(boolean mine) { with("mine", mine); }
}
