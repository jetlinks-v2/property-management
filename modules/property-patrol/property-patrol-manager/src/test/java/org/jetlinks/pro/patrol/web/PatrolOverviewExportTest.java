package org.jetlinks.pro.patrol.web;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
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
}
