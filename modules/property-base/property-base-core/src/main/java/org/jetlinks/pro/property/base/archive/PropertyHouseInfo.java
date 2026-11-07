package org.jetlinks.pro.property.base.archive;

import java.math.BigDecimal;

/**
 * 房屋公开只读资料；可维护任意层级空间，消费模块保存自己的历史快照。
 *
 * @since 2.20.0
 */
public record PropertyHouseInfo(
        String spaceId,
        String projectId,
        String name,
        String fullName,
        String spaceTypeId,
        String spaceTypeName,
        String spaceLocation,
        BigDecimal billingArea,
        BigDecimal buildingArea,
        BigDecimal insideArea,
        Long deliveryDate,
        Long version) {}
