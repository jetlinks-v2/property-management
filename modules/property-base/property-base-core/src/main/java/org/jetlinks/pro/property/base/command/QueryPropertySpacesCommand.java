package org.jetlinks.pro.property.base.command;

import lombok.Getter;
import lombok.Setter;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.base.archive.PropertySpaceInfo;

import reactor.core.publisher.Mono;

import java.io.Serial;
import java.util.List;

/**
 * 读取项目内有权访问的全部层级空间；来源叶子标志不按裁剪结果重新推断。
 *
 * @since 2.20.0
 * @see PropertySpaceInfo
 */
@Getter
@Setter
public class QueryPropertySpacesCommand
        extends AbstractCommand<Mono<List<PropertySpaceInfo>>, QueryPropertySpacesCommand> {
    @Serial private static final long serialVersionUID = 1L;
    public static final String SERVICE_ID = "propertyService:space";
    private String projectId;
}
