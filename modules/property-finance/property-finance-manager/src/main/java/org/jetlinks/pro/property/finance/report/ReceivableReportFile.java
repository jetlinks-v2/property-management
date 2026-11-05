package org.jetlinks.pro.property.finance.report;


import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hswebframework.web.i18n.LocaleUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.io.*;
import java.util.*;

/** 只渲染已经授权的报表快照；Excel文本防公式执行，PDF不允许读取外部资源。 */
@Component
public class ReceivableReportFile {
    private static final List<String> MONEY =
            List.of(
                    "receivableAmount",
                    "remissionAmount",
                    "netAmount",
                    "receivedAmount",
                    "arrearsAmount",
                    "collectionRate");
    private static final Map<String, String> ZH =
            Map.ofEntries(
                    Map.entry("title", "应收统计"),
                    Map.entry("scope", "仅统计已审核、未删除且未拆分的账单；金额以已保存的计算截至日为准。"),
                    Map.entry("projectName", "项目"),
                    Map.entry("subjectName", "收费科目"),
                    Map.entry("fullName", "房屋"),
                    Map.entry("customerName", "收费客户"),
                    Map.entry("id", "账单编号"),
                    Map.entry("calcStartDate", "计费开始"),
                    Map.entry("calcEndDate", "计费结束"),
                    Map.entry("dueDate", "到期日"),
                    Map.entry("calculatedAsOfDate", "计算截至日"),
                    Map.entry("billCount", "账单数"),
                    Map.entry("receivableAmount", "减免前应收（元）"),
                    Map.entry("remissionAmount", "减免（元）"),
                    Map.entry("netAmount", "净应收（元）"),
                    Map.entry("receivedAmount", "已收（元）"),
                    Map.entry("arrearsAmount", "未收（元）"),
                    Map.entry("collectionRate", "收缴率（%）"),
                    Map.entry("summary", "全筛选范围合计"));
    private static final Map<String, String> EN =
            Map.ofEntries(
                    Map.entry("title", "Receivable report"),
                    Map.entry(
                            "scope",
                            "Approved, active, unsplit bills only. Amounts use the saved"
                                + " calculation date."),
                    Map.entry("projectName", "Project"),
                    Map.entry("subjectName", "Charge subject"),
                    Map.entry("fullName", "Space"),
                    Map.entry("customerName", "Customer"),
                    Map.entry("id", "Bill ID"),
                    Map.entry("calcStartDate", "Charge start"),
                    Map.entry("calcEndDate", "Charge end"),
                    Map.entry("dueDate", "Due date"),
                    Map.entry("calculatedAsOfDate", "Calculated as of"),
                    Map.entry("billCount", "Bills"),
                    Map.entry("receivableAmount", "Gross due (CNY)"),
                    Map.entry("remissionAmount", "Reduction (CNY)"),
                    Map.entry("netAmount", "Net due (CNY)"),
                    Map.entry("receivedAmount", "Received (CNY)"),
                    Map.entry("arrearsAmount", "Outstanding (CNY)"),
                    Map.entry("collectionRate", "Collection rate (%)"),
                    Map.entry("summary", "Filtered total"));

    public byte[] write(ReceivableReportResult result, String format, Locale locale) {
        var columns = columns(result.groupBy());
        try {
            return "excel".equals(format)
                    ? excel(result, columns, locale)
                    : pdf(result, columns, locale);
        } catch (Exception failure) {
            throw new org.hswebframework.web.exception.BusinessException(
                    "error.property_finance_report_export_failed", 500, failure);
        }
    }

    private byte[] excel(ReceivableReportResult result, List<String> columns, Locale locale)
            throws IOException {
        try (var book = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet(label("title", locale));
            var header = sheet.createRow(0);
            var style = book.createCellStyle();
            var font = book.createFont();
            font.setBold(true);
            style.setFont(font);
            for (int n = 0; n < columns.size(); n++) {
                var cell = header.createCell(n);
                cell.setCellValue(label(columns.get(n), locale));
                cell.setCellStyle(style);
                sheet.setColumnWidth(n, ("fullName".equals(columns.get(n)) ? 48 : 24) * 256);
            }
            int index = 1;
            for (var row : result.page().getData()) {
                var target = sheet.createRow(index++);
                var values = values(row);
                for (int n = 0; n < columns.size(); n++)
                    target.createCell(n).setCellValue(values.getOrDefault(columns.get(n), ""));
            }
            var footer = sheet.createRow(index++);
            footer.createCell(0).setCellValue(label("summary", locale));
            var amounts = amounts(result.summary());
            for (int n = 0; n < columns.size(); n++)
                if (amounts.containsKey(columns.get(n)))
                    footer.createCell(n).setCellValue(amounts.get(columns.get(n)));
            sheet.createRow(index).createCell(0).setCellValue(label("scope", locale));
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(
                    new org.apache.poi.ss.util.CellRangeAddress(
                            0, result.page().getData().size(), 0, columns.size() - 1));
            book.write(output);
            return output.toByteArray();
        }
    }

    private byte[] pdf(ReceivableReportResult result, List<String> columns, Locale locale)
            throws IOException {
        StringBuilder html =
                new StringBuilder(
                        "<html><head><meta charset='UTF-8'/><style>@page{size:A3"
                            + " landscape;margin:12mm}body{font-family:'Noto Sans"
                            + " SC';font-size:9px;color:#262626}h1{font-size:18px}table{width:100%;border-collapse:collapse;table-layout:fixed;-fs-table-paginate:paginate}th,td{border:1px"
                            + " solid"
                            + " #d9d9d9;padding:5px;word-wrap:break-word}th{background:#f5f5f5}thead{display:table-header-group}tr{page-break-inside:avoid}.money{text-align:right}.summary{font-weight:bold}</style></head><body><h1>");
        html.append(escape(label("title", locale)))
                .append("</h1><p>")
                .append(escape(label("scope", locale)))
                .append("</p><table><thead><tr>");
        for (String column : columns)
            html.append("<th>").append(escape(label(column, locale))).append("</th>");
        html.append("</tr></thead><tbody>");
        for (var row : result.page().getData()) appendRow(html, columns, values(row), false);
        var totals = new HashMap<>(amounts(result.summary()));
        totals.put(columns.get(0), label("summary", locale));
        appendRow(html, columns, totals, true);
        html.append("</tbody></table></body></html>");
        try (var output = new ByteArrayOutputStream()) {
            var renderer = new PdfRendererBuilder();
            renderer.useFastMode();
            renderer.useFont(
                    () ->
                            ReceivableReportFile.class.getResourceAsStream(
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

    private static void appendRow(
            StringBuilder html, List<String> columns, Map<String, String> values, boolean summary) {
        html.append(summary ? "<tr class='summary'>" : "<tr>");
        for (String c : columns)
            html.append(MONEY.contains(c) ? "<td class='money'>" : "<td>")
                    .append(escape(values.getOrDefault(c, "")))
                    .append("</td>");
        html.append("</tr>");
    }

    private static List<String> columns(String group) {
        var cols = new ArrayList<String>();
        if ("DETAIL".equals(group))
            cols.addAll(
                    List.of(
                            "projectName",
                            "fullName",
                            "customerName",
                            "subjectName",
                            "id",
                            "calcStartDate",
                            "calcEndDate",
                            "dueDate",
                            "calculatedAsOfDate"));
        else
            cols.addAll(
                    List.of("PROJECT".equals(group) ? "projectName" : "subjectName", "billCount"));
        cols.addAll(MONEY);
        return cols;
    }

    private static Map<String, String> values(ReceivableReportRow row) {
        var values = new HashMap<>(amounts(row.amounts()));
        values.put("projectName", Objects.toString(row.projectName(), ""));
        values.put("subjectName", Objects.toString(row.subjectName(), ""));
        values.put("fullName", Objects.toString(row.fullName(), ""));
        values.put("customerName", Objects.toString(row.customerName(), ""));
        values.put("id", Objects.toString(row.id(), ""));
        values.put("calcStartDate", Objects.toString(row.calcStartDate(), ""));
        values.put("calcEndDate", Objects.toString(row.calcEndDate(), ""));
        values.put("dueDate", Objects.toString(row.dueDate(), ""));
        values.put("calculatedAsOfDate", Objects.toString(row.calculatedAsOfDate(), ""));
        values.put("billCount", Long.toString(row.billCount()));
        return values;
    }

    private static Map<String, String> amounts(ReceivableReportAmounts a) {
        return Map.of(
                "receivableAmount",
                a.receivableAmount(),
                "remissionAmount",
                a.remissionAmount(),
                "netAmount",
                a.netAmount(),
                "receivedAmount",
                a.receivedAmount(),
                "arrearsAmount",
                a.arrearsAmount(),
                "collectionRate",
                a.collectionRate());
    }

    private static String label(String key, Locale locale) {
        return LocaleUtils.resolveMessage(
                "message.finance.report.receivable." + key,
                locale,
                ("zh".equals(locale.getLanguage()) ? ZH : EN).get(key));
    }

    private static String escape(String text) {
        return HtmlUtils.htmlEscape(text);
    }
}
