package org.jetlinks.pro.property.decoration.service;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import static org.jetlinks.pro.property.decoration.service.DecorationRules.error;

/** 旧版二进制Word仅输出纯文本，由前端按文本节点呈现，禁止执行文档内HTML或脚本。 */
final class DecorationWordPreview {
    private DecorationWordPreview() {}
    static String text(byte[] bytes) {
        try (var document = new HWPFDocument(new ByteArrayInputStream(bytes)); var extractor = new WordExtractor(document)) {
            return extractor.getText();
        } catch (IOException | RuntimeException failure) {
            throw error("invalid_attachment", 400);
        }
    }
}
