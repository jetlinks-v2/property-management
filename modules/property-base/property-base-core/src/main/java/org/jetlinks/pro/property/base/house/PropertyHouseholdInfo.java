package org.jetlinks.pro.property.base.house;

import java.io.Serializable;

/** 有效客户房屋关系只读快照；ID 与显示名称分离，不包含证件等无关资料。 */
public record PropertyHouseholdInfo(String projectId, String spaceId, String spaceName,
        String houseType, String customerId, String userId, String customerName,
        String telephone, String relationshipId, String nature) implements Serializable {}
