package org.jetlinks.pro.patrol.integration;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.GetCurrentPropertyEmployeeCommand;
import org.jetlinks.pro.property.base.command.QueryPropertyEmployeePageCommand;
import org.jetlinks.pro.property.base.command.QueryPropertyOrganizationTreeCommand;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 物业主数据只读适配器；保留调用者认证，故障直接传播，不复制主数据或回退到未过滤目录。 */
@Component
public class PropertyDirectory {
    protected Mono<CommandSupport> support(String service) {
        return CommandSupportManagerProviders.getCommandSupport(service)
                .switchIfEmpty(Mono.error(new BusinessException("error.patrol.base_unavailable")));
    }

    public Flux<PropertyOrganizationInfo> organizations() {
        return support(QueryPropertyOrganizationTreeCommand.SERVICE_ID)
                .flatMapMany(support -> support.execute(new QueryPropertyOrganizationTreeCommand()));
    }

    public Mono<PagerResult<PropertyEmployeeInfo>> employees(QueryParamEntity query) {
        var command = new QueryPropertyEmployeePageCommand();
        command.setQuery(query);
        return support(QueryPropertyEmployeePageCommand.SERVICE_ID).flatMap(support -> support.execute(command));
    }

    public Mono<PropertyEmployeeInfo> currentEmployee() {
        return support(GetCurrentPropertyEmployeeCommand.SERVICE_ID)
                .flatMap(support -> support.execute(new GetCurrentPropertyEmployeeCommand()));
    }
}
