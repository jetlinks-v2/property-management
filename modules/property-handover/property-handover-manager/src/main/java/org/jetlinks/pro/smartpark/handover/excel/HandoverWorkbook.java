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
        return template(kind, null, options);
    }

    public static Flux<DataBuffer> template(
            String kind, String stage, Map<String, List<String>> options) {
        var columns = HandoverExcelSchema.columns(kind, stage);
        var headers =
                columns.stream()
                        .map(c -> new ExcelHeader(c.key(), c.title(), CellDataType.STRING))
                        .toList();
        return Flux.defer(
                        () ->
                                ExcelUtils.write(
                                        // 台账自带8行说明和第9行表头，禁止默认表头写回第1行覆盖说明。
                                        "ledger".equals(kind) ? List.<ExcelHeader>of() : headers,
                                        Flux.empty(),
                                        "xlsx",
                                        WorkbookOption.of(
                                                book -> configure(book, kind, columns, options))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static void configure(
            Workbook book,
            String kind,
            List<HandoverExcelSchema.Column> columns,
            Map<String, List<String>> options) {
        Sheet data = book.createSheet("导入数据");
        int headerRow = "ledger".equals(kind) ? 8 : 0;
        if (headerRow > 0) {
            List<String> notes = List.of("前介台账问题导入模板", "填写说明：红色表头为必填，数据从第10行开始填写。",
                    "项目名称：填写当前账号有权限的项目全称；分类、责任单位填写该项目已配置的名称。",
                    "问题分类有层级时填写上级/下级；名称重复无法确定时会报行错误。",
                    "问题位置最多200字，问题描述最多500字。",
                    "问题图片最多9张jpg/jpeg/png，单张不超过2MB，请插入对应行图片列；不支持远程图片公式。",
                    "检查时间格式：yyyy-MM-dd HH:mm:ss，留空使用录入时间；发起人留空使用当前登录人，不可冒用他人。",
                    "整表校验通过才导入；错误显示Excel行号。填写示例单独成表，不参与导入。");
            for (int r = 0; r < notes.size(); r++) {
                data.createRow(r).createCell(0).setCellValue(notes.get(r));
                data.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(r, r, 0, columns.size() - 1));
            }
        }
        header(data, columns, headerRow);
        Sheet example = book.createSheet("填写示例（不导入）");
        header(example, columns, 0);
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
                        "ledger".equals(kind) ? "每行填写项目全称，分类与责任单位必须匹配该项目；发起人留空使用当前登录人。" : "当前项目由导入界面决定；名称必须匹配当前项目，重名时请先调整资料或补充清单编号。",
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
                    helper.createValidation(constraint, new CellRangeAddressList(headerRow + 1, 1048575, c, c));
            validation.setShowErrorBox(true);
            validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
            validation.createErrorBox("选项无效", "请从当前项目选项中选择。");
            data.addValidationData(validation);
            referenceColumn++;
        }
        book.setSheetHidden(book.getSheetIndex(refs), true);
    }

    private static void header(Sheet sheet, List<HandoverExcelSchema.Column> columns, int headerRow) {
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
        var row = sheet.createRow(headerRow);
        row.setHeightInPoints(26);
        for (int i = 0; i < columns.size(); i++) {
            var col = columns.get(i);
            var cell = row.createCell(i);
            cell.setCellValue(col.title());
            cell.setCellStyle(col.required() ? required : normal);
            sheet.setColumnWidth(i, (col.key().equals("description") ? 42 : 24) * 256);
        }
        sheet.createFreezePane(0, headerRow + 1);
    }

    public static List<ImportRow> read(byte[] bytes, String kind) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid(1, "文件", "文件为空或超过32MB");
        var columns = HandoverExcelSchema.columns(kind);
        var errors = new ArrayList<HandoverImportException.RowError>();
        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (book.getNumberOfSheets() == 0) throw invalid(1, "表头", "未找到导入数据表");
            Sheet sheet = book.getSheetAt(0);
            int headerRow = 0;
            if ("ledger".equals(kind)) {
                // 兼容原系统模板的8行说明；只按完整中文字段识别表头，不将说明行当作数据。
                headerRow = ledgerHeader(sheet);
            }
            Row title = sheet.getRow(headerRow);
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
                    errors.add(new HandoverImportException.RowError(headerRow + 1, text, "未知或重复表头"));
                else positions.put(c, column);
            }
            columns.stream()
                    .filter(HandoverExcelSchema.Column::required)
                    .filter(c -> !seen.contains(c.key()))
                    .forEach(
                            c ->
                                    errors.add(
                                            new HandoverImportException.RowError(
                                                    title.getRowNum() + 1, c.title(), "缺少必填列")));
            if (!errors.isEmpty()) throw new HandoverImportException(errors);
            var images = HandoverEmbeddedImages.read(bytes, book, sheet, positions);
            var result = new ArrayList<ImportRow>();
            for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
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
                errors.add(new HandoverImportException.RowError(headerRow + 2, "数据", "请填写导入数据，示例表不会导入"));
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

    private static int ledgerHeader(Sheet sheet) {
        var formatter = new DataFormatter(java.util.Locale.CHINA);
        for (int r = 0; r <= Math.min(19, sheet.getLastRowNum()); r++) {
            var row = sheet.getRow(r);
            if (row == null) continue;
            var labels = new HashSet<String>();
            for (Cell cell : row) labels.add(formatter.formatCellValue(cell).trim());
            if (labels.contains("项目名称") && labels.contains("问题描述") && labels.contains("问题分类")) return r;
        }
        throw invalid(9, "表头", "缺少前介台账中文表头，请下载最新模板");
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
