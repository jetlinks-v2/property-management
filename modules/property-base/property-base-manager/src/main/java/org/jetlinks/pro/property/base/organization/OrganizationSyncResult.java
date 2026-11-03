package org.jetlinks.pro.property.base.organization;

/** 一次已提交的全量同步结果，计数不包含未变化的组织。 */
public record OrganizationSyncResult(int added, int updated, int deleted, long completedTime) {}
