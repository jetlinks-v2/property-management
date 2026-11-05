package org.jetlinks.pro.property.base.command;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.property.base.dictionary.PropertyDictionaryItemInfo;
import org.jetlinks.pro.property.base.service.PropertyDictionaryService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** 公共字典拥有者提供启用项；复用字典服务的认证与删除过滤，权限与HTTP读取相同。 */
@Component @Authorize @RequiredArgsConstructor
@CommandService(id = QueryPropertyDictionaryItemsCommand.SERVICE_ID, name = "物业公共字典目录")
public class PropertyDictionaryCommandSupport {
    private final PropertyDictionaryService dictionaries;

    @CommandHandler(name = "查询启用字典项")
    public Flux<PropertyDictionaryItemInfo> query(QueryPropertyDictionaryItemsCommand command) {
        return dictionaries.types().flatMapMany(types -> types.stream().anyMatch(type ->
                type.getCode().equals(command.getTypeCode()) && Boolean.TRUE.equals(type.getEnabled()))
            ? dictionaries.items(command.getTypeCode()).flatMapMany(Flux::fromIterable)
                .filter(item -> Boolean.TRUE.equals(item.getEnabled()))
                .map(item -> new PropertyDictionaryItemInfo(item.getValue(), item.getText(), item.getSortIndex()))
            : Flux.empty());
    }
}
