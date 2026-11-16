package org.jetlinks.pro.property.base.space;

/** 授权空间的组织归属及原始权限位；不包含房屋资料或财务信息。
 * @since 2.20.0
 */
public record PropertySpaceOrganizationBinding(
        String organizationId, String spaceId, long permission, String relation) {}
