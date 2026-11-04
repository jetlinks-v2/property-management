package org.jetlinks.pro.patrol.web;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetlinks.pro.patrol.service.PatrolStatisticsService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** 三类统计导出：与查询使用同一口径；名称写文本，计数写数值，不执行用户输入公式。 */
final class PatrolStatisticsExport {
    static byte[] write(PatrolStatisticsService.Report report, boolean people, boolean qualification) throws IOException {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(people ? "岗位巡更覆盖率" : qualification ? "点位合格率" : "点位覆盖率");
            row(sheet, people ? "执行岗位" : "巡更点位", "统计周期", "点位数",
                    qualification ? "任务时段数" : "应巡次数",
                    qualification ? "已完成时段数" : "有效已巡次数",
                    qualification ? "未完成时段数" : "未完成次数",
                    qualification ? "点位合格率（%）" : "覆盖率（%）");
            String period = report.from() + " ～ " + report.to();
            data(sheet, report.total(), "总计", period, qualification);
            for (var item : report.rows()) data(sheet, item, item.name(), period, qualification);
            sheet.createFreezePane(0, 2);
            sheet.setColumnWidth(0, 28 * 256);
            sheet.setColumnWidth(1, 32 * 256);
            for (int i = 2; i < 7; i++) sheet.setColumnWidth(i, 22 * 256);
            workbook.write(out);
            return out.toByteArray();
        }
    }
    private static void data(Sheet sheet, PatrolStatisticsService.Row item, String name, String period, boolean qualification) {
        row(sheet, name, period, item.pointCount(),
                qualification ? item.taskCount() : item.expected(),
                qualification ? item.completedTasks() : item.completed(),
                qualification ? item.taskCount() - item.completedTasks() : item.missing(),
                qualification ? item.qualification() : item.coverage());
    }
    private static void row(Sheet sheet, Object... values) {
        var row = sheet.createRow(sheet.getPhysicalNumberOfRows());
        for (int i = 0; i < values.length; i++) {
            var cell = row.createCell(i);
            if (values[i] instanceof Number number) cell.setCellValue(number.doubleValue());
            else cell.setCellValue(values[i] == null ? "" : values[i].toString());
        }
    }
}
