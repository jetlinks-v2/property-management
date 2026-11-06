package org.jetlinks.pro.property.finance.receivable;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

class HistoricalWorkbookTest {
    byte[] workbook(String actual) throws Exception {
        try (var book = new XSSFWorkbook();
                var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("历史应收");
            var header = sheet.createRow(0);
            for (int i = 0; i < HistoricalWorkbook.HEADERS.size(); i++)
                header.createCell(i).setCellValue(HistoricalWorkbook.HEADERS.get(i));
            var row = sheet.createRow(3);
            String[] values = {
                "HT-1",
                "园区/1栋/101",
                "龚超",
                "物业费",
                "面积月费",
                "2024-02-01",
                "2024-02-29",
                "2024-02",
                "2024-03-05",
                "100.25",
                "0.25",
                actual,
                "历史迁入"
            };
            for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
            book.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void physicalRowAndExactAmountArePreserved() throws Exception {
        var row = HistoricalWorkbook.parse(workbook("100.00")).get(0);
        assertEquals(4, row.row());
        assertEquals("100.00", row.actual().toPlainString());
        assertEquals("2024-02", row.accountPeriod());
    }

    @Test
    void lastRowMoneyMismatchHasRowAndFieldAndDoesNotReturnPartialData() throws Exception {
        var error =
                assertThrows(
                        org.hswebframework.web.exception.BusinessException.class,
                        () -> HistoricalWorkbook.parse(workbook("99.99")));
        assertEquals(4, error.getArgs()[0]);
        assertEquals("实际应收", error.getArgs()[1]);
    }

    @Test
    void templateHasChineseColumnsAndProjectReferencesWithDropdowns() throws Exception {
        var buffer =
                org.springframework.core.io.buffer.DataBufferUtils.join(
                                HistoricalWorkbook.template(
                                        "5G园区",
                                        java.util.List.of(java.util.List.of("5G园区/1栋/101", "龚超")),
                                        java.util.List.of(java.util.List.of("物业费", "面积月费"))))
                        .block(java.time.Duration.ofSeconds(10));
        byte[] bytes;
        try {
            bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
        } finally {
            org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
        }
        try (var book =
                org.apache.poi.ss.usermodel.WorkbookFactory.create(
                        new java.io.ByteArrayInputStream(bytes))) {
            var data = book.getSheetAt(0);
            assertEquals("合同编号", data.getRow(0).getCell(0).getStringCellValue());
            assertEquals("房屋完整路径", data.getRow(0).getCell(1).getStringCellValue());
            assertEquals(4, data.getDataValidations().size());
            assertEquals(
                    "5G园区/1栋/101",
                    book.getSheet("项目房屋与客户").getRow(1).getCell(0).getStringCellValue());
            assertEquals("龚超", book.getSheet("项目房屋与客户").getRow(1).getCell(1).getStringCellValue());
            assertThrows(
                    org.hswebframework.web.exception.BusinessException.class,
                    () -> HistoricalWorkbook.parse(bytes));
        }
    }

    @Test
    void repeatedRowAndFormulaCellFailWithPhysicalLine() throws Exception {
        try (var book = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook("100.00")));
                var out = new ByteArrayOutputStream()) {
            var sheet = book.getSheetAt(0);
            var row = sheet.createRow(4);
            for (int c = 0; c < HistoricalWorkbook.HEADERS.size(); c++)
                row.createCell(c).setCellValue(sheet.getRow(3).getCell(c).getStringCellValue());
            book.write(out);
            var duplicate =
                    assertThrows(
                            org.hswebframework.web.exception.BusinessException.class,
                            () -> HistoricalWorkbook.parse(out.toByteArray()));
            assertEquals(5, duplicate.getArgs()[0]);
            sheet.removeRow(row);
            sheet.getRow(3).getCell(11).setCellFormula("100+0");
            out.reset();
            book.write(out);
            var formula =
                    assertThrows(
                            org.hswebframework.web.exception.BusinessException.class,
                            () -> HistoricalWorkbook.parse(out.toByteArray()));
            assertEquals(4, formula.getArgs()[0]);
            assertEquals("实际应收", formula.getArgs()[1]);
        }
    }
}
