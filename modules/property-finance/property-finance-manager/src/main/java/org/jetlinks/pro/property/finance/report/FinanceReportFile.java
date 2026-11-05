package org.jetlinks.pro.property.finance.report;

import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.util.HtmlUtils;

import java.io.*;
import java.util.*;

/** 财务报表共用文件排版；仅接受已授权快照，禁止Excel公式与PDF外部资源。 */
public final class FinanceReportFile {
    private FinanceReportFile() {}

    public record Data(
            String title,
            String scope,
            String summaryLabel,
            List<String> columns,
            Map<String, String> labels,
            Set<String> money,
            List<Map<String, String>> rows,
            Map<String, String> summary) {}

    public static byte[] write(Data data, String format) {
        try {
            return "excel".equals(format) ? excel(data) : pdf(data);
        } catch (Exception failure) {
            throw new org.hswebframework.web.exception.BusinessException(
                    "error.property_finance_report_export_failed", 500, failure);
        }
    }

    private static byte[] excel(Data data) throws IOException {
        var columns = data.columns();
        try (var book = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet(data.title());
            var header = sheet.createRow(0);
            var style = book.createCellStyle();
            var font = book.createFont();
            font.setBold(true);
            style.setFont(font);
            for (int n = 0; n < columns.size(); n++) {
                var cell = header.createCell(n);
                cell.setCellValue(data.labels().get(columns.get(n)));
                cell.setCellStyle(style);
                sheet.setColumnWidth(n, ("fullName".equals(columns.get(n)) ? 48 : 24) * 256);
            }
            int index = 1;
            for (var row : data.rows()) {
                var target = sheet.createRow(index++);
                var values = row;
                for (int n = 0; n < columns.size(); n++)
                    target.createCell(n).setCellValue(values.getOrDefault(columns.get(n), ""));
            }
            var footer = sheet.createRow(index++);
            footer.createCell(0).setCellValue(data.summaryLabel());
            var amounts = data.summary();
            for (int n = 0; n < columns.size(); n++)
                if (amounts.containsKey(columns.get(n)))
                    footer.createCell(n).setCellValue(amounts.get(columns.get(n)));
            sheet.createRow(index).createCell(0).setCellValue(data.scope());
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(
                    new org.apache.poi.ss.util.CellRangeAddress(
                            0, data.rows().size(), 0, columns.size() - 1));
            book.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] pdf(Data data) throws IOException {
        var columns = data.columns();
        StringBuilder html =
                new StringBuilder(
                        "<html><head><meta charset='UTF-8'/><style>@page{size:A3"
                            + " landscape;margin:12mm}body{font-family:'Noto Sans"
                            + " SC';font-size:9px;color:#262626}h1{font-size:18px}table{width:100%;border-collapse:collapse;table-layout:fixed;-fs-table-paginate:paginate}th,td{border:1px"
                            + " solid"
                            + " #d9d9d9;padding:5px;word-wrap:break-word}th{background:#f5f5f5}thead{display:table-header-group}tr{page-break-inside:avoid}.money{text-align:right;white-space:nowrap;word-wrap:normal}.summary{font-weight:bold}</style></head><body><h1>");
        html.append(escape(data.title()))
                .append("</h1><p>")
                .append(escape(data.scope()))
                .append("</p><table>");
        appendColumns(html, data);
        html.append("<thead><tr>");
        for (String column : columns)
            html.append("<th>").append(escape(data.labels().get(column))).append("</th>");
        html.append("</tr></thead><tbody>");
        for (var row : data.rows()) appendRow(html, columns, data.money(), row, false);
        var totals = new HashMap<>(data.summary());
        totals.put(columns.get(0), data.summaryLabel());
        appendRow(html, columns, data.money(), totals, true);
        html.append("</tbody></table></body></html>");
        try (var output = new ByteArrayOutputStream()) {
            var renderer = new PdfRendererBuilder();
            renderer.useFastMode();
            renderer.useFont(
                    () ->
                            FinanceReportFile.class.getResourceAsStream(
                                    "/receipt-templates/fonts/PropertyReceiptSC-Regular.ttf"),
                    "Noto Sans SC");
            renderer.useExternalResourceAccessControl(
                    (uri, type) -> false, ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI);
            renderer.withHtmlContent(html.toString(), null);
            renderer.toStream(output);
            renderer.run();
            return output.toByteArray();
        }
    }

    /** 金额列按实际十进制位数预留宽度，避免超长金额断行；其余文本仍允许折行。 */
    private static void appendColumns(StringBuilder html, Data data) {
        var weights = new LinkedHashMap<String, Integer>();
        for (var column : data.columns()) {
            int weight = "fullName".equals(column) ? 24 : 14;
            if (data.money().contains(column)) {
                int digits = data.summary().getOrDefault(column, "").length();
                for (var row : data.rows())
                    digits = Math.max(digits, row.getOrDefault(column, "").length());
                weight = Math.max(20, digits + 4);
            }
            weights.put(column, weight);
        }
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        html.append("<colgroup>");
        weights.values()
                .forEach(
                        weight ->
                                html.append("<col style='width:")
                                        .append(
                                                String.format(
                                                        Locale.ROOT,
                                                        "%.4f",
                                                        100.0 * weight / total))
                                        .append("%'/>"));
        html.append("</colgroup>");
    }

    private static void appendRow(
            StringBuilder html,
            List<String> columns,
            Set<String> money,
            Map<String, String> values,
            boolean summary) {
        html.append(summary ? "<tr class='summary'>" : "<tr>");
        for (String c : columns)
            html.append(money.contains(c) ? "<td class='money'>" : "<td>")
                    .append(escape(values.getOrDefault(c, "")))
                    .append("</td>");
        html.append("</tr>");
    }

    private static String escape(String text) {
        return HtmlUtils.htmlEscape(text);
    }
}
