package org.jetlinks.pro.property.base.archive;

/**
 * 当前房屋关系的最小公开客户资料；不暴露证件、信用代码或其他房屋关系。
 *
 * @since 2.20.0
 */
public record PropertyHouseCustomer(
        String relationshipId,
        String customerId,
        String userId,
        String customerType,
        String name,
        String telephone,
        String nature,
        String familyId,
        String familyName,
        Long startTime,
        Long relationshipVersion,
        Long customerVersion) {}
