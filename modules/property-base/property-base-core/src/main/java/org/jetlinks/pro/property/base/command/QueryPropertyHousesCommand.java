package org.jetlinks.pro.property.base.command;

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
public class QueryPropertyHousesCommand
        extends AbstractCommand<Mono<PropertyHousePage>, QueryPropertyHousesCommand> {
    @Serial private static final long serialVersionUID = 1L;
    public static final String SERVICE_ID = "propertyService:house";

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

    public String getKeyword() {
        var value = getOrNull("keyword", String.class);
        return value;
    }

    public void setKeyword(String value) {
        with("keyword", value);
    }

    public int getPageIndex() {
        var value = getOrNull("pageIndex", Integer.class);
        return value == null ? 0 : value;
    }

    public void setPageIndex(int value) {
        with("pageIndex", value);
    }

    public int getPageSize() {
        var value = getOrNull("pageSize", Integer.class);
        return value == null ? 12 : value;
    }

    public void setPageSize(int value) {
        with("pageSize", value);
    }
}
