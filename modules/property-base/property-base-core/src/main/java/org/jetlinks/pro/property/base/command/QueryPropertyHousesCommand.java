package org.jetlinks.pro.property.base.command;

import lombok.Getter;
import lombok.Setter;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.base.archive.PropertyHousePage;

import reactor.core.publisher.Mono;

import java.io.Serial;

/**
 * 查询授权项目内已完善资料的末端房屋；空间范围可选，页大小1到200。
 *
 * @see PropertyHousePage
 * @since 2.20.0
 */
@Getter
@Setter
public class QueryPropertyHousesCommand
        extends AbstractCommand<Mono<PropertyHousePage>, QueryPropertyHousesCommand> {
    @Serial private static final long serialVersionUID = 1L;
    public static final String SERVICE_ID = "propertyService:house";
    private String projectId;
    private String spaceId;
    private String keyword;
    private int pageIndex;
    private int pageSize = 12;
}
