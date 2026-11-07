package org.jetlinks.pro.property.base.command;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.base.archive.PropertyHouseContext;

import reactor.core.publisher.Mono;

import java.io.Serial;

/**
 * 校验项目、末端及已完善房屋资料后，返回当前有效客户；来源失败不能降级为空快照。
 *
 * @see PropertyHouseContext
 * @since 2.20.0
 */
public class QueryPropertyHouseContextCommand
        extends AbstractCommand<Mono<PropertyHouseContext>, QueryPropertyHouseContextCommand> {
    @Serial private static final long serialVersionUID = 1L;
    public static final String SERVICE_ID = QueryPropertyHousesCommand.SERVICE_ID;

    public String getProjectId() {
        var value = getOrNull("projectId", String.class);
        return value;
    }

    public void setProjectId(String value) {
        with("projectId", value);
    }

    public String getSpaceId() {
        var value = getOrNull("spaceId", String.class);
        return value;
    }

    public void setSpaceId(String value) {
        with("spaceId", value);
    }

    /** 仅在本次基础服务读取事务内锁住项目；不提供跨进程事务或延续到收费提交的锁。 */
    public boolean isForUpdate() {
        var value = getOrNull("forUpdate", Boolean.class);
        return value == null ? false : value;
    }

    public void setForUpdate(boolean value) {
        with("forUpdate", value);
    }
}
