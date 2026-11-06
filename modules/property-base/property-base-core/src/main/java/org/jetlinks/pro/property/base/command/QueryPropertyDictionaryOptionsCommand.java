package org.jetlinks.pro.property.base.command;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.base.dictionary.PropertyDictionaryOption;

import reactor.core.publisher.Mono;

import java.io.Serial;
import java.util.List;

/**
 * 查询全局字典当前可新选值；分类不存在、已停用和来源失败必须显式返回错误。 不授予字典维护权限，不接受项目参数；消费方仍须校验自身业务动作和项目资产。
 *
 * @see PropertyDictionaryOption
 * @since 2.20.0
 */
public class QueryPropertyDictionaryOptionsCommand
        extends AbstractCommand<
                Mono<List<PropertyDictionaryOption>>, QueryPropertyDictionaryOptionsCommand> {
    @Serial private static final long serialVersionUID = 1L;
    public static final String SERVICE_ID = "property-dictionary-service";

    public String getTypeCode() {
        var value = getOrNull("typeCode", String.class);
        return value;
    }

    public void setTypeCode(String value) {
        with("typeCode", value);
    }
}
