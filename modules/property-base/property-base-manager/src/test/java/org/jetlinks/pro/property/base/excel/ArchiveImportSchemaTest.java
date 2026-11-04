package org.jetlinks.pro.property.base.excel;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hswebframework.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;

import java.io.ByteArrayInputStream;
import java.util.*;

class ArchiveImportSchemaTest {
    @Test
    void personalAndCompanyTemplatesOnlyContainTheirFormFieldsAndRejectWrongType()
            throws Exception {
        var personal = ArchiveImportSchema.columns("person");
        var company = ArchiveImportSchema.columns("company");
        assertEquals(8, personal.size());
        assertEquals(8, company.size());
        assertFalse(personal.contains("creditCode"));
        assertFalse(company.contains("nature"));
        assertTrue(personal.stream().noneMatch(k -> k.endsWith("Id") || k.equals("familyGroup")));
        var data =
                new ArchiveExcel.SheetData(
                        "导入数据",
                        personal,
                        List.of(
                                Map.of(
                                        "spacePath",
                                        "园区/101",
                                        "personName",
                                        "张三",
                                        "telephone",
                                        "00123")));
        var bytes = bytes(new ArchiveExcel.Document("个人.xlsx", data, List.of()));
        assertEquals("00123", ArchiveExcel.read(bytes, personal).get(0).get("telephone"));
        assertThrows(BusinessException.class, () -> ArchiveExcel.read(bytes, company));
    }

    @Test
    void dropdownsUseNamedRangesForLongListsAndTemplatesExcludeFamilyAndTime() throws Exception {
        var columns = ArchiveImportSchema.columns("person");
        assertFalse(columns.contains("startTime"));
        assertTrue(columns.stream().noneMatch(c -> c.startsWith("family")));
        var document =
                new ArchiveExcel.Document(
                        "模板.xlsx",
                        new ArchiveExcel.SheetData("导入数据", columns, List.of()),
                        List.of(),
                        Map.of(
                                "spacePath",
                                List.of("园区/1栋/101", "园区/1栋/102"),
                                "nature",
                                java.util.stream.IntStream.range(0, 100)
                                        .mapToObj(i -> "客户性质" + i)
                                        .toList()));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes(document)))) {
            assertEquals(
                    "完整空间路径", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals(2, workbook.getSheetAt(0).getDataValidations().size());
            assertTrue(workbook.isSheetVeryHidden(workbook.getSheetIndex("_导入选项")));
            assertTrue(
                    workbook.getAllNames().stream()
                            .anyMatch(n -> n.getRefersToFormula().contains("$100")));
            assertTrue(
                    workbook.getSheetAt(0).getDataValidations().stream()
                            .allMatch(v -> v.getShowErrorBox()));
        }
    }

    @Test
    void localDateTimeIsStrictAndRetainsTimeOfDay() {
        var date =
                ArchiveExcel.date(Map.of("startTime", "2026-11-04 09:30:15"), "startTime", 2, true);
        assertEquals(
                java.time.LocalDateTime.of(2026, 11, 4, 9, 30, 15)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli(),
                date);
        assertThrows(
                BusinessException.class,
                () ->
                        ArchiveExcel.date(
                                Map.of("startTime", "2026-02-30 09:30:15"), "startTime", 7, true));
    }

    static byte[] bytes(ArchiveExcel.Document document) {
        var buffer = DataBufferUtils.join(ArchiveExcel.write(document)).block();
        try {
            var bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }
}
