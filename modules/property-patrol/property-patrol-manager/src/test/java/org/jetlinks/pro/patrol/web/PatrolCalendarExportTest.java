package org.jetlinks.pro.patrol.web;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.jetlinks.pro.patrol.entity.PatrolRosterEntity;
import org.jetlinks.pro.patrol.service.PatrolCalendarService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PatrolCalendarExportTest {
    @Test
    void taskSheetUsesHistoricalCategoryNameAndKeepsUnknownNamesBlank() throws Exception {
        var task = new PatrolTaskEntity();
        task.setId("night-period"); task.setTaskGroupId("daily-group");
        task.setPointName("=1+1"); task.setCategoryId("internal-category-id");
        task.setCategoryName("生成时分类"); task.setLevelName("生成时等级"); task.setPostName("夜班");
        task.setStartTime(OffsetDateTime.parse("2026-11-03T23:00:00+08:00").toInstant().toEpochMilli());
        task.setEndTime(OffsetDateTime.parse("2026-11-04T01:00:00+08:00").toInstant().toEpochMilli());
        task.setRequiredCount(4); task.setCompletedCount(3);
        var legacy = new PatrolTaskEntity();
        legacy.setId("legacy-period"); legacy.setCategoryId("unrecorded-name-id");
        legacy.setStartTime(task.getStartTime()); legacy.setEndTime(task.getEndTime());
        legacy.setRequiredCount(1);
        var detail = new PatrolCalendarService.Detail("2026-11-04", 0, 0, 2, 5, 3, 2,
                List.of(), List.of(task, legacy), java.util.Map.of());
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(PatrolCalendarExport.write(detail)))) {
            var sheet = book.getSheet("巡更任务");
            assertEquals("点位分类", sheet.getRow(0).getCell(4).getStringCellValue());
            var row = sheet.getRow(1);
            assertEquals("生成时分类", row.getCell(4).getStringCellValue());
            assertEquals("生成时等级", row.getCell(5).getStringCellValue());
            assertEquals("2026-11-03 23:00", row.getCell(1).getStringCellValue());
            assertEquals("2026-11-04 01:00", row.getCell(2).getStringCellValue());
            assertEquals(CellType.STRING, row.getCell(3).getCellType());
            assertEquals("=1+1", row.getCell(3).getStringCellValue());
            assertEquals("4", row.getCell(7).getStringCellValue());
            assertEquals("3", row.getCell(8).getStringCellValue());
            assertEquals("", sheet.getRow(2).getCell(4).getStringCellValue());
        }
    }

    @Test
    void staffSheetIncludesOnlyResolvedPhonesAndDoesNotUseInternalIdsAsNames() throws Exception {
        var alice = new PatrolRosterEntity();
        alice.setUserId("alice-id"); alice.setUserName("Alice"); alice.setShiftName("Day");
        var unknown = new PatrolRosterEntity(); unknown.setUserId("unreadable-id"); unknown.setRest(true);
        var detail = new PatrolCalendarService.Detail("2026-11-04", 1, 1, 0, 0, 0, 0,
                List.of(alice, unknown), List.of(), java.util.Map.of("alice-id", "13800000001"));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(PatrolCalendarExport.write(detail)))) {
            var sheet = book.getSheet("人员排班");
            assertEquals("手机号", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("Alice", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("13800000001", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("Day", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("", sheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals("", sheet.getRow(2).getCell(1).getStringCellValue());
            assertEquals("休息", sheet.getRow(2).getCell(5).getStringCellValue());
        }
    }
}
