package org.jetlinks.pro.property.base.space;

/** 数据库租约及最近同步结果；不暴露来源异常详情。 */
public record SpaceSyncStatus(
        boolean running, Long lastSuccessTime, Long lastFailureTime, String lastError) {}
