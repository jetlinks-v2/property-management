package org.jetlinks.pro.patrol.web;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetlinks.pro.patrol.entity.PatrolGenerationEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 日任务导出使用生成快照和真实时段进度；文本写为字符串，不能将名称解释为公式。 */
final class PatrolTaskGroupExport {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    static byte[] write(List<PatrolGenerationEntity> groups) throws IOException {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("点位巡更记录");
            var header = sheet.createRow(0);
            String[] labels = {"巡更点位", "点位分类", "点位等级", "归属日期", "开始时间", "结束时间",
                    "业务岗位", "时段数", "已巡次数", "应巡次数", "最后巡更时间"};
            for (int i = 0; i < labels.length; i++) {
                header.createCell(i).setCellValue(labels[i]);
                sheet.setColumnWidth(i, (i == 4 || i == 5 || i == 10 ? 24 : 20) * 256);
            }
            for (var group : groups) {
                var row = sheet.createRow(sheet.getPhysicalNumberOfRows());
                Object[] values = {group.getPointName(), group.getCategoryName(), group.getLevelName(),
                        group.getBelongDate(), time(group.getStartTime()), time(group.getEndTime()), group.getPostName(),
                        group.getPeriodCount(), group.getCompletedCount(), group.getRequiredCount(), time(group.getLastExecutionTime())};
                for (int i = 0; i < values.length; i++) {
                    var cell = row.createCell(i);
                    if (values[i] instanceof Number number) cell.setCellValue(number.doubleValue());
                    else cell.setCellValue(values[i] == null ? "" : values[i].toString());
                }
            }
            sheet.createFreezePane(0, 1);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static String time(Long value) {
        return value == null ? "" : TIME.format(Instant.ofEpochMilli(value));
    }
}
