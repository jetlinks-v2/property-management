package org.jetlinks.pro.patrol.web;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetlinks.pro.patrol.service.PatrolStatisticsService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PatrolStatisticsExportTest {
    @Test
    void workbookPreservesPeriodCountsTotalsAndTreatsNamesAsText() throws Exception {
        var item = new PatrolStatisticsService.Row("p", "=1+1", 1, 4, 1, 8, 3, 5, 37.5, 25, "统计项目");
        var report = new PatrolStatisticsService.Report(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3), List.of(item), item);
        for (boolean qualification : new boolean[]{false, true}) {
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(PatrolStatisticsExport.write(report, false, qualification)))) {
                var sheet = workbook.getSheetAt(0);
                assertEquals("总计", sheet.getRow(1).getCell(0).getStringCellValue());
                var row = sheet.getRow(2);
                assertEquals("所属项目", sheet.getRow(0).getCell(7).getStringCellValue());
                assertEquals("全部项目汇总", sheet.getRow(1).getCell(7).getStringCellValue());
                assertEquals("统计项目", row.getCell(7).getStringCellValue());
                assertEquals(CellType.STRING, row.getCell(0).getCellType());
                assertEquals("=1+1", row.getCell(0).getStringCellValue());
                assertEquals("2026-11-01 ～ 2026-11-03", row.getCell(1).getStringCellValue());
                assertEquals(qualification ? 4 : 8, row.getCell(3).getNumericCellValue());
                assertEquals(qualification ? 3 : 5, row.getCell(5).getNumericCellValue());
                assertEquals(qualification ? 25 : 37.5, row.getCell(6).getNumericCellValue());
            }
        }
    }
}
