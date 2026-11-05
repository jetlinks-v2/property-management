package org.jetlinks.pro.property.base.dictionary;

/**
 * 可新选的字典值；编码用于保存，文本用于展示，不把文本当作稳定身份。
 *
 * @since 2.20.0
 */
public record PropertyDictionaryOption(String value, String text) {}
