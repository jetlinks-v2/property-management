package org.jetlinks.pro.property.base.command;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.property.base.dictionary.PropertyDictionaryOption;
import org.jetlinks.pro.property.base.service.PropertyDictionaryService;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 业务模块通过公开 core 命令读取可选值，不依赖基础 manager 或直接查询字典表。
 *
 * @see QueryPropertyDictionaryOptionsCommand
 * @since 2.20.0
 */
@Component
@Authorize
@RequiredArgsConstructor
@CommandService(id = QueryPropertyDictionaryOptionsCommand.SERVICE_ID, name = "物业字典服务")
public class PropertyDictionaryCommandSupport {
    private final PropertyDictionaryService dictionaries;

    @CommandHandler(name = "查询可选字典项", description = "只返回启用分类中当前可新选的启用项")
    public Mono<List<PropertyDictionaryOption>> queryOptions(
            QueryPropertyDictionaryOptionsCommand command) {
        return dictionaries.availableOptions(command.getTypeCode());
    }
}
