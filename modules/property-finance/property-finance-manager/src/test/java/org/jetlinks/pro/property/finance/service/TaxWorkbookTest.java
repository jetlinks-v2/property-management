package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.finance.tax.TaxWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** 原始表格验证：税率百分比单元格仍读小数，错误含物理行号，重复和公式整批失败。 */
class TaxWorkbookTest {
    @Test
    void templateRoundTripRetainsPercentRateAndLeadingZeroClassification() throws Exception {
        var rows = TaxWorkbook.read(workbook(false, false), "company");
        assertEquals(1, rows.size());
        assertEquals("00123", rows.get(0).getGoodsCode());
        assertEquals(0, rows.get(0).getTaxRate().compareTo(new java.math.BigDecimal("0.06")));
    }

    @Test
    void priceValidationUsesTwoDecimalsAndReportsPhysicalRow() throws Exception {
        for (String price : new String[] {"80", "80.10", "80.1000000000", "0"}) {
            var rows = TaxWorkbook.read(workbookWithPrice(price), "company");
            assertEquals(2, rows.get(0).getPrice().scale());
            assertEquals(0, new java.math.BigDecimal(price).compareTo(rows.get(0).getPrice()));
        }
        var failure = assertThrows(BusinessException.class,
                () -> TaxWorkbook.read(workbookWithPrice("80.123"), "company"));
        String message = failure.getArgs()[0].toString();
        assertTrue(message.contains("2:"));
        assertTrue(message.contains("price") || message.contains("单价"));
        assertEquals(1, failure.getArgs()[2]);
    }

    private byte[] workbookWithPrice(String price) throws Exception {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(workbook(false, false)));
                var out = new ByteArrayOutputStream()) {
            workbook.getSheetAt(0).getRow(1).createCell(5).setCellValue(price);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void malformedSecondRowDoesNotReturnPartialPlan() throws Exception {
        var failure =
                assertThrows(
                        BusinessException.class,
                        () -> TaxWorkbook.read(workbook(true, false), "company"));
        assertTrue(String.valueOf(failure.getArgs()[0]).contains("3"));
    }

    @Test
    void formulasAreRejectedInsteadOfTrustingCachedValues() throws Exception {
        assertThrows(
                BusinessException.class, () -> TaxWorkbook.read(workbook(false, true), "company"));
    }

    @Test
    void normalizedDuplicateReportsBothPhysicalRows() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(workbook(false, false)));
                var out = new ByteArrayOutputStream()) {
            var row = workbook.getSheetAt(0).createRow(2);
            row.createCell(0).setCellValue(" 物业服务 ");
            row.createCell(2).setCellValue(0.06);
            row.createCell(7).setCellValue("00123");
            row.createCell(8).setCellValue("服务");
            workbook.write(out);
            bytes = out.toByteArray();
        }
        var failure =
                assertThrows(BusinessException.class, () -> TaxWorkbook.read(bytes, "company"));
        assertEquals(2, failure.getArgs()[1]);
        assertEquals(2, failure.getArgs()[2]);
        assertTrue(failure.getArgs()[0].toString().contains("2:"));
        assertTrue(failure.getArgs()[0].toString().contains("3:"));
    }

    byte[] workbook(boolean invalid, boolean formula) throws Exception {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(TaxWorkbook.template()));
                var out = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("物业服务");
            row.createCell(2).setCellValue(0.06);
            row.createCell(7).setCellValue("00123");
            row.createCell(8).setCellValue("服务");
            if (formula) row.getCell(2).setCellFormula("1/10");
            if (invalid) {
                var bad = sheet.createRow(2);
                bad.createCell(0).setCellValue("错误行");
                bad.createCell(2).setCellValue("bad");
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
