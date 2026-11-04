package org.jetlinks.pro.patrol.service;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetlinks.pro.patrol.entity.PatrolPointEntity;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PatrolPointWorkbookTest {
    static byte[] workbook(String category, String categoryName, int count) throws Exception {
        return workbook(new XSSFWorkbook(), category, categoryName, count);
    }
    private static byte[] workbook(Workbook book, String category, String categoryName, int count) throws Exception {
        try (book; var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("点位导入"); var header = sheet.createRow(0);
            for (int i = 0; i < PatrolPointWorkbook.HEADERS.length; i++) header.createCell(i).setCellValue(PatrolPointWorkbook.HEADERS[i]);
            for (int i = 0; i < count; i++) {
                var row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(category); row.createCell(1).setCellValue(categoryName);
                row.createCell(2).setCellValue("Point-" + i); row.createCell(3).setCellValue("Floor 1");
            }
            book.write(out); return out.toByteArray();
        }
    }

    @Test
    void oldFourColumnsAndBothExcelFormatsWorkAtFullBatch() throws Exception {
        assertEquals(1000, PatrolPointWorkbook.read(workbook("category", "Building", 1000)).size());
        assertEquals(2, PatrolPointWorkbook.read(workbook(new HSSFWorkbook(), "category", "Building", 2)).size());
        assertThrows(RuntimeException.class, () -> PatrolPointWorkbook.read(workbook("category", "Building", 1001)));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(PatrolPointWorkbook.template(Map.of("c", "分类"))))) {
            assertEquals("分类id", book.getSheet("点位导入").getRow(0).getCell(0).getStringCellValue());
            assertEquals("c", book.getSheet("分类参考").getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    void invalidRowsExposeExactExcelLineAndDuplicatesFormulasLengths() throws Exception {
        byte[] bytes;
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(workbook("category", "Building", 2))); var out = new ByteArrayOutputStream()) {
            var sheet = book.getSheetAt(0);
            sheet.getRow(2).getCell(2).setCellValue("Point-0");
            var row = sheet.createRow(4);
            row.createCell(0).setCellValue("category"); row.createCell(1).setCellValue("Building");
            row.createCell(2).setCellValue("x".repeat(101)); row.createCell(3).setCellFormula("1+1");
            book.write(out); bytes = out.toByteArray();
        }
        var rows = PatrolPointWorkbook.read(bytes);
        assertEquals(List.of("duplicate"), rows.get(1).errors());
        assertEquals(5, rows.get(2).rowNumber());
        assertTrue(rows.get(2).errors().containsAll(List.of("formula", "required", "nameLength")));
        assertFalse(new PatrolPointWorkbook.Preview(rows).valid());
    }

    @Test
    void exportUsesReadableFieldsAndLiteralCells() throws Exception {
        var point = new PatrolPointEntity(); point.setId("p"); point.setName("=1+1"); point.setCode("P001");
        point.setCategoryId("c"); point.setTagIds(List.of("t", "hidden")); point.setLocation("Floor 1");
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(PatrolPointWorkbook.export(List.of(point), Map.of("c", "Building"), Map.of("t", "Tag"), Map.of("p", "Security"))))) {
            var row = book.getSheetAt(0).getRow(1);
            assertEquals(CellType.STRING, row.getCell(0).getCellType()); assertEquals("=1+1", row.getCell(0).getStringCellValue());
            assertEquals("Building", row.getCell(2).getStringCellValue()); assertEquals("Security", row.getCell(3).getStringCellValue());
            assertEquals("Tag", row.getCell(4).getStringCellValue()); assertEquals("Floor 1", row.getCell(5).getStringCellValue());
        }
    }
}
