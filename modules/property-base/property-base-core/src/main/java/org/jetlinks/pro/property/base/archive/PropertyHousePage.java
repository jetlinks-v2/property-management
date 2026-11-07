package org.jetlinks.pro.property.base.archive;

import java.util.List;

/**
 * 只含已完善资料且调用者可见的空间房屋资料；总数与分页始终属于相同过滤范围。
 *
 * @since 2.20.0
 */
public record PropertyHousePage(
        long total, int pageIndex, int pageSize, List<PropertyHouseInfo> data) {}
