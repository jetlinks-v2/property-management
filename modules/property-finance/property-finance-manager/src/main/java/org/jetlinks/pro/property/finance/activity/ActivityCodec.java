package org.jetlinks.pro.property.finance.activity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 活动配置含Java record，沿用HTTP的Jackson语义保存，避免旧Fastjson把record写为空对象。 */
public final class ActivityCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ActivityCodec() {}

    public static String encode(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize activity configuration", e);
        }
    }

    public static <T> T decode(String value, Class<T> type) {
        try {
            return MAPPER.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read activity configuration", e);
        }
    }
}
