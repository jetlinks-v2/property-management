package org.jetlinks.pro.patrol.web;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetlinks.pro.patrol.entity.PatrolGenerationEntity;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PatrolTaskGroupExportTest {
    @Test
    void groupWorkbookKeepsSnapshotCountsCrossDayTimesAndLiteralNames() throws Exception {
        var group = new PatrolGenerationEntity();
        group.setPointName("=1+1"); group.setCategoryName("原分类"); group.setLevelName("原等级");
        group.setBelongDate("2026-11-03"); group.setRoleName("夜班岗");
        group.setStartTime(OffsetDateTime.parse("2026-11-03T23:00:00+08:00").toInstant().toEpochMilli());
        group.setEndTime(OffsetDateTime.parse("2026-11-04T01:00:00+08:00").toInstant().toEpochMilli());
        group.setPeriodCount(2); group.setRequiredCount(4); group.setCompletedCount(3);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(PatrolTaskGroupExport.write(List.of(group))))) {
            var row = book.getSheetAt(0).getRow(1);
            assertEquals(CellType.STRING, row.getCell(0).getCellType());
            assertEquals("=1+1", row.getCell(0).getStringCellValue());
            assertEquals("原分类", row.getCell(1).getStringCellValue());
            assertEquals("2026-11-03", row.getCell(3).getStringCellValue());
            assertEquals("2026-11-03 23:00:00", row.getCell(4).getStringCellValue());
            assertEquals("2026-11-04 01:00:00", row.getCell(5).getStringCellValue());
            assertEquals(2, row.getCell(7).getNumericCellValue());
            assertEquals(3, row.getCell(8).getNumericCellValue());
            assertEquals(4, row.getCell(9).getNumericCellValue());
            assertEquals("", row.getCell(10).getStringCellValue());
        }
    }
}
