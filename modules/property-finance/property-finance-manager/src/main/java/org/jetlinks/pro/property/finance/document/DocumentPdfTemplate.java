package org.jetlinks.pro.property.finance.document;

import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/** 票面只加载内置模板与字体；一次替换已转义字段，禁止访问外部资源。 */
final class DocumentPdfTemplate {
    private static final String ROOT = "/receipt-templates/";
    private static final Pattern TOKEN = Pattern.compile("\\{\\{([A-Za-z]+)}}");

    private DocumentPdfTemplate() {}

    static byte[] render(String name, Map<String, String> values) throws Exception {
        try (var input = DocumentPdfTemplate.class.getResourceAsStream(ROOT + name);
                var output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("Missing document template");
            var template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var html =
                    TOKEN.matcher(template)
                            .replaceAll(
                                    match ->
                                            java.util.regex.Matcher.quoteReplacement(
                                                    values.getOrDefault(match.group(1), "")));
            var builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useFont(
                    () ->
                            DocumentPdfTemplate.class.getResourceAsStream(
                                    ROOT + "fonts/PropertyReceiptSC-Regular.ttf"),
                    "Noto Sans SC");
            builder.useExternalResourceAccessControl(
                    (uri, type) -> false, ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI);
            builder.withHtmlContent(html, null);
            builder.toStream(output);
            builder.run();
            return output.toByteArray();
        }
    }
}
