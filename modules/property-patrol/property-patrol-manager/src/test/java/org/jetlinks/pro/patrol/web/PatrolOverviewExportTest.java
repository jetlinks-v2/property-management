package org.jetlinks.pro.patrol.web;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity;
import org.jetlinks.pro.patrol.service.statistics.PatrolOverview;
import org.jetlinks.pro.patrol.service.statistics.PatrolOverviewAggregation;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PatrolOverviewExportTest {
    @Test void exportsSameBusinessDateCountsGroupsAndTextWithoutFormulaExecution() throws Exception {
        var task = new PatrolTaskEntity(); task.setId("t"); task.setProjectId("p"); task.setProjectName("=HYPERLINK(\"bad\")");
        task.setPointId("point"); task.setPointName("Gate"); task.setBelongDate("2026-11-05"); task.setTaskGroupId("stable-group");
        task.setStartTime(1L); task.setEndTime(2L); task.setRequiredCount(2); task.setCompletedCount(1);
        var range = new PatrolOverview.Range(LocalDate.of(2026,11,5),LocalDate.of(2026,11,5),List.of(),List.of(),null);
        var result = PatrolOverviewAggregation.aggregate(range, List.of(task), List.of(), 3L);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(PatrolStatisticsExport.writeOverview(result)))) {
            assertEquals(5,workbook.getNumberOfSheets());
            var project = workbook.getSheet("项目履行情况").getRow(2);
            assertEquals(CellType.STRING,project.getCell(0).getCellType());
            assertEquals(task.getProjectName(),project.getCell(0).getStringCellValue());
            assertEquals(2,project.getCell(1).getNumericCellValue()); assertEquals(1,project.getCell(4).getNumericCellValue());
            assertEquals(2,project.getCell(14).getNumericCellValue());
            assertEquals(1,project.getCell(15).getNumericCellValue());
            assertEquals(50,project.getCell(16).getNumericCellValue());
            assertEquals(50,project.getCell(17).getNumericCellValue());
            var detail = workbook.getSheet("点位日任务").getRow(1);
            assertEquals("stable-group",detail.getCell(5).getStringCellValue());
            assertEquals("漏检",detail.getCell(6).getStringCellValue());
            assertEquals(1,detail.getCell(11).getNumericCellValue());
            assertEquals(1,workbook.getSheet("实际执行人员").getPhysicalNumberOfRows());
        }
    }
    @Test void daysAndActualPersonnelAreSplitByProjectWithoutDuplicatingTotals() throws Exception {
        var tasks = new java.util.ArrayList<PatrolTaskEntity>();
        var executions = new java.util.ArrayList<PatrolExecutionEntity>();
        for (int i = 1; i <= 2; i++) {
            var task = new PatrolTaskEntity(); task.setId("t" + i); task.setProjectId("p" + i); task.setProjectName("项目" + i);
            task.setPointId("point"); task.setPointName("Gate"); task.setBelongDate("2026-11-10");
            task.setRequiredCount(i); task.setCompletedCount(1); task.setStartTime(1L); task.setEndTime(2L); tasks.add(task);
            var execution = new PatrolExecutionEntity(); execution.setId("e" + i); execution.setTaskId(task.getId());
            execution.setProjectId(task.getProjectId()); execution.setPointId("point"); execution.setUserId("same-user");
            execution.setUserName("同一员工"); execution.setReceiveTime(2L); executions.add(execution);
        }
        var range = new PatrolOverview.Range(LocalDate.of(2026,11,10), LocalDate.of(2026,11,10), List.of(), List.of(), null);
        var result = PatrolOverviewAggregation.aggregate(range, tasks, executions, 3L);
        assertEquals(2, result.report().people().get(0).executionCount());
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(PatrolStatisticsExport.writeOverview(result)))) {
            var days = book.getSheet("每日履行情况");
            var people = book.getSheet("实际执行人员");
            assertEquals("所属项目", days.getRow(0).getCell(18).getStringCellValue());
            assertEquals(3, days.getPhysicalNumberOfRows());
            assertEquals("项目1", days.getRow(1).getCell(18).getStringCellValue());
            assertEquals(1, days.getRow(1).getCell(1).getNumericCellValue());
            assertEquals("项目2", days.getRow(2).getCell(18).getStringCellValue());
            assertEquals(2, days.getRow(2).getCell(1).getNumericCellValue());
            assertEquals(3, people.getPhysicalNumberOfRows());
            assertEquals("项目1", people.getRow(1).getCell(5).getStringCellValue());
            assertEquals("项目2", people.getRow(2).getCell(5).getStringCellValue());
            assertEquals(1, people.getRow(1).getCell(1).getNumericCellValue());
            assertEquals(1, people.getRow(2).getCell(1).getNumericCellValue());
        }
    }

}
