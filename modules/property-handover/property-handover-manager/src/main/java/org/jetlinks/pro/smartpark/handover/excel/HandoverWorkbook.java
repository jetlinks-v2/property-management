package org.jetlinks.pro.smartpark.handover.excel;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.hswebframework.reactor.excel.CellDataType;
import org.hswebframework.reactor.excel.ExcelHeader;
import org.hswebframework.reactor.excel.poi.options.WorkbookOption;
import org.jetlinks.pro.io.excel.ExcelUtils;
import org.springframework.core.io.buffer.DataBuffer;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 平台 ExcelUtils 负责输出，POI 扩展负责下拉、中文必填表头及物理行号读取。 */
public final class HandoverWorkbook {
    public static final int MAX_BYTES = 32 * 1024 * 1024;

    public record ImportRow(int row, Map<String, String> values, List<EmbeddedImage> images) {}

    public record EmbeddedImage(byte[] bytes, String extension, String column) {}

    private HandoverWorkbook() {}

    public static Flux<DataBuffer> template(String kind, Map<String, List<String>> options) {
        var columns = HandoverExcelSchema.columns(kind);
        var headers =
                columns.stream()
                        .map(c -> new ExcelHeader(c.key(), c.title(), CellDataType.STRING))
                        .toList();
        return Flux.defer(
                        () ->
                                ExcelUtils.write(
                                        headers,
                                        Flux.empty(),
                                        "xlsx",
                                        WorkbookOption.of(
                                                book -> configure(book, columns, options))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static void configure(
            Workbook book,
            List<HandoverExcelSchema.Column> columns,
            Map<String, List<String>> options) {
        Sheet data = book.createSheet("导入数据");
        header(data, columns);
        Sheet example = book.createSheet("填写示例（不导入）");
        header(example, columns);
        Row exampleRow = example.createRow(1);
        for (int i = 0; i < columns.size(); i++) {
            var col = columns.get(i);
            var values = options.getOrDefault(col.key(), List.of());
            exampleRow.createCell(i).setCellValue(values.isEmpty() ? col.example() : values.get(0));
        }
        Sheet guide = book.createSheet("导入说明");
        guide.setColumnWidth(0, 100 * 256);
        List<String> instructions =
                List.of(
                        "红色表头为必填，仅第一张导入数据表参与导入。",
                        "当前项目由导入界面决定；名称必须匹配当前项目，重名时请先调整资料或补充清单编号。",
                        "分类使用上级/下级完整名称；人员多个时用分号分隔。日期格式：yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss。",
                        "图片请插入对应行的图片单元格，支持浮动图片、WPS单元格图片和Excel单元格图片；不支持远程图片公式。",
                        "全表校验通过后才写入；任意错误均不导入，请按返回行号修正后重试。示例表不会导入。");
        for (int i = 0; i < instructions.size(); i++)
            guide.createRow(i).createCell(0).setCellValue(instructions.get(i));
        Sheet refs = book.createSheet("选项参考");
        int referenceColumn = 0;
        for (int c = 0; c < columns.size(); c++) {
            var values = options.getOrDefault(columns.get(c).key(), List.of());
            if (values.isEmpty()) continue;
            refs.getRow(0);
            for (int r = 0; r < values.size(); r++) {
                Row row = refs.getRow(r);
                if (row == null) row = refs.createRow(r);
                row.createCell(referenceColumn).setCellValue(values.get(r));
            }
            String letter =
                    org.apache.poi.ss.util.CellReference.convertNumToColString(referenceColumn);
            String rangeName = "HandoverOption" + referenceColumn;
            Name name = book.createName();
            name.setNameName(rangeName);
            name.setRefersToFormula("'选项参考'!$" + letter + "$1:$" + letter + "$" + values.size());
            var helper = data.getDataValidationHelper();
            var constraint = helper.createFormulaListConstraint(rangeName);
            var validation =
                    helper.createValidation(constraint, new CellRangeAddressList(1, 1048575, c, c));
            validation.setShowErrorBox(true);
            validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
            validation.createErrorBox("选项无效", "请从当前项目选项中选择。");
            data.addValidationData(validation);
            referenceColumn++;
        }
        book.setSheetHidden(book.getSheetIndex(refs), true);
    }

    private static void header(Sheet sheet, List<HandoverExcelSchema.Column> columns) {
        var normal = sheet.getWorkbook().createCellStyle();
        var font = sheet.getWorkbook().createFont();
        font.setBold(true);
        normal.setFont(font);
        var required = sheet.getWorkbook().createCellStyle();
        required.cloneStyleFrom(normal);
        var red = sheet.getWorkbook().createFont();
        red.setBold(true);
        red.setColor(IndexedColors.RED.getIndex());
        required.setFont(red);
        var row = sheet.createRow(0);
        row.setHeightInPoints(26);
        for (int i = 0; i < columns.size(); i++) {
            var col = columns.get(i);
            var cell = row.createCell(i);
            cell.setCellValue(col.title());
            cell.setCellStyle(col.required() ? required : normal);
            sheet.setColumnWidth(i, (col.key().equals("description") ? 42 : 24) * 256);
        }
        sheet.createFreezePane(0, 1);
    }

    public static List<ImportRow> read(byte[] bytes, String kind) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid(1, "文件", "文件为空或超过32MB");
        var columns = HandoverExcelSchema.columns(kind);
        var errors = new ArrayList<HandoverImportException.RowError>();
        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (book.getNumberOfSheets() == 0) throw invalid(1, "表头", "未找到导入数据表");
            Sheet sheet = book.getSheetAt(0);
            Row title = sheet.getRow(0);
            if (title == null) throw invalid(1, "表头", "缺少中文表头");
            var byTitle = new HashMap<String, HandoverExcelSchema.Column>();
            columns.forEach(c -> byTitle.put(c.title(), c));
            var positions = new LinkedHashMap<Integer, HandoverExcelSchema.Column>();
            Set<String> seen = new HashSet<>();
            var formatter = new DataFormatter(java.util.Locale.CHINA);
            for (int c = 0; c < title.getLastCellNum(); c++) {
                String text = formatter.formatCellValue(title.getCell(c)).trim();
                if (text.isEmpty()) continue;
                var column = byTitle.get(text);
                if (column == null || !seen.add(column.key()))
                    errors.add(new HandoverImportException.RowError(1, text, "未知或重复表头"));
                else positions.put(c, column);
            }
            columns.stream()
                    .filter(HandoverExcelSchema.Column::required)
                    .filter(c -> !seen.contains(c.key()))
                    .forEach(
                            c ->
                                    errors.add(
                                            new HandoverImportException.RowError(
                                                    1, c.title(), "缺少必填列")));
            if (!errors.isEmpty()) throw new HandoverImportException(errors);
            var images = HandoverEmbeddedImages.read(bytes, book, sheet, positions);
            var result = new ArrayList<ImportRow>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                var values = new LinkedHashMap<String, String>();
                boolean populated = !images.getOrDefault(r, List.of()).isEmpty();
                for (var position : positions.entrySet()) {
                    Cell cell = row == null ? null : row.getCell(position.getKey());
                    var column = position.getValue();
                    String text = text(cell, formatter, column.key(), r + 1);
                    values.put(column.key(), text);
                    populated |= !text.isBlank();
                }
                if (!populated) continue;
                for (var col : columns)
                    if (col.required() && values.getOrDefault(col.key(), "").isBlank())
                        errors.add(
                                new HandoverImportException.RowError(
                                        r + 1, col.title(), "必填项不能为空"));
                result.add(new ImportRow(r + 1, values, images.getOrDefault(r, List.of())));
            }
            if (result.isEmpty())
                errors.add(new HandoverImportException.RowError(2, "数据", "请填写导入数据，示例表不会导入"));
            if (!errors.isEmpty()) throw new HandoverImportException(errors);
            return result;
        } catch (HandoverImportException error) {
            throw error;
        } catch (Exception error) {
            var failure = invalid(1, "文件", "无法解析Excel，请检查文件格式及图片内容");
            failure.initCause(error);
            throw failure;
        }
    }

    private static String text(Cell cell, DataFormatter formatter, String key, int row) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.FORMULA) {
            if (key.equals("photos")
                    && cell.getCellFormula()
                            .toUpperCase(java.util.Locale.ROOT)
                            .contains("DISPIMG(")) return "";
            throw invalid(row, key, "不支持计算公式，请粘贴为值");
        }
        if (cell.getCellType() == CellType.ERROR && key.equals("photos")) return "";
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell))
            return cell.getLocalDateTimeCellValue()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return formatter.formatCellValue(cell).trim();
    }

    static HandoverImportException invalid(int row, String column, String message) {
        return new HandoverImportException(
                List.of(new HandoverImportException.RowError(row, column, message)));
    }
}
