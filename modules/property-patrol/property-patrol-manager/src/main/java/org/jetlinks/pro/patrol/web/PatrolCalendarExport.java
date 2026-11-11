package org.jetlinks.pro.patrol.web;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetlinks.pro.patrol.service.PatrolCalendarService;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** 与原导出一样分成人员排班、巡更任务两个工作表；单元格始终写文本，避免公式注入。 */
final class PatrolCalendarExport {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static byte[] write(PatrolCalendarService.Detail detail) throws IOException {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var staff = workbook.createSheet("人员排班");
            row(staff, "姓名", "手机号", "班次", "班次开始时间", "班次结束时间", "状态", "所属项目");
            for (var item : detail.staff()) row(staff,
                    item.getUserName(), detail.staffTelephones().get(item.getUserId()),
                    item.getShiftName() == null ? item.getName() : item.getShiftName(),
                    time(item.getStartTime()), time(item.getEndTime()), item.isRest() ? "休息" : "已排班", item.getProjectName());
            var tasks = workbook.createSheet("巡更任务");
            row(tasks, "任务组ID", "任务时段开始", "任务时段结束", "巡更点位", "点位分类", "点位等级",
                    "执行巡更角色", "应巡次数", "已巡次数", "最后巡更时间", "状态", "所属项目");
            for (var item : detail.tasks()) row(tasks, item.getTaskGroupId(), time(item.getStartTime()),
                    time(item.getEndTime()), item.getPointName(), item.getCategoryName(), item.getLevelName(),
                    item.getRoleId() != null && item.getRoleId().isBlank() ? "全员巡更" : item.getRoleName(),
                    String.valueOf(item.getRequiredCount()), String.valueOf(item.getCompletedCount()),
                    time(item.getLastExecutionTime()), status(PatrolCalendarService.status(item)), item.getProjectName());
            var summary = workbook.createSheet("点位日统计");
            row(summary, "归属日期", "应巡点位", "已完成点位", "漏检点位", "缺勤点位", "所属项目");
            Map<String, String> projects = new LinkedHashMap<>();
            detail.tasks().forEach(task -> projects.put(task.getProjectId(), task.getProjectName()));
            detail.staff().forEach(staffRow -> projects.putIfAbsent(staffRow.getProjectId(), staffRow.getProjectName()));
            summaryRow(summary, detail, projects.size() == 1 ? projects.values().iterator().next() : "全部项目汇总");
            if (projects.size() > 1) projects.forEach((id, name) -> summaryRow(summary, detail.forProject(id), name));
            for (Sheet sheet : workbook) {
                sheet.createFreezePane(0, 1);
                for (int i = 0; i < sheet.getRow(0).getLastCellNum(); i++) sheet.setColumnWidth(i, 22 * 256);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
    private static void summaryRow(Sheet sheet, PatrolCalendarService.Detail detail, String projectName) {
        row(sheet, detail.date(), String.valueOf(detail.pointCount()), String.valueOf(detail.completedPointCount()),
                String.valueOf(detail.missedPointCount()), String.valueOf(detail.absentPointCount()), projectName);
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
