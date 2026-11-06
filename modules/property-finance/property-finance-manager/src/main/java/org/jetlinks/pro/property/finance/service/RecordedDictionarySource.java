package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.QueryPropertyDictionaryOptionsCommand;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/** 基础字典只经公开命令读取；来源故障不能降级为接受任意字典值。 */
@Component
public class RecordedDictionarySource {
    public Mono<Map<String, String>> options(String typeCode) {
        return Mono.defer(
                () -> {
                    var command = new QueryPropertyDictionaryOptionsCommand();
                    command.setTypeCode(typeCode);
                    return CommandSupportManagerProviders.getCommandSupport(
                                    QueryPropertyDictionaryOptionsCommand.SERVICE_ID)
                            .switchIfEmpty(Mono.error(error("dictionary_source_unavailable", 503)))
                            .flatMap(support -> support.execute(command))
                            .map(
                                    options -> {
                                        var values = new LinkedHashMap<String, String>();
                                        for (var option : options) {
                                            if (values.putIfAbsent(option.value(), option.text())
                                                    != null)
                                                throw error("dictionary_source_unavailable", 503);
                                        }
                                        return values;
                                    });
                });
    }
}
