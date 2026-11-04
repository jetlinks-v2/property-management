package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.archive.PropertySpaceInfo;
import org.jetlinks.pro.property.base.command.QueryPropertySpacesCommand;
import org.jetlinks.pro.property.base.dictionary.PropertyDictionaryOption;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.List;

/** 活动只依赖基础core公开命令，空间深度和来源类型不在财务模块重建。 */
@Component
@RequiredArgsConstructor
public class ActivitySource {
    private final SubjectDictionaryService dictionaries;

    public Mono<List<PropertySpaceInfo>> spaces(String project) {
        return Mono.defer(
                () -> {
                    var command = new QueryPropertySpacesCommand();
                    command.setProjectId(project);
                    return CommandSupportManagerProviders.getCommandSupport(
                                    QueryPropertySpacesCommand.SERVICE_ID)
                            .switchIfEmpty(Mono.error(error("house_source_unavailable", 503)))
                            .flatMap(support -> support.execute(command))
                            .switchIfEmpty(Mono.error(error("house_source_unavailable", 503)));
                });
    }

    public Mono<List<PropertyDictionaryOption>> types() {
        return dictionaries.options("promotion-type");
    }
}
