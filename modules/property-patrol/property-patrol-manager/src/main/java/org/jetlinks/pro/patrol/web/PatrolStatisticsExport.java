package org.jetlinks.pro.patrol.web;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetlinks.pro.patrol.service.PatrolStatisticsService;
import org.jetlinks.pro.patrol.service.statistics.PatrolOverview;
import org.jetlinks.pro.patrol.service.statistics.PatrolOverviewAggregation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** 三类统计导出：与查询使用同一口径；名称写文本，计数写数值，不执行用户输入公式。 */
final class PatrolStatisticsExport {
    /** 首版工作台导出完整授权结果；人员只有实际打卡量，不摊分角色共享应巡量。 */
    static byte[] writeOverview(PatrolOverviewAggregation.Result result) throws IOException {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var report = result.report();
            var projects = workbook.createSheet("项目履行情况");
            var days = workbook.createSheet("每日履行情况");
            var points = workbook.createSheet("点位日任务");
            var people = workbook.createSheet("实际执行人员");
            header(projects, "所属项目"); header(days, "归属日期", "所属项目");
            counts(projects, "总计", report.total());
            for (var project : report.projects()) counts(projects, project.projectName(), project.counts());
            for (var projectDay : result.projectDays()) counts(days, projectDay.day().date(), projectDay.day().counts(), projectDay.projectName());
            row(points, "所属项目", "巡更点位", "归属日期", "分类", "点位等级", "任务组ID", "履行状态", "应巡次数",
                    "有效完成次数", "实际打卡次数", "审核补计次数", "到期未完成次数", "未到期剩余次数", "存在检查异常",
                    "已截止时段应巡次数", "已截止时段有效完成次数", "完成进度（%）", "已截止任务完成率（%）");
            for (var point : result.points()) {
                var c = point.counts();
                row(points, point.projectName(), point.pointName(), point.date(), String.join("、", point.categoryNames()),
                        String.join("、", point.levelNames()), String.join(",", point.groupIds()), state(point.state()),
                        c.expected(), c.completed(), c.executed(), c.credited(), c.overdue(), c.notDue(), c.abnormalPoints() > 0 ? "是" : "否",
                        c.dueExpected(), c.dueCompleted(), rate(c.progressRate()), rate(c.dueFulfillmentRate()));
            }
            row(people, "巡更人员", "实际打卡次数", "检查异常次数", "巡更点位数", "最近打卡时间", "所属项目");
            for (var projectPerson : result.projectPeople()) {
                var person = projectPerson.person();
                row(people, person.userName(), person.executionCount(), person.abnormalCount(), person.pointCount(),
                        java.time.Instant.ofEpochMilli(person.lastTime()).atZone(java.time.ZoneId.of("Asia/Shanghai"))
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), projectPerson.projectName());
            }
            var definitions = workbook.createSheet("统计口径");
            row(definitions, "统计周期", report.from() + " ～ " + report.to());
            row(definitions, "统计时间", java.time.Instant.ofEpochMilli(report.generatedAt()).toString());
            row(definitions, "分母", "仅包含已生成任务；业务归属日筛选，跨天时段不变更归属");
            row(definitions, "次数对账", "应巡=有效完成+到期未完成+未到期剩余；有效完成含审核补计，实际打卡与补计分列");
            row(definitions, "完成进度", "有效完成次数/全部应巡次数；无应巡次数显示—；总计按次数加权，不平均项目比率");
            row(definitions, "已截止任务完成率", "已截止时段有效完成次数/已截止时段应巡次数；未截止时段不参与，无已截止应巡次数显示—；有效完成含审核补计，不代表按时打卡率");
            row(definitions, "点位日任务", "同一项目、点位、归属日统计一次，所有角色和时段完成才算完成");
            row(definitions, "漏检/缺勤", "漏检=有有效完成且存在到期未完成；缺勤=所有时段已截止且无有效完成，两者互斥");
            row(definitions, "检查异常", "真实打卡结果不合格，与巡更履行完成独立");
            for (var sheet : workbook) {
                sheet.createFreezePane(0, 1);
                for (int i = 0; i < sheet.getRow(0).getLastCellNum(); i++) sheet.setColumnWidth(i, 22 * 256);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
    private static void header(Sheet sheet, String dimension, String... extra) {
        row(sheet, dimension, "应巡次数", "有效完成次数", "实际打卡次数", "审核补计次数", "到期未完成次数", "未到期剩余次数",
                "应巡点位日任务", "已完成点位日任务", "漏检点位日任务", "缺勤点位日任务", "未开始点位日任务", "进行中点位日任务", "检查异常点位日任务",
                "已截止时段应巡次数", "已截止时段有效完成次数", "完成进度（%）", "已截止任务完成率（%）");
        append(sheet, (Object[]) extra);
    }
    private static void counts(Sheet sheet, String name, PatrolOverview.Counts c, String... extra) {
        row(sheet, name, c.expected(), c.completed(), c.executed(), c.credited(), c.overdue(), c.notDue(), c.pointDays(),
                c.completedPoints(), c.missedPoints(), c.absentPoints(), c.pendingPoints(), c.ongoingPoints(), c.abnormalPoints(),
                c.dueExpected(), c.dueCompleted(), rate(c.progressRate()), rate(c.dueFulfillmentRate()));
        append(sheet, (Object[]) extra);
    }
    private static Object rate(Double rate) { return rate == null ? "—" : rate; }
    private static String state(String state) {
        return switch (state) {
            case "completed" -> "已完成";
            case "missed" -> "漏检";
            case "absent" -> "缺勤";
            case "pending" -> "未开始";
            default -> "进行中";
        };
    }
    static byte[] write(PatrolStatisticsService.Report report, boolean people, boolean qualification) throws IOException {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(people ? "巡更角色巡更覆盖率" : qualification ? "点位合格率" : "点位覆盖率");
            row(sheet, people ? "执行巡更角色" : "巡更点位", "统计周期", "点位数",
                    qualification ? "任务时段数" : "应巡次数",
                    qualification ? "已完成时段数" : "有效已巡次数",
                    qualification ? "未完成时段数" : "未完成次数",
                    qualification ? "点位合格率（%）" : "覆盖率（%）", "所属项目");
            String period = report.from() + " ～ " + report.to();
            data(sheet, report.total(), "总计", period, qualification, "全部项目汇总");
            for (var item : report.rows()) data(sheet, item,
                    people && "".equals(item.id()) ? "全员巡更" : item.name(), period, qualification, item.projectName());
            sheet.createFreezePane(0, 2);
            sheet.setColumnWidth(0, 28 * 256);
            sheet.setColumnWidth(1, 32 * 256);
            for (int i = 2; i < 8; i++) sheet.setColumnWidth(i, 22 * 256);
            workbook.write(out);
            return out.toByteArray();
        }
    }
    private static void data(Sheet sheet, PatrolStatisticsService.Row item, String name, String period, boolean qualification, String projectName) {
        row(sheet, name, period, item.pointCount(),
                qualification ? item.taskCount() : item.expected(),
                qualification ? item.completedTasks() : item.completed(),
                qualification ? item.taskCount() - item.completedTasks() : item.missing(),
                qualification ? item.qualification() : item.coverage(), projectName);
    }
    private static void append(Sheet sheet, Object... values) {
        var last = sheet.getRow(sheet.getLastRowNum());
        int offset = last.getLastCellNum();
        for (int i = 0; i < values.length; i++) last.createCell(offset + i).setCellValue(values[i] == null ? "" : values[i].toString());
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
