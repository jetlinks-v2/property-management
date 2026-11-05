package org.jetlinks.pro.property.base.command;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.base.dictionary.PropertyDictionaryItemInfo;
import reactor.core.publisher.Flux;

/** 以当前认证读取全局公共字典的启用选项；不提供写入或业务资料。
 * 参数随命令负载传输；缺分类/停用分类返回空目录，调用故障保持失败。
 */
public class QueryPropertyDictionaryItemsCommand extends AbstractCommand<Flux<PropertyDictionaryItemInfo>, QueryPropertyDictionaryItemsCommand> {
    public static final String SERVICE_ID = "propertyService:dictionary";
    public String getTypeCode() { return getOrNull("typeCode", String.class); }
    public void setTypeCode(String typeCode) { with("typeCode", typeCode); }
}
