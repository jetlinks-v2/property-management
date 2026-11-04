package org.jetlinks.pro.property.base.command;

import lombok.Getter;
import lombok.Setter;

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
@Getter
@Setter
public class QueryPropertyHouseContextCommand
        extends AbstractCommand<Mono<PropertyHouseContext>, QueryPropertyHouseContextCommand> {
    @Serial private static final long serialVersionUID = 1L;
    public static final String SERVICE_ID = QueryPropertyHousesCommand.SERVICE_ID;
    private String projectId;
    private String spaceId;
}
