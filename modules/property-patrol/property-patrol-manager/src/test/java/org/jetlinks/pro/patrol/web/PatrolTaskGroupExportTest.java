package org.jetlinks.pro.patrol.web;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetlinks.pro.patrol.entity.*;
import org.jetlinks.pro.patrol.service.PatrolExportImages;
import org.jetlinks.pro.patrol.service.PatrolRecordExportService.Batch;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PatrolTaskGroupExportTest {
    @Test
    void threeSheetsLinkDailyTasksExecutionsAndHistoricalAnswersWithEmbeddedImages() throws Exception {
        var group = group("group", 3, 2);
        var task = task("period", "group");
        var execution = execution("execution", "group", "period", 1793720000000L);
        execution.setNetworkStatus(PatrolExecutionEntity.NetworkStatus.offline); execution.setRemark("=原始备注");
        execution.setStandardSnapshot(List.of(
                new PatrolStandardEntity.CheckItem("lock", "当时的锁检查", true, List.of("正常", "异常")),
                new PatrolStandardEntity.CheckItem("optional", "未填选填项", false, List.of())));
        execution.setAnswers(List.of(new PatrolExecutionEntity.Answer("lock", "=1+1", "evidence"),
                new PatrolExecutionEntity.Answer("legacy", "旧答案")));
        execution.setItemResults(List.of(new PatrolExecutionEntity.ItemResult("lock", PatrolExecutionEntity.CheckResult.pass)));
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(50, 100, BufferedImage.TYPE_INT_RGB), "png", output);
        byte[] bytes;
        try (var writer = new PatrolTaskGroupExport()) {
            assertEquals(java.util.Set.of("evidence"), writer.missingImages(new Batch(List.of(group), List.of(task), List.of(execution))));
            writer.addImage("evidence", new PatrolExportImages.Image(output.toByteArray(), "", 50, 100));
            writer.append(new Batch(List.of(group), List.of(task), List.of(execution))); bytes = writer.finish();
        }
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals(3, book.getNumberOfSheets()); assertNull(book.getSheet("巡更时段")); assertNull(book.getSheet("漏检申报"));
            assertEquals("所属项目", book.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals("日任务编号", book.getSheetAt(0).getRow(0).getCell(9).getStringCellValue());
            assertEquals("group", book.getSheetAt(0).getRow(1).getCell(9).getStringCellValue());
            var detail = book.getSheet("执行记录").getRow(1);
            assertEquals("原项目", detail.getCell(0).getStringCellValue());
            assertEquals("2026-11-03 23:00:00 ～ 2026-11-04 01:00:00", detail.getCell(5).getStringCellValue());
            assertEquals("group", detail.getCell(7).getStringCellValue()); assertEquals("execution", detail.getCell(8).getStringCellValue());
            assertEquals("离线", detail.getCell(11).getStringCellValue()); assertEquals("=原始备注", detail.getCell(12).getStringCellValue());
            assertEquals("巡更时间", book.getSheet("执行记录").getRow(0).getCell(10).getStringCellValue());
            var check = book.getSheet("检查项结果").getRow(1);
            assertEquals(3, book.getSheet("检查项结果").getLastRowNum());
            assertEquals("execution", check.getCell(8).getStringCellValue()); assertEquals("当时的锁检查", check.getCell(11).getStringCellValue());
            assertEquals("=1+1", check.getCell(13).getStringCellValue()); assertEquals(CellType.STRING, check.getCell(13).getCellType());
            assertEquals("异常", check.getCell(14).getStringCellValue()); assertEquals("正常", check.getCell(18).getStringCellValue());
            assertEquals("", check.getCell(20).getStringCellValue()); // 导出图片，不暴露附件编号。
            assertEquals(1, book.getAllPictures().size());
            var picture = (org.apache.poi.xssf.usermodel.XSSFPicture) book.getSheet("检查项结果").getDrawingPatriarch().getShapes().get(0);
            var anchor = picture.getClientAnchor();
            assertEquals(20, anchor.getCol1()); assertEquals(1, anchor.getRow1());
            assertEquals(2, (anchor.getDy2() - anchor.getDy1()) / (anchor.getDx2() - anchor.getDx1()));
            assertEquals("", book.getSheet("检查项结果").getRow(2).getCell(14).getStringCellValue());
            assertEquals("旧答案", book.getSheet("检查项结果").getRow(3).getCell(13).getStringCellValue());
        }
    }

    @Test
    void mergesOnlyDailyTaskCommonColumnsAndKeepsEveryExecutionAcrossStreamingWindow() throws Exception {
        var group = group("day-role-1", 200, 150); var other = group("day-role-2", 1, 1);
        var task = task("period", group.getId());
        var facts = new ArrayList<PatrolExecutionEntity>();
        for (int i = 149; i >= 0; i--) facts.add(execution("e" + i, group.getId(), task.getId(), 1793720000000L + i));
        facts.add(execution("other", other.getId(), task.getId(), 1793720000000L));
        byte[] bytes;
        try (var writer = new PatrolTaskGroupExport()) {
            writer.append(new Batch(List.of(group, other), List.of(task), facts)); bytes = writer.finish();
        }
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = book.getSheet("执行记录"); assertEquals(151, sheet.getLastRowNum());
            assertEquals(7, sheet.getNumMergedRegions());
            assertTrue(sheet.getMergedRegions().stream().allMatch(region -> region.getFirstRow() == 1 && region.getLastRow() == 150));
            assertFalse(sheet.getMergedRegions().stream().anyMatch(region -> region.getFirstColumn() == 5 || region.getFirstColumn() == 8));
            assertEquals("e0", sheet.getRow(1).getCell(8).getStringCellValue()); assertEquals("e149", sheet.getRow(150).getCell(8).getStringCellValue());
            assertEquals("other", sheet.getRow(151).getCell(8).getStringCellValue());
        }
    }

    @Test
    void taskStatusUsesWholeDailyTaskDeadlineAndLiteralNamesRemainText() throws Exception {
        var missing = group("missing", 4, 3); missing.setPointName("=1+1");
        var absent = group("absent", 4, 0); var complete = group("complete", 4, 4);
        var future = group("future", 4, 0); future.setStartTime(Long.MAX_VALUE - 1); future.setEndTime(Long.MAX_VALUE);
        var active = group("active", 4, 0); active.setStartTime(0L); active.setEndTime(Long.MAX_VALUE);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(PatrolTaskGroupExport.write(List.of(missing, absent, complete, future, active))))) {
            var sheet = book.getSheetAt(0);
            assertEquals(CellType.STRING, sheet.getRow(1).getCell(1).getCellType());
            assertEquals("=1+1", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals(3, sheet.getRow(1).getCell(6).getNumericCellValue()); assertEquals(4, sheet.getRow(1).getCell(7).getNumericCellValue());
            assertEquals(List.of("漏检", "缺勤", "已完成", "未开始", "进行中"),
                    java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(i -> sheet.getRow(i).getCell(8).getStringCellValue()).toList());
        }
    }

    @Test
    void inaccessiblePictureShowsReasonInsteadOfAttachmentId() throws Exception {
        var fact = execution("e", "g", "p", 1L);
        fact.setAnswers(List.of(new PatrolExecutionEntity.Answer("item", "", "hidden")));
        byte[] bytes;
        try (var writer = new PatrolTaskGroupExport()) {
            writer.addImage("hidden", new PatrolExportImages.Image(new byte[0], "无图片查看权限"));
            writer.append(new Batch(List.of(group("g", 1, 1)), List.of(task("p", "g")), List.of(fact))); bytes = writer.finish();
        }
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals("无图片查看权限", book.getSheet("检查项结果").getRow(1).getCell(20).getStringCellValue());
            assertTrue(book.getAllPictures().isEmpty());
        }
    }
    private PatrolGenerationEntity group(String id, int required, int completed) {
        var row = new PatrolGenerationEntity(); row.setId(id); row.setRoleId("guard"); row.setRoleName("巡更员");
        row.setProjectName("原项目"); row.setPointName("原点位"); row.setBelongDate("2026-11-03");
        row.setStartTime(1L); row.setEndTime(2L); row.setRequiredCount(required); row.setCompletedCount(completed); return row;
    }
    private PatrolTaskEntity task(String id, String group) {
        var row = new PatrolTaskEntity(); row.setId(id); row.setTaskGroupId(group); row.setPeriodNo(2);
        row.setStartTime(OffsetDateTime.parse("2026-11-03T23:00:00+08:00").toInstant().toEpochMilli());
        row.setEndTime(OffsetDateTime.parse("2026-11-04T01:00:00+08:00").toInstant().toEpochMilli()); return row;
    }
    private PatrolExecutionEntity execution(String id, String group, String task, long received) {
        var row = new PatrolExecutionEntity(); row.setId(id); row.setTaskGroupId(group); row.setTaskId(task);
        row.setUserName("张三"); row.setReceiveTime(received); return row;
    }
}
