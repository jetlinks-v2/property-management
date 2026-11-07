package org.jetlinks.pro.property.finance.payment;

import com.alibaba.fastjson.JSON;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** XLSX只解析首张数据表，行落磁盘后按需求读取；不把整份工作簿或所有草稿装入堆。 */
public final class FinanceExcelRows {
    public static final int LIMIT = 50_000;
    public static final int MAX_BYTES = 20 * 1024 * 1024;

    private FinanceExcelRows() {}

    public record Row(int number, List<String> cells) {}

    public static void spool(Path input, Path output) throws Exception {
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            var size = new long[1];
            Consumer<Row> sink =
                    row -> {
                        if (row.number() > LIMIT + 1)
                            throw invalid(row.number(), "文件", "collection_limit");
                        if (row.cells().size() > 32
                                || row.cells().stream().anyMatch(v -> v.length() > 4000))
                            throw invalid(row.number(), "单元格", "collection_field_invalid");
                        var json =
                                JSON.toJSONString(
                                        Map.of("number", row.number(), "cells", row.cells()));
                        size[0] += json.length();
                        if (size[0] > 80L * 1024 * 1024)
                            throw invalid(row.number(), "文件", "collection_file_invalid");
                        try {
                            writer.write(json);
                            writer.newLine();
                        } catch (IOException failure) {
                            throw new UncheckedIOException(failure);
                        }
                    };
            if (input.toString().endsWith(".xls")) {
                // 旧二进制Excel受更小的文件上限保护；大批量优先使用下载的xlsx模板。
                if (Files.size(input) > 5 * 1024 * 1024)
                    throw invalid(1, "文件", "collection_xls_large");
                try (var book = WorkbookFactory.create(input.toFile())) {
                    var sheet = book.getSheetAt(0);
                    var fmt = formatter();
                    if (sheet.getLastRowNum() > LIMIT)
                        throw invalid(LIMIT + 2, "文件", "collection_limit");
                    for (var row : sheet) {
                        var values = new ArrayList<String>();
                        if (row.getLastCellNum() > 32)
                            throw invalid(row.getRowNum() + 1, "表头", "collection_header_invalid");
                        for (int c = 0; c < row.getLastCellNum(); c++) {
                            var cell = row.getCell(c);
                            if (cell != null
                                    && (cell.getCellType() == CellType.FORMULA
                                            || cell.getCellType() == CellType.ERROR))
                                throw invalid(
                                        row.getRowNum() + 1,
                                        "第" + (c + 1) + "列",
                                        "collection_formula_denied");
                            values.add(cell == null ? "" : fmt.formatCellValue(cell).trim());
                        }
                        sink.accept(new Row(row.getRowNum() + 1, values));
                    }
                }
                return;
            }
            // 限制解压体积，包含共享字符串；防止小压缩包导致POI字典占满内存。
            try (var zip = new ZipFile(input.toFile())) {
                long expanded = 0;
                for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                    var entry = entries.nextElement();
                    if (entry.getSize() < 0 || entry.getSize() > 64L * 1024 * 1024)
                        throw invalid(1, "文件", "collection_file_invalid");
                    expanded += entry.getSize();
                    if (expanded > 128L * 1024 * 1024)
                        throw invalid(1, "文件", "collection_file_invalid");
                }
            }
            try (var pkg = OPCPackage.open(input.toFile(), PackageAccess.READ)) {
                var reader = new XSSFReader(pkg);
                reader.setUseReadOnlySharedStringsTable(true);
                var strings = reader.getSharedStringsTable();
                if (strings != null && strings.getUniqueCount() > 1_000_000)
                    throw invalid(1, "文件", "collection_file_invalid");
                var sheets = reader.getSheetsData();
                if (!sheets.hasNext()) throw invalid(1, "文件", "collection_file_invalid");
                try (var sheet = sheets.next()) {
                    var rows =
                            new XSSFSheetXMLHandler.SheetContentsHandler() {
                                int number;
                                List<String> cells;

                                public void startRow(int index) {
                                    number = index + 1;
                                    cells = new ArrayList<>();
                                    if (number > LIMIT + 1)
                                        throw invalid(number, "文件", "collection_limit");
                                }

                                public void endRow(int index) {
                                    sink.accept(new Row(number, cells));
                                }

                                public void cell(String ref, String value, XSSFComment comment) {
                                    int col =
                                            new org.apache.poi.ss.util.CellReference(ref).getCol();
                                    if (col >= 32)
                                        throw invalid(number, "表头", "collection_header_invalid");
                                    while (cells.size() <= col) cells.add("");
                                    cells.set(col, value == null ? "" : value.trim());
                                }
                            };
                    var handler =
                            new XSSFSheetXMLHandler(
                                    reader.getStylesTable(),
                                    null,
                                    strings,
                                    rows,
                                    formatter(),
                                    false);
                    var parser = XMLHelper.newXMLReader();
                    parser.setContentHandler(
                            new DefaultHandler() {
                                int number = 1, col = 0;

                                public void startElement(
                                        String uri, String local, String q, Attributes a)
                                        throws org.xml.sax.SAXException {
                                    if ("row".equals(local))
                                        number = Integer.parseInt(a.getValue("r"));
                                    if ("c".equals(local)) {
                                        col =
                                                new org.apache.poi.ss.util.CellReference(
                                                                a.getValue("r"))
                                                        .getCol();
                                        if ("e".equals(a.getValue("t")))
                                            throw invalid(
                                                    number,
                                                    "第" + (col + 1) + "列",
                                                    "collection_formula_denied");
                                    }
                                    if ("f".equals(local))
                                        throw invalid(
                                                number,
                                                "第" + (col + 1) + "列",
                                                "collection_formula_denied");
                                    handler.startElement(uri, local, q, a);
                                }

                                public void endElement(String uri, String local, String q)
                                        throws org.xml.sax.SAXException {
                                    handler.endElement(uri, local, q);
                                }

                                public void characters(char[] ch, int start, int length)
                                        throws org.xml.sax.SAXException {
                                    handler.characters(ch, start, length);
                                }
                            });
                    parser.parse(new InputSource(sheet));
                }
            }
        }
    }

    public static Flux<Row> read(Path spool) {
        return Flux.using(
                        () -> Files.newBufferedReader(spool, StandardCharsets.UTF_8),
                        reader ->
                                Flux.<Row>generate(
                                        sink -> {
                                            try {
                                                var text = reader.readLine();
                                                if (text == null) sink.complete();
                                                else sink.next(decodeRow(text));
                                            } catch (Exception e) {
                                                sink.error(e);
                                            }
                                        }),
                        reader -> {
                            try {
                                reader.close();
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static Row decodeRow(String text) {
        var json = JSON.parseObject(text);
        return new Row(
                json.getIntValue("number"), json.getJSONArray("cells").toJavaList(String.class));
    }

    private static DataFormatter formatter() {
        return new DataFormatter(Locale.ROOT) {
            @Override
            public String formatRawCellContents(
                    double value, int formatIndex, String formatString, boolean date1904) {
                if (DateUtil.isADateFormat(formatIndex, formatString)
                        && DateUtil.isValidExcelDate(value)) {
                    var time = DateUtil.getLocalDateTime(value, date1904);
                    return time.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                            ? time.toLocalDate().toString()
                            : time.withSecond(0).withNano(0).toString();
                }
                return super.formatRawCellContents(value, formatIndex, formatString, date1904);
            }
        };
    }

    private static RuntimeException invalid(int row, String field, String code) {
        return CollectionImportWorkbook.rowError(row, field, code);
    }
}
