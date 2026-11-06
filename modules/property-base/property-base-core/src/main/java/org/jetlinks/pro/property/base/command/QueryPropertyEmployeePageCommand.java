package org.jetlinks.pro.property.base.command;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.core.command.CommandUtils;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.hswebframework.web.bean.FastBeanCopier;
import org.springframework.core.ResolvableType;
import reactor.core.publisher.Mono;

/**
 * 按当前 user 资产权限查询有效员工，先过滤再分页；不要求基础用户维护菜单。
 * 调用方负责其业务查询动作和应用成员约束，不得通过此命令新增或修改员工。
 */
public class QueryPropertyEmployeePageCommand extends AbstractCommand<
        Mono<PagerResult<PropertyEmployeeInfo>>, QueryPropertyEmployeePageCommand> {
    public static final String SERVICE_ID = "propertyService:employee";
    /** 参数必须进入 AbstractCommand 属性，保证远程序列化不会丢失筛选与分页。 */
    public void setQuery(QueryParamEntity query) { with("query", query); }
    public QueryParamEntity getQuery() {
        QueryParamEntity query = getOrNull("query", QueryParamEntity.class);
        return query == null ? new QueryParamEntity() : query;
    }

    /** RSocket may decode the page itself while leaving its generic rows as maps. */
    @Override
    public PagerResult<PropertyEmployeeInfo> createResponseData(Object data) {
        PagerResult<?> raw = data instanceof PagerResult<?> page
                ? page : FastBeanCopier.copy(data, new PagerResult<>());
        var rows = raw.getData().stream()
                .map(row -> (PropertyEmployeeInfo) CommandUtils.convertData(
                        ResolvableType.forClass(PropertyEmployeeInfo.class), row))
                .toList();
        var result = new PagerResult<PropertyEmployeeInfo>(raw.getTotal(), rows);
        result.setPageIndex(raw.getPageIndex());
        result.setPageSize(raw.getPageSize());
        return result;
    }
}
