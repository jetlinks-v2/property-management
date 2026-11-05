package org.jetlinks.pro.property.base.organization;

/** 已授权项目的私有章图片；仅供业务证件生成，不包含公共URL或文件存储凭据。 */
public record PropertyProjectSeal(String projectId, String mediaType, byte[] content) {}
