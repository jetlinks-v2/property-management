package org.jetlinks.pro.property.base.command;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.base.archive.PropertyHouseSummary;

import reactor.core.publisher.Mono;

import java.io.Serial;
import java.util.List;

/**
 * 以调用者组织及空间 read 权限汇总1到100个项目；任一项目非法或无权则整请求失败。 只读，不接受通用查询条件或权限覆盖参数；基础来源失败原样传播。
 *
 * @since 2.20.0
 * @see PropertyHouseSummary
 */
public class QueryPropertyHouseSummaryCommand
        extends AbstractCommand<Mono<PropertyHouseSummary>, QueryPropertyHouseSummaryCommand> {
    @Serial private static final long serialVersionUID = 1L;
    public static final String SERVICE_ID = QueryPropertyHousesCommand.SERVICE_ID;

    @SuppressWarnings("unchecked")
    public List<String> getProjectIds() {
        var value = getOrNull("projectIds", List.class);
        return value == null ? List.of() : value;
    }

    public void setProjectIds(List<String> value) {
        with("projectIds", value);
    }
}
