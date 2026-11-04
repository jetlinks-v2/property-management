package org.jetlinks.pro.smartpark.repair.api;

/** 派单选择项不暴露人员角色、组织关系及其他共享资料。 */
public record RepairAssignee(String id, String displayName) {
}
