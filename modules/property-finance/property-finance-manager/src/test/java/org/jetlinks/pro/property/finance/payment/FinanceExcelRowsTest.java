package org.jetlinks.pro.property.finance.payment;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.LocalDateTime;

class FinanceExcelRowsTest {
    @TempDir Path directory;

    @Test
    void inlineStringsAndExcelDatesKeepPhysicalRowNumbers() throws Exception {
        var input = directory.resolve("inline.xlsx");
        var output = directory.resolve("rows.jsonl");
        try (var book = new SXSSFWorkbook(100);
                var stream = Files.newOutputStream(input)) {
            var sheet = book.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("日期");
            var row = sheet.createRow(3);
            row.createCell(0).setCellValue("房屋");
            var date = row.createCell(1);
            date.setCellValue(LocalDateTime.of(2026, 11, 7, 14, 25));
            var style = book.createCellStyle();
            style.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
            date.setCellStyle(style);
            book.write(stream);
            book.dispose();
        }
        FinanceExcelRows.spool(input, output);
        var rows = FinanceExcelRows.read(output).collectList().block();
        assertEquals(2, rows.size());
        assertEquals(4, rows.get(1).number());
        assertEquals("房屋", rows.get(1).cells().get(0));
        assertTrue(rows.get(1).cells().get(1).startsWith("2026-11-07"), rows.get(1).cells().get(1));
        assertTrue(rows.get(1).cells().get(1).contains("14:25"), rows.get(1).cells().get(1));
    }

    @Test
    void formulaIsRejectedRatherThanUsingCachedResult() throws Exception {
        var input = directory.resolve("formula.xlsx");
        try (var book = new SXSSFWorkbook(100);
                var stream = Files.newOutputStream(input)) {
            var sheet = book.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("amount");
            sheet.createRow(1).createCell(0).setCellFormula("1+1");
            book.write(stream);
            book.dispose();
        }
        var error =
                assertThrows(
                        org.hswebframework.web.exception.BusinessException.class,
                        () -> FinanceExcelRows.spool(input, directory.resolve("rows.jsonl")));
        assertEquals("error.property_finance_collection_row", error.getI18nCode());
        assertEquals(2, error.getArgs()[0]);
    }

    @Test
    void moreThanFiftyThousandRowsFailsAtTheActualLimit() throws Exception {
        var input = directory.resolve("oversize.xlsx");
        try (var book = new SXSSFWorkbook(100);
                var stream = Files.newOutputStream(input)) {
            var sheet = book.createSheet();
            for (int i = 0; i <= 50001; i++)
                sheet.createRow(i).createCell(0).setCellValue("row " + i);
            book.write(stream);
            book.dispose();
        }
        var error =
                assertThrows(
                        org.hswebframework.web.exception.BusinessException.class,
                        () -> FinanceExcelRows.spool(input, directory.resolve("rows.jsonl")));
        assertEquals(50002, error.getArgs()[0]);
    }
}
