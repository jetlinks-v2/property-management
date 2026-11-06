package org.jetlinks.pro.property.base.dictionary;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 可新选的字典值；编码用于保存，文本用于展示，不把文本当作稳定身份。
 *
 * @since 2.20.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PropertyDictionaryOption {
    // 命令响应通过 Bean 转换重建，使用标准读写属性，避免 record 编码字段丢失。
    private String value;

    private String text;

    /** 保留既有消费方的编码访问契约。 */
    public String value() {
        return value;
    }

    /** 保留既有消费方的文本访问契约。 */
    public String text() {
        return text;
    }
}
