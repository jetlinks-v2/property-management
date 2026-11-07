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

    public static byte[] write(ReportWorkbook workbook, String format) {
        try {
            if ("excel".equals(format)) return excel(workbook);
            return pdf(workbook);
        } catch (Exception failure) {
            throw new org.hswebframework.web.exception.BusinessException(
                    "error.property_finance_report_export_failed", 500, failure);
        }
    }

    private static byte[] excel(Data data) throws IOException {
        return excel(new ReportWorkbook(List.of(data), Map.of()));
    }

    private static byte[] excel(ReportWorkbook workbook) throws IOException {
        try (var book = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var headerStyle = book.createCellStyle();
            var font = book.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            var numericStyle = book.createCellStyle();
            numericStyle.setDataFormat(
                    book.createDataFormat().getFormat("#,##0.00;[Red]-#,##0.00"));
            var preciseStyle = book.createCellStyle();
            preciseStyle.setDataFormat(
                    book.createDataFormat().getFormat("#,##0.000000;[Red]-#,##0.000000"));
            int number = 0;
            for (var data : workbook.sheets()) {
                var sheet =
                        book.createSheet(
                                org.apache.poi.ss.util.WorkbookUtil.createSafeSheetName(
                                        data.title() + (number++ == 0 ? "" : "-明细")));
                var columns = data.columns();
                var header = sheet.createRow(0);
                for (int n = 0; n < columns.size(); n++) {
                    var cell = header.createCell(n);
                    cell.setCellValue(data.labels().get(columns.get(n)));
                    cell.setCellStyle(headerStyle);
                    sheet.setColumnWidth(n, ("fullName".equals(columns.get(n)) ? 48 : 24) * 256);
                }
                int index = 1;
                for (var values : data.rows()) {
                    var row = sheet.createRow(index++);
                    for (int n = 0; n < columns.size(); n++)
                        writeCell(
                                row.createCell(n),
                                columns.get(n),
                                values.get(columns.get(n)),
                                data.money(),
                                "outputTax".equals(columns.get(n)) ? preciseStyle : numericStyle);
                }
                var footer = sheet.createRow(index++);
                footer.createCell(0).setCellValue(data.summaryLabel());
                for (int n = 1; n < columns.size(); n++)
                    if (data.summary().containsKey(columns.get(n)))
                        writeCell(
                                footer.createCell(n),
                                columns.get(n),
                                data.summary().get(columns.get(n)),
                                data.money(),
                                "outputTax".equals(columns.get(n)) ? preciseStyle : numericStyle);
                sheet.createRow(index).createCell(0).setCellValue(data.scope());
                sheet.createFreezePane(0, 1);
                sheet.setAutoFilter(
                        new org.apache.poi.ss.util.CellRangeAddress(
                                0, data.rows().size(), 0, columns.size() - 1));
            }
            if (!workbook.conditions().isEmpty()) {
                var sheet = book.createSheet("查询条件");
                int index = 0;
                for (var item : workbook.conditions().entrySet()) {
                    var row = sheet.createRow(index++);
                    row.createCell(0).setCellValue(item.getKey());
                    row.createCell(1).setCellValue(item.getValue());
                }
                sheet.setColumnWidth(0, 24 * 256);
                sheet.setColumnWidth(1, 90 * 256);
            }
            book.write(output);
            return output.toByteArray();
        }
    }

    /** Excel普通金额写数值供求和；超过15位有效数字保留文本，避免Excel破坏财务精度。 */
    private static void writeCell(
            org.apache.poi.ss.usermodel.Cell cell,
            String key,
            String value,
            Set<String> money,
            org.apache.poi.ss.usermodel.CellStyle style) {
        String text = Objects.toString(value, "");
        if (money.contains(key) && !text.isBlank()) {
            var amount = new java.math.BigDecimal(text);
            if (amount.precision() <= 15) {
                cell.setCellValue(amount.doubleValue());
                cell.setCellStyle(style);
                return;
            }
        }
        cell.setCellValue(text);
    }

    private static byte[] pdf(Data data) throws IOException {
        return pdf(new ReportWorkbook(List.of(data), Map.of()));
    }

    private static byte[] pdf(ReportWorkbook workbook) throws IOException {
        Data data = workbook.sheets().get(0);
        var columns = data.columns();
        StringBuilder html =
                new StringBuilder(
                        "<html><head><meta charset='UTF-8'/><style>@page{size:A3"
                            + " landscape;margin:12mm}body{font-family:'Noto Sans"
                            + " SC';font-size:9px;color:#262626}h1{font-size:18px}table{width:100%;border-collapse:collapse;table-layout:fixed;-fs-table-paginate:paginate}th,td{border:1px"
                            + " solid"
                            + " #d9d9d9;padding:5px;word-wrap:break-word}th{background:#f5f5f5}thead{display:table-header-group}tr{page-break-inside:avoid}.money{text-align:right;white-space:nowrap;word-wrap:normal}.summary{font-weight:bold}</style></head><body><h1>");
        html.append(escape(data.title())).append("</h1>");
        for (var condition : workbook.conditions().entrySet())
            html.append("<span>")
                    .append(escape(condition.getKey()))
                    .append(": ")
                    .append(escape(condition.getValue()))
                    .append("　</span>");
        for (var section : workbook.sheets()) {
            data = section;
            columns = data.columns();
            html.append("<h2>")
                    .append(escape(data.title()))
                    .append("</h2><p>")
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
            html.append("</tbody></table>");
        }
        html.append("</body></html>");
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
        // XHTML解析器不声明HTML命名实体，数字实体同时保留文本转义和Unicode兼容。
        return HtmlUtils.htmlEscapeDecimal(text);
    }
}
