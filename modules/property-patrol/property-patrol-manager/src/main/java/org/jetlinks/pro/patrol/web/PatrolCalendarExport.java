package org.jetlinks.pro.patrol.web;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetlinks.pro.patrol.service.PatrolCalendarService;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

/** 与原导出一样分成人员排班、巡更任务两个工作表；单元格始终写文本，避免公式注入。 */
final class PatrolCalendarExport {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static byte[] write(PatrolCalendarService.Detail detail) throws IOException {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var staff = workbook.createSheet("人员排班");
            row(staff, "姓名", "手机号", "班次", "班次开始时间", "班次结束时间", "状态");
            for (var item : detail.staff()) row(staff,
                    item.getUserName(), detail.staffTelephones().get(item.getUserId()),
                    item.getShiftName() == null ? item.getName() : item.getShiftName(),
                    time(item.getStartTime()), time(item.getEndTime()), item.isRest() ? "休息" : "已排班");
            var tasks = workbook.createSheet("巡更任务");
            row(tasks, "任务组ID", "任务时段开始", "任务时段结束", "巡更点位", "点位分类", "点位等级",
                    "执行岗位角色", "应巡次数", "已巡次数", "最后巡更时间", "状态");
            for (var item : detail.tasks()) row(tasks, item.getTaskGroupId(), time(item.getStartTime()),
                    time(item.getEndTime()), item.getPointName(), item.getCategoryName(), item.getLevelName(), item.getPostName(),
                    String.valueOf(item.getRequiredCount()), String.valueOf(item.getCompletedCount()),
                    time(item.getLastExecutionTime()), status(PatrolCalendarService.status(item)));
            for (Sheet sheet : workbook) {
                sheet.createFreezePane(0, 1);
                for (int i = 0; i < sheet.getRow(0).getLastCellNum(); i++) sheet.setColumnWidth(i, 22 * 256);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
    private static void row(Sheet sheet, String... values) {
        var row = sheet.createRow(sheet.getPhysicalNumberOfRows());
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i] == null ? "" : values[i]);
    }
    private static String time(Long value) {
        return value == null ? "" : Instant.ofEpochMilli(value).atZone(ZoneId.of("Asia/Shanghai")).format(FORMAT);
    }
    private static String status(String value) {
        return switch (value) {
            case "completed" -> "已完成"; case "abnormal" -> "已漏检";
            case "pending" -> "待开始"; case "ongoing" -> "进行中"; default -> "暂无任务";
        };
    }
}
