package org.jetlinks.pro.property.base.organization;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.hswebframework.web.dict.I18nEnumDict;

/**
 * 物业组织的公共业务分类，不写回 SaaS 自身的组织 type 字段。
 *
 * @see PropertyOrganizationInfo
 * @since 2.20.0
 */
@Getter
@AllArgsConstructor
public enum OrganizationType implements I18nEnumDict<String> {
    project("项目"),
    external("外协单位");

    private final String text;

    @Override
    public String getValue() {
        return name();
    }
}
