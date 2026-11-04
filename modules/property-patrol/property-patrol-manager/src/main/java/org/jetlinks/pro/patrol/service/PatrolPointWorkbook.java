package org.jetlinks.pro.patrol.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.patrol.entity.PatrolPointEntity;

import java.io.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 沿用老巡更四列点位模板；文件解析与生成由调用方调度到 boundedElastic。 */
public final class PatrolPointWorkbook {
    public static final int MAX_ROWS = 1000;
    static final String[] HEADERS = {"分类id", "分类名称", "点位名称", "所属位置"};
    private PatrolPointWorkbook() {}

    /** 错误码由 PC 国际化；行号保持 Excel 中的真实行号，空行不改变定位。 */
    public record ImportRow(int rowNumber, String categoryId, String categoryName, String name,
                            String location, List<String> errors) {}
    public record Preview(List<ImportRow> rows) {
        public boolean valid() { return !rows.isEmpty() && rows.stream().allMatch(row -> row.errors().isEmpty()); }
    }

    public static byte[] template(Map<String, String> categories) throws IOException {
        try (var book = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("点位导入");
            header(sheet, HEADERS);
            var reference = book.createSheet("分类参考");
            header(reference, new String[]{"分类id", "分类名称"});
            int index = 1;
            for (var entry : categories.entrySet()) {
                var row = reference.createRow(index++);
                row.createCell(0).setCellValue(entry.getKey()); row.createCell(1).setCellValue(entry.getValue());
            }
            book.write(bytes); return bytes.toByteArray();
        }
    }

    public static List<ImportRow> read(byte[] bytes) throws IOException {
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = book.getSheet("点位导入");
            if (sheet == null) sheet = book.getNumberOfSheets() == 0 ? null : book.getSheetAt(0);
            if (sheet == null || sheet.getRow(0) == null || sheet.getLastRowNum() > 10000) throw invalid();
            Map<String, Integer> columns = new HashMap<>();
            for (var cell : sheet.getRow(0)) columns.put(normalize(text(cell)), cell.getColumnIndex());
            for (String name : HEADERS) if (!columns.containsKey(normalize(name))) throw invalid();
            List<ImportRow> rows = new ArrayList<>();
            Set<List<String>> seen = new HashSet<>();
            var formatter = new DataFormatter(Locale.ROOT);
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                var row = sheet.getRow(index);
                if (row == null) continue;
                boolean empty = true;
                for (var cell : row) if (!formatter.formatCellValue(cell).trim().isEmpty()) { empty = false; break; }
                if (empty) continue;
                if (rows.size() == MAX_ROWS) throw invalid();
                List<String> errors = new ArrayList<>();
                List<String> values = new ArrayList<>();
                for (String name : HEADERS) {
                    var cell = row.getCell(columns.get(normalize(name)));
                    if (cell != null && cell.getCellType() == CellType.FORMULA) errors.add("formula");
                    values.add(cell != null && cell.getCellType() == CellType.FORMULA ? "" : text(cell));
                }
                String category = values.get(0), categoryName = values.get(1), name = values.get(2), location = values.get(3);
                if (values.stream().anyMatch(String::isBlank)) errors.add("required");
                if (name.length() > 100) errors.add("nameLength");
                if (category.length() > 64 || categoryName.length() > 128 || location.length() > 255) errors.add("fieldLength");
                if (!seen.add(List.of(category, name, location))) errors.add("duplicate");
                rows.add(new ImportRow(index + 1, category, categoryName, name, location, errors));
            }
            if (rows.isEmpty()) throw invalid();
            return rows;
        } catch (BusinessException error) { throw error; }
        catch (IOException | RuntimeException error) { throw invalid(); }
    }

    /** 导出使用名称，缺少关联资产读取权限时留空，不泄露关联 ID。 */
    public static byte[] export(List<PatrolPointEntity> points, Map<String, String> categories,
                                Map<String, String> tags, Map<String, String> pointPosts) throws IOException {
        try (var book = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("巡更点位");
            header(sheet, new String[]{"点位名称", "点位编码", "分类", "指定巡更岗位", "标签", "所属位置", "状态", "最后编辑时间"});
            var format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));
            int index = 1;
            for (var point : points) {
                String[] values = {point.getName(), point.getCode(), categories.getOrDefault(point.getCategoryId(), ""),
                        pointPosts.getOrDefault(point.getId(), ""),
                        String.join("、", (point.getTagIds() == null ? List.<String>of() : point.getTagIds()).stream()
                                .map(tags::get).filter(Objects::nonNull).toList()), point.getLocation(),
                        point.isEnabled() ? "启用" : "停用", point.getModifyTime() == null ? "" : format.format(Instant.ofEpochMilli(point.getModifyTime()))};
                var row = sheet.createRow(index++);
                // 显式写字符串，名称以 =/+ 开头也不成为公式。
                for (int col = 0; col < values.length; col++) row.createCell(col).setCellValue(values[col] == null ? "" : values[col]);
            }
            book.write(bytes); return bytes.toByteArray();
        }
    }

    private static void header(Sheet sheet, String[] names) {
        var row = sheet.createRow(0);
        for (int col = 0; col < names.length; col++) {
            row.createCell(col).setCellValue(names[col]); sheet.setColumnWidth(col, (col == 0 ? 38 : 28) * 256);
        }
        sheet.createFreezePane(0, 1);
    }
    private static String normalize(String value) { return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT); }
    private static String text(Cell cell) { return cell == null ? "" : new DataFormatter(Locale.ROOT).formatCellValue(cell).trim(); }
    static BusinessException invalid() { return new BusinessException("error.patrol.point.workbook"); }
}
