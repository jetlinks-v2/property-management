package org.jetlinks.pro.property.base.command;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.base.archive.PropertySpaceInfo;

import reactor.core.publisher.Mono;

import java.io.Serial;
import java.util.List;

/**
 * 读取项目内有权访问的全部层级空间；不替换既有directory空间命令；来源叶子标志不按裁剪结果重新推断。
 *
 * @since 2.20.0
 * @see PropertySpaceInfo
 */
public class QueryPropertySpaceNodesCommand
        extends AbstractCommand<Mono<List<PropertySpaceInfo>>, QueryPropertySpaceNodesCommand> {
    @Serial private static final long serialVersionUID = 1L;
    public static final String SERVICE_ID = "propertyService:space";

    public String getProjectId() {
        var value = getOrNull("projectId", String.class);
        return value;
    }

    public void setProjectId(String value) {
        with("projectId", value);
    }
}
