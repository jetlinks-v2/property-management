package org.jetlinks.pro.patrol.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.patrol.entity.PatrolPostEntity;
import org.jetlinks.pro.patrol.entity.PatrolRosterEntity;
import java.io.*;
import java.time.*;
import java.util.*;

/** 月排班交换格式：四行表头、第五行示例、第六行起数据；班次由同一工作簿明确给出。 */
public final class PatrolRosterWorkbook {
    public static final int MAX_ROWS = 1000;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private PatrolRosterWorkbook() {}

    public static byte[] template(YearMonth month, List<PatrolPostEntity> posts, Map<String, String> names) throws IOException {
        try (var book = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("排班表");
            sheet.createRow(0).createCell(0).setCellValue(month.toString());
            String[] fields = {"岗位ID", "岗位", "人员ID", "姓名"};
            var header = sheet.createRow(1);
            for (int i = 0; i < fields.length; i++) header.createCell(i).setCellValue(fields[i]);
            var dates = sheet.createRow(2);
            var weeks = sheet.createRow(3);
            for (int d = 1; d <= month.lengthOfMonth(); d++) {
                header.createCell(d + 3).setCellValue(month.getMonthValue() + "月");
                dates.createCell(d + 3).setCellValue(d);
                weeks.createCell(d + 3).setCellValue("周" + "一二三四五六日".charAt(month.atDay(d).getDayOfWeek().getValue() - 1));
            }
            sheet.createRow(4).createCell(0).setCellValue("示例行（不导入）；请在班次表填写班次，再从第6行填写每日班次或休息");
            int index = 5;
            for (var post : posts) for (String userId : post.getUserIds()) {
                if (index >= MAX_ROWS + 5) throw invalid();
                var row = sheet.createRow(index++);
                row.createCell(0).setCellValue(post.getId());
                row.createCell(1).setCellValue(post.getName());
                row.createCell(2).setCellValue(userId);
                row.createCell(3).setCellValue(names.getOrDefault(userId, userId));
            }
            for (int i = 0; i < 4; i++) sheet.setColumnWidth(i, (i % 2 == 0 ? 38 : 20) * 256);
            for (int d = 4; d < month.lengthOfMonth() + 4; d++) sheet.setColumnWidth(d, 12 * 256);
            sheet.createFreezePane(4, 5);
            var shifts = book.createSheet("班次");
            var titles = shifts.createRow(0);
            titles.createCell(0).setCellValue("班次名称（休息无需配置）");
            titles.createCell(1).setCellValue("开始时间 HH:mm");
            titles.createCell(2).setCellValue("结束时间 HH:mm（小于开始表示跨天）");
            for (int i = 0; i < 3; i++) shifts.setColumnWidth(i, 42 * 256);
            book.write(bytes);
            return bytes.toByteArray();
        }
    }

    public static List<PatrolRosterEntity> read(byte[] bytes, YearMonth month) throws IOException {
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = book.getSheet("排班表");
            var shiftSheet = book.getSheet("班次");
            if (sheet == null || shiftSheet == null || !month.toString().equals(cell(sheet.getRow(0), 0))
                    || sheet.getLastRowNum() > MAX_ROWS + 4 || shiftSheet.getLastRowNum() > 100) throw invalid();
            Map<String, LocalTime[]> shifts = new HashMap<>();
            for (int i = 1; i <= shiftSheet.getLastRowNum(); i++) {
                var row = shiftSheet.getRow(i);
                String name = cell(row, 0);
                if (name.isEmpty()) continue;
                if (name.length() > 128 || name.equals("休息") || shifts.containsKey(name)) throw invalid();
                try {
                    var start = LocalTime.parse(cell(row, 1));
                    var end = LocalTime.parse(cell(row, 2));
                    if (start.equals(end)) throw invalid();
                    shifts.put(name, new LocalTime[]{start, end});
                } catch (java.time.format.DateTimeParseException error) { throw invalid(); }
            }
            List<PatrolRosterEntity> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 5; i <= sheet.getLastRowNum(); i++) {
                var row = sheet.getRow(i);
                String post = cell(row, 0), user = cell(row, 2);
                for (int d = 1; d <= month.lengthOfMonth(); d++) {
                    String shift = cell(row, d + 3);
                    if (shift.isEmpty()) continue;
                    if (post.isBlank() || user.isBlank() || !seen.add(post + "|" + user + "|" + d)) throw invalid();
                    boolean rest = "休息".equals(shift);
                    var time = shifts.get(shift);
                    if (!rest && time == null) throw invalid();
                    var date = month.atDay(d);
                    var start = date.atTime(rest ? LocalTime.MIDNIGHT : time[0]);
                    var end = rest ? start.plusDays(1) : date.atTime(time[1]);
                    if (!end.isAfter(start)) end = end.plusDays(1);
                    var item = new PatrolRosterEntity();
                    item.setPostId(post); item.setUserId(user); item.setBusinessDate(date);
                    item.setName(shift); item.setShiftName(shift); item.setRest(rest);
                    item.setStartTime(start.atZone(ZONE).toInstant().toEpochMilli());
                    item.setEndTime(end.atZone(ZONE).toInstant().toEpochMilli());
                    result.add(item);
                }
                // 不允许把月底外的排班误当有效数据。
                if (row != null) for (int col = month.lengthOfMonth() + 4; col < row.getLastCellNum(); col++)
                    if (!cell(row, col).isEmpty()) throw invalid();
            }
            return result;
        }
    }

    private static String cell(Row row, int column) {
        Cell cell = row == null ? null : row.getCell(column);
        if (cell == null) return "";
        if (cell.getCellType() == CellType.FORMULA) throw invalid();
        return new DataFormatter(Locale.ROOT).formatCellValue(cell).trim();
    }
    static BusinessException invalid() { return new BusinessException("error.patrol.roster.workbook"); }
}
