package org.jetlinks.pro.property.base.excel;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hswebframework.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;

import java.io.*;
import java.math.BigDecimal;
import java.util.*;

class ArchiveExcelTest {
    @Test
    void officialWriterKeepsChineseHeadersReferencesAndLeadingZeros() throws Exception {
        var columns = ArchiveExcel.columns(true);
        var row = new HashMap<String, Object>();
        row.put("spaceId", "00101");
        row.put("userId", "000012");
        row.put("telephone", "00123456789");
        var document =
                new ArchiveExcel.Document(
                        "客户模板.xlsx",
                        new ArchiveExcel.SheetData("导入数据", columns, List.of(row)),
                        List.of(
                                new ArchiveExcel.SheetData(
                                        "空间参考",
                                        List.of("spaceId", "fullName"),
                                        List.of(
                                                Map.of(
                                                        "spaceId",
                                                        "00101",
                                                        "fullName",
                                                        "5G园区/1栋/101")))));
        byte[] bytes = bytes(document);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertEquals("导入数据", workbook.getSheetName(0));
            assertEquals("@", workbook.getSheetAt(0).getColumnStyle(0).getDataFormatString());
            assertEquals("空间参考", workbook.getSheetName(1));
            assertEquals(
                    "空间标识（请勿修改）", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals("00101", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals(
                    "5G园区/1栋/101",
                    workbook.getSheet("空间参考").getRow(1).getCell(1).getStringCellValue());
        }
        var parsed = ArchiveExcel.read(bytes, true);
        assertEquals(1, parsed.size());
        assertEquals("000012", parsed.get(0).get("userId"));
        assertEquals("00123456789", parsed.get(0).get("telephone"));
    }

    @Test
    void emptyDataTemplateStillContainsHeadersAndSpaceReferences() throws Exception {
        var doc =
                new ArchiveExcel.Document(
                        "房屋模板.xlsx",
                        new ArchiveExcel.SheetData("导入数据", ArchiveExcel.columns(false), List.of()),
                        List.of(
                                new ArchiveExcel.SheetData(
                                        "空间参考",
                                        List.of("spaceId"),
                                        List.of(Map.of("spaceId", "101")))));
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes(doc)))) {
            assertEquals("房屋类型", book.getSheetAt(0).getRow(0).getCell(ArchiveExcel.columns(false).indexOf("houseType")).getStringCellValue());
            assertEquals(0, book.getSheetAt(0).getLastRowNum());
            assertEquals("101", book.getSheet("空间参考").getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    void rejectsSparseBlankRowsRatherThanLosingLastRowOrRenumbering() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = dataSheet(book);
            sheet.createRow(1).createCell(0).setCellValue("101");
            sheet.createRow(3).createCell(0).setCellValue("103");
            var error =
                    assertThrows(
                            BusinessException.class,
                            () -> ArchiveExcel.read(serialize(book), false));
            assertEquals("error.property_excel_cell", error.getI18nCode());
            assertEquals(3, error.getArgs()[0]);
        }
    }

    @Test
    void rejectsFormulaAndNumericIdentifiersWithActualRowAndField() throws Exception {
        try (var book = new HSSFWorkbook()) {
            var sheet = dataSheet(book);
            sheet.createRow(1).createCell(0).setCellValue("101");
            sheet.createRow(2).createCell(0).setCellFormula("1+1");
            var formula =
                    assertThrows(
                            BusinessException.class,
                            () -> ArchiveExcel.read(serialize(book), false));
            assertEquals(3, formula.getArgs()[0]);
            sheet.getRow(2).getCell(0).setCellValue(102D);
            var numeric =
                    assertThrows(
                            BusinessException.class,
                            () -> ArchiveExcel.read(serialize(book), false));
            assertEquals(3, numeric.getArgs()[0]);
        }
    }

    @Test
    void rejectsDuplicateHeadersButAcceptsMoreThanOneThousandRows() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = dataSheet(book);
            sheet.getRow(0).getCell(1).setCellValue(ArchiveExcel.header("spaceId"));
            assertThrows(BusinessException.class, () -> ArchiveExcel.read(serialize(book), false));
        }
        try (var book = new XSSFWorkbook()) {
            var sheet = dataSheet(book);
            for (int row = 1; row <= 1001; row++)
                sheet.createRow(row).createCell(0).setCellValue(Integer.toString(row));
            var result = ArchiveExcel.read(serialize(book), false);
            assertEquals(1001, result.size());
            assertEquals("1001", result.get(1000).get("spaceId"));
        }
        assertThrows(BusinessException.class, () -> ArchiveExcel.read(new byte[] {1, 2, 3}, false));
    }

    @Test
    void parsesDecimalsAndStrictDatesWithoutRoundingOrNormalizingInvalidDates() {
        assertEquals(
                new BigDecimal("123.45"),
                ArchiveExcel.decimal(Map.of("billingArea", "123.45"), "billingArea", 4, true));
        assertThrows(
                BusinessException.class,
                () -> ArchiveExcel.date(Map.of("startTime", "2026-02-30"), "startTime", 4, true));
        assertNotNull(ArchiveExcel.date(Map.of("startTime", "2026-11-04"), "startTime", 4, true));
    }

    static Sheet dataSheet(Workbook book) {
        var sheet = book.createSheet("导入数据");
        var row = sheet.createRow(0);
        var columns = ArchiveExcel.HOUSE;
        for (int c = 0; c < columns.size(); c++)
            row.createCell(c).setCellValue(ArchiveExcel.header(columns.get(c)));
        return sheet;
    }

    static byte[] serialize(Workbook book) throws IOException {
        var out = new ByteArrayOutputStream();
        book.write(out);
        return out.toByteArray();
    }

    static byte[] bytes(ArchiveExcel.Document document) {
        var buffer =
                DataBufferUtils.join(ArchiveExcel.write(document))
                        .block(java.time.Duration.ofSeconds(20));
        assertNotNull(buffer);
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }
}
