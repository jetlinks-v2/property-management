package org.jetlinks.pro.property.finance.payment;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hswebframework.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;

import java.io.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

class CollectionImportWorkbookTest {
    @Test
    void frameworkTemplateHasBlankDataSeparateExampleRequiredRedAndEveryChoiceDropdown()
            throws Exception {
        var references =
                Map.of(
                        "房屋客户参考",
                        List.of(List.of("房屋", "客户"), List.of("园区/101", "脱敏客户")),
                        "科目标准参考",
                        List.of(List.of("科目", "标准"), List.of("物业费", "月费")),
                        "公司支付参考",
                        List.of(List.of("公司", "支付方式", "银行"), List.of("测试物业", "现金", "")),
                        "应收参考",
                        List.of(List.of("应收ID"), List.of("bill-1")));
        for (var kind : CollectionImportWorkbook.KINDS) {
            var joined =
                    DataBufferUtils.join(CollectionImportWorkbook.template(kind, references))
                            .block(Duration.ofSeconds(10));
            byte[] bytes;
            try {
                bytes = new byte[joined.readableByteCount()];
                joined.read(bytes);
            } finally {
                DataBufferUtils.release(joined);
            }
            try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                var data = workbook.getSheetAt(0);
                assertEquals("收款导入", data.getSheetName());
                assertEquals(0, data.getLastRowNum());
                assertEquals(8, data.getDataValidations().size());
                for (var c : List.of(0, 6, 7, 9, 10, 11))
                    assertEquals(
                            IndexedColors.RED.getIndex(),
                            workbook.getFontAt(
                                            data.getRow(0).getCell(c).getCellStyle().getFontIndex())
                                    .getColor());
                assertEquals(
                        "示例-请勿导入",
                        workbook.getSheet("填写示例").getRow(1).getCell(0).getStringCellValue());
                assertEquals(
                        "2024-01-15 10:00",
                        workbook.getSheet("填写示例").getRow(1).getCell(10).getStringCellValue());
                assertEquals(
                        "RECEIVED".equals(kind) ? "0.00" : "",
                        workbook.getSheet("填写示例").getRow(1).getCell(17).getStringCellValue());
                assertThrows(
                        BusinessException.class, () -> CollectionImportWorkbook.parse(bytes, kind));
            }
        }
    }

    @Test
    void workbookNormalizesMinuteDateTimeAndPreservesPhysicalRow() throws Exception {
        var bytes = workbook(false, false);
        var parsed = CollectionImportWorkbook.parse(bytes, "RECEIVED");
        assertEquals(1, parsed.size());
        assertEquals(4, parsed.get(0).row());
        assertEquals("2024-02-15T10:25", parsed.get(0).payload().getPaidAt());
        assertEquals(new BigDecimal("80.00"), parsed.get(0).payload().getAmount());
    }

    @Test
    void duplicateReceivableIsRejectedAtSecondPhysicalRowBeforeStaging() throws Exception {
        var error =
                assertThrows(
                        BusinessException.class,
                        () -> CollectionImportWorkbook.parse(workbook(true, false), "RECEIVED"));
        assertEquals(5, error.getArgs()[0]);
        assertEquals("应收明细ID", error.getArgs()[1]);
    }

    @Test
    void formulasAreRejectedWithFieldAndPhysicalRow() throws Exception {
        var error =
                assertThrows(
                        BusinessException.class,
                        () -> CollectionImportWorkbook.parse(workbook(false, true), "RECEIVED"));
        assertEquals(4, error.getArgs()[0]);
        assertEquals("收款金额", error.getArgs()[1]);
    }

    private byte[] workbook(boolean duplicate, boolean formula) throws Exception {
        try (var book = new XSSFWorkbook();
                var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("data");
            var head = sheet.createRow(0);
            for (int c = 0; c < CollectionImportWorkbook.HEADERS.size(); c++)
                head.createCell(c).setCellValue(CollectionImportWorkbook.HEADERS.get(c));
            for (int n = 0; n < (duplicate ? 2 : 1); n++) {
                var row = sheet.createRow(3 + n);
                var values =
                        Map.of(
                                0,
                                "external-" + n,
                                1,
                                "bill-1",
                                6,
                                "测试物业",
                                7,
                                "现金",
                                9,
                                "80.00",
                                10,
                                "2024-02-15 10:25",
                                11,
                                "脱敏缴款人");
                values.forEach((c, v) -> row.createCell(c).setCellValue(v));
                if (formula) row.getCell(9).setCellFormula("40+40");
            }
            book.write(out);
            return out.toByteArray();
        }
    }
}
