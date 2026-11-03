package org.jetlinks.pro.property.base.space;

/** 已原子提交的一轮空间同步统计。 */
public record SpaceSyncResult(int added, int updated, int deleted, long completedAt) {}
