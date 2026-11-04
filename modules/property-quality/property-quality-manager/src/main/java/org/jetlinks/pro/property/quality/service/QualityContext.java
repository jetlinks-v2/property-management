package org.jetlinks.pro.property.quality.service;
/** 当前已认证租户、应用及真实操作人；不接受请求体传入身份。 */
public record QualityContext(String tenantId,String applicationId,String userId,String userName) {}
