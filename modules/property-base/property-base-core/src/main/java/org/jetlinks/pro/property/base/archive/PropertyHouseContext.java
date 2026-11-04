package org.jetlinks.pro.property.base.archive;

import java.util.List;

/**
 * 跨模块校验快照，只包含当前有效关系；关系结束后旧业务使用自己的历史快照。
 *
 * @see PropertyHouseInfo
 * @see PropertyHouseCustomer
 * @since 2.20.0
 */
public record PropertyHouseContext(
        PropertyHouseInfo house, List<PropertyHouseCustomer> customers) {}
