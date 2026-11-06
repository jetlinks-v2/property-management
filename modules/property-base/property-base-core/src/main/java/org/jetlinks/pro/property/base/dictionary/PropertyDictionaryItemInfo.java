package org.jetlinks.pro.property.base.dictionary;

/** 已启用公共字典项的只读投影；value为持久化编码，text仅用于展示和导入名称匹配。 */
public record PropertyDictionaryItemInfo(String value, String text, Long sortIndex) {}
