package org.jetlinks.pro.smartpark.repair.service;

/** 已通过租户、应用归属和登录身份验证的报修操作上下文。 */
public record RepairContext(String tenantId, String applicationId, String userId, String userName) {}
