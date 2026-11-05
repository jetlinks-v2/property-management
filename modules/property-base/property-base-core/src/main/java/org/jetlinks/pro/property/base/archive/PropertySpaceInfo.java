package org.jetlinks.pro.property.base.archive;

/**
 * 授权空间的稳定身份和来源位置，不预设组团、楼栋或房间层级。
 *
 * @since 2.20.0
 * @see org.jetlinks.pro.property.base.command.QueryPropertySpacesCommand
 */
public record PropertySpaceInfo(
        String id,
        String parentId,
        String rootId,
        String name,
        String fullName,
        String spaceTypeId,
        String spaceTypeName,
        Boolean leaf) {}
