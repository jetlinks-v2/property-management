package org.jetlinks.pro.property.base.command;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.core.utils.SerializeUtils;
import org.jetlinks.pro.codec.Serializers;
import org.jetlinks.pro.property.base.dictionary.PropertyDictionaryOption;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;

/** 验证命令传输中的字典编码和文本，不能仅检查进程内 DTO。 */
class PropertyDictionaryCommandResponseTest {
    @ParameterizedTest
    @CsvSource({"level1,一般欠费", "1,常规收费项目"})
    void dictionaryCodeSurvivesPlatformRpcCodec(String value, String text) {
        var encoded = Serializers.serializeSafetyNetty(
                List.of(new PropertyDictionaryOption(value, text)));
        var decoded = Serializers.deserializeNetty(encoded);
        assertResponse(decoded, value, text);
    }

    @ParameterizedTest
    @CsvSource({"level1,一般欠费", "1,常规收费项目"})
    void dictionaryCodeSurvivesRpcSerialization(String value, String text) throws Exception {
        var option = new PropertyDictionaryOption(value, text);
        var payload = SerializeUtils.convertToSafelySerializable(List.of(option));
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            SerializeUtils.writeObject(payload, output);
        }
        Object restored;
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = SerializeUtils.readObject(input);
        }
        var rows = assertInstanceOf(List.class, restored);
        var row = assertInstanceOf(Map.class, rows.get(0));
        assertEquals(value, row.get("value"));
        assertEquals(text, row.get("text"));

        assertResponse(restored, value, text);
    }

    private void assertResponse(Object payload, String value, String text) {
        var options = assertInstanceOf(List.class,
                new QueryPropertyDictionaryOptionsCommand().createResponseData(payload));
        var converted = assertInstanceOf(PropertyDictionaryOption.class, options.get(0));
        assertEquals(value, converted.value());
        assertEquals(text, converted.text());
    }
}
