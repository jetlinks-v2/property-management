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
        task.setProjectId("p1"); task.setProjectName("项目一"); task.setId("night-period"); task.setTaskGroupId("daily-group");
        task.setPointName("=1+1"); task.setCategoryId("internal-category-id");
        task.setCategoryName("生成时分类"); task.setLevelName("生成时等级"); task.setRoleName("夜班");
        task.setStartTime(OffsetDateTime.parse("2026-11-03T23:00:00+08:00").toInstant().toEpochMilli());
        task.setEndTime(OffsetDateTime.parse("2026-11-04T01:00:00+08:00").toInstant().toEpochMilli());
        task.setRequiredCount(4); task.setCompletedCount(3);
        var legacy = new PatrolTaskEntity();
        legacy.setId("legacy-period"); legacy.setCategoryId("unrecorded-name-id");
        legacy.setStartTime(task.getStartTime()); legacy.setEndTime(task.getEndTime());
        legacy.setRequiredCount(1);
        var detail = new PatrolCalendarService.Detail("2026-11-04", 0, 0, 2, 5, 3, 2,
                List.of(), List.of(task, legacy), java.util.Map.of(), 0, 1, 1);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(PatrolCalendarExport.write(detail)))) {
            var sheet = book.getSheet("巡更任务");
            assertEquals("项目一", sheet.getRow(1).getCell(11).getStringCellValue());
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
            var summary = book.getSheet("点位日统计");
            assertEquals("漏检点位", summary.getRow(0).getCell(3).getStringCellValue());
            assertEquals("缺勤点位", summary.getRow(0).getCell(4).getStringCellValue());
            assertEquals("1", summary.getRow(1).getCell(3).getStringCellValue());
            assertEquals("1", summary.getRow(1).getCell(4).getStringCellValue());
        }
    }

    @Test
    void summarySeparatesSamePointAcrossProjectsAndKeepsAllRolesRequired() throws Exception {
        var first = new PatrolTaskEntity(); first.setProjectId("a"); first.setProjectName("项目甲");
        first.setPointId("point"); first.setRequiredCount(2); first.setCompletedCount(2);
        first.setStartTime(1L); first.setEndTime(2L);
        var secondRole = new PatrolTaskEntity(); secondRole.setProjectId("a"); secondRole.setProjectName("项目甲");
        secondRole.setPointId("point"); secondRole.setRequiredCount(1); secondRole.setStartTime(1L); secondRole.setEndTime(2L);
        var other = new PatrolTaskEntity(); other.setProjectId("b"); other.setProjectName("项目乙");
        other.setPointId("point"); other.setRequiredCount(1); other.setStartTime(1L); other.setEndTime(2L);
        var detail = new PatrolCalendarService.Detail("2026-11-10", 0, 0, 2, 4, 2, 2,
                List.of(), List.of(first, secondRole, other), java.util.Map.of(), 0, 1, 1);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(PatrolCalendarExport.write(detail)))) {
            var sheet = book.getSheet("点位日统计");
            assertEquals("全部项目汇总", sheet.getRow(1).getCell(5).getStringCellValue());
            assertEquals("项目甲", sheet.getRow(2).getCell(5).getStringCellValue());
            assertEquals("1", sheet.getRow(2).getCell(1).getStringCellValue());
            assertEquals("0", sheet.getRow(2).getCell(2).getStringCellValue());
            assertEquals("1", sheet.getRow(2).getCell(3).getStringCellValue());
            assertEquals("项目乙", sheet.getRow(3).getCell(5).getStringCellValue());
            assertEquals("1", sheet.getRow(3).getCell(4).getStringCellValue());
        }
    }

    @Test
    void staffSheetIncludesOnlyResolvedPhonesAndDoesNotUseInternalIdsAsNames() throws Exception {
        var alice = new PatrolRosterEntity();
        alice.setProjectId("p1"); alice.setProjectName("项目一"); alice.setUserId("alice-id"); alice.setUserName("Alice"); alice.setShiftName("Day");
        var unknown = new PatrolRosterEntity(); unknown.setUserId("unreadable-id"); unknown.setRest(true);
        var detail = new PatrolCalendarService.Detail("2026-11-04", 1, 1, 0, 0, 0, 0,
                List.of(alice, unknown), List.of(), java.util.Map.of("alice-id", "13800000001"), 0, 0, 0);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(PatrolCalendarExport.write(detail)))) {
            var sheet = book.getSheet("人员排班");
            assertEquals("项目一", sheet.getRow(1).getCell(6).getStringCellValue());
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
