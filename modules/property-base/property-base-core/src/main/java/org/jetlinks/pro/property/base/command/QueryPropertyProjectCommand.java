package org.jetlinks.pro.property.base.command;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.base.organization.PropertyProjectInfo;

import reactor.core.publisher.Mono;

import java.io.Serial;

/**
 * 按稳定 ID 校验并读取物业项目，不接受名称匹配，不提升调用者的资产权限。 失效、未标注项目、无权限和来源不可用都直接失败，消费模块不得把失败转成可写的本地项目。
 *
 * @see PropertyProjectInfo
 * @since 2.20.0
 */
public class QueryPropertyProjectCommand
        extends AbstractCommand<Mono<PropertyProjectInfo>, QueryPropertyProjectCommand> {
    @Serial private static final long serialVersionUID = 1L;

    public static final String SERVICE_ID = "propertyService:project";

    public String getProjectId() {
        var value = getOrNull("projectId", String.class);
        return value;
    }

    public void setProjectId(String value) {
        with("projectId", value);
    }
}
