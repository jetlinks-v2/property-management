package org.jetlinks.pro.property.finance.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.jetlinks.pro.property.finance.report.*;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

/** POI窗口200行并写临时磁盘，调用者仅在boundedElastic线程执行，失败不发布半个文件。 */
final class StreamingReportFile implements AutoCloseable {
    private final SXSSFWorkbook book = new SXSSFWorkbook(200);
    private final List<FinanceReportFile.Data> pdf = new ArrayList<>();
    private final Set<String> spaces = new TreeSet<>();
    private final CellStyle money;
    private final CellStyle header;
    private final Path root;
    private final boolean isPdf;
    private Sheet sheet;
    private FinanceReportFile.Data data;
    private int row;

    StreamingReportFile(Path root, boolean pdf) throws IOException {
        Files.createDirectories(root);
        this.root = root;
        this.isPdf = pdf;
        book.setCompressTempFiles(true);
        money = book.createCellStyle();
        money.setDataFormat(book.createDataFormat().getFormat("#,##0.00;[Red]-#,##0.00"));
        header = book.createCellStyle();
        var font = book.createFont();
        font.setBold(true);
        header.setFont(font);
        header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    }

    void append(ReportExportPages.Page page, boolean write, boolean first) {
        spaces.addAll(page.spaces());
        if (spaces.size() > 20000) throw FinanceScope.error("report_result_limit", 400);
        if (!write) return;
        if (first) {
            finishSheet();
            data = page.data();
            row = 0;
            sheet = book.createSheet("报表" + (book.getNumberOfSheets() + 1));
            var titles = sheet.createRow(row++);
            for (int n = 0; n < data.columns().size(); n++) {
                var c = titles.createCell(n);
                c.setCellValue(data.labels().get(data.columns().get(n)));
                c.setCellStyle(header);
                sheet.setColumnWidth(n, (data.columns().get(n).equals("fullName") ? 46 : 24) * 256);
            }
            sheet.createFreezePane(0, 1);
        }
        for (var values : page.data().rows()) {
            if (row >= 1_048_574) throw FinanceScope.error("report_excel_limit", 400);
            if (isPdf && row > 2000) throw FinanceScope.error("report_pdf_limit", 400);
            var r = sheet.createRow(row++);
            for (int n = 0; n < data.columns().size(); n++) {
                String key = data.columns().get(n), value = values.getOrDefault(key, "");
                var cell = r.createCell(n);
                if (data.money().contains(key) && !value.isBlank()) {
                    amount(cell, value);
                } else cell.setCellValue(value);
            }
        }
        if (isPdf) {
            if (first) pdf.add(page.data());
            else {
                var previous = pdf.remove(pdf.size() - 1);
                var values = new ArrayList<>(previous.rows());
                values.addAll(page.data().rows());
                pdf.add(
                        new FinanceReportFile.Data(
                                previous.title(),
                                previous.scope(),
                                previous.summaryLabel(),
                                previous.columns(),
                                previous.labels(),
                                previous.money(),
                                values,
                                previous.summary()));
            }
        }
    }

    private void finishSheet() {
        if (sheet == null || data == null) return;
        var r = sheet.createRow(row);
        for (int n = 0; n < data.columns().size(); n++) {
            var c = r.createCell(n);
            String key = data.columns().get(n);
            if (n == 0) c.setCellValue(data.summaryLabel());
            else if (data.summary().containsKey(key)) {
                if (data.money().contains(key)) amount(c, data.summary().get(key));
                else c.setCellValue(data.summary().get(key));
            }
        }
    }

    private void amount(Cell cell, String value) {
        var amount = new BigDecimal(value);
        if (amount.stripTrailingZeros().precision() <= 15) cell.setCellValue(amount.doubleValue());
        else cell.setCellValue(value);
        cell.setCellStyle(money);
    }

    void finish(String id, String format, String conditions) throws IOException {
        finishSheet();
        var filters = book.createSheet("查询条件");
        String[] lines = conditions.split("\n");
        for (int n = 0; n < lines.length; n++)
            filters.createRow(n).createCell(0).setCellValue(lines[n]);
        filters.setColumnWidth(0, 100 * 256);
        Path temp = root.resolve(id + ".tmp"), target = root.resolve(id + "." + format);
        if (isPdf)
            Files.write(
                    temp,
                    FinanceReportFile.write(
                            new ReportWorkbook(pdf, Map.of("查询条件", conditions)), "pdf"));
        else
            try (var output = Files.newOutputStream(temp)) {
                book.write(output);
            }
        Files.writeString(root.resolve(id + ".scope"), String.join("\n", spaces));
        Files.move(
                temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void close() throws IOException {
        try {
            book.close();
        } finally {
            book.dispose();
        }
    }
}
