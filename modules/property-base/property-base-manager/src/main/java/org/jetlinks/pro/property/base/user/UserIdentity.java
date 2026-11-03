package org.jetlinks.pro.property.base.user;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.hswebframework.web.dict.I18nEnumDict;

/** 物业业务身份，与 SaaS 角色、账号类型和房屋关系相互独立。 */
@Getter
@AllArgsConstructor
public enum UserIdentity implements I18nEnumDict<String> {
    owner("客户"),
    employee("员工");

    private final String text;

    @Override
    public String getValue() {
        return name();
    }

    /** 请求只接收稳定编码，避免 EnumDict 的对象序列化格式影响普通枚举反序列化。 */
    @com.fasterxml.jackson.annotation.JsonCreator(
            mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING)
    public static UserIdentity fromValue(String value) {
        return valueOf(value);
    }
}
