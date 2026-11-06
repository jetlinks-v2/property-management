package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.QueryPropertyDictionaryOptionsCommand;
import org.jetlinks.pro.property.base.dictionary.PropertyDictionaryOption;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import java.util.List;

/** 保留当前认证与命令上下文。来源失败必须原样失败，禁止把它当作没有启用选项。 */
@Service
public class SubjectDictionaryService {
    public Mono<List<PropertyDictionaryOption>> options(String typeCode) {
        return Mono.defer(
                () -> {
                    var command = new QueryPropertyDictionaryOptionsCommand();
                    command.setTypeCode(typeCode);
                    return CommandSupportManagerProviders.getCommandSupport(
                                    QueryPropertyDictionaryOptionsCommand.SERVICE_ID)
                            .switchIfEmpty(Mono.error(error("subject_dictionary_unavailable", 503)))
                            .flatMap(support -> support.execute(command));
                });
    }

    public Mono<Void> assertValue(String typeCode, String value) {
        return options(typeCode)
                .flatMap(
                        options ->
                                options.stream().anyMatch(option -> option.value().equals(value))
                                        ? Mono.empty()
                                        : Mono.error(error("subject_dictionary_invalid", 400)));
    }
}
