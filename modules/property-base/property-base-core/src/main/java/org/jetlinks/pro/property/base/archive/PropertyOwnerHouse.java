package org.jetlinks.pro.property.base.archive;

import java.util.List;

/**
 * 本人房屋最小快照：客户仅含本人，祖先仅用于活动范围匹配，不包含其他住户资料。
 *
 * @since 2.20.0
 */
public record PropertyOwnerHouse(
        String projectName, PropertyHouseContext context, List<PropertySpaceInfo> ancestors) {}
