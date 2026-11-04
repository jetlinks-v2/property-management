package org.jetlinks.pro.property.base.organization;

/**
 * 经基础模块确认仍有效、标注为项目且调用者有权读取的项目标识。
 * 仅用于业务引用校验，不替代消费模块的动作权限或写入资产权限。
 *
 * @since 2.20.0
 */
public record PropertyProjectInfo(String id, String name) {}
