package org.jetlinks.pro.property.finance.service;

import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.archive.PropertySpaceInfo;
import org.jetlinks.pro.property.base.command.QueryPropertySpacesCommand;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.List;

/** 合同只校验来源真实空间，不要求提前补齐房屋计费面积；始终通过基础core命令。 */
@Component
public class ContractSpaceSource {
    public Mono<List<PropertySpaceInfo>> spaces(String project) {
        var command = new QueryPropertySpacesCommand();
        command.setProjectId(project);
        return CommandSupportManagerProviders.getCommandSupport(
                        QueryPropertySpacesCommand.SERVICE_ID)
                .switchIfEmpty(Mono.error(FinanceScope.error("house_source_unavailable", 503)))
                .flatMap(support -> support.execute(command))
                .switchIfEmpty(Mono.error(FinanceScope.error("house_source_unavailable", 503)));
    }
}
