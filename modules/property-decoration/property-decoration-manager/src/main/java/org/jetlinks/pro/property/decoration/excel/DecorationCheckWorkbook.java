package org.jetlinks.pro.property.decoration.excel;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetlinks.pro.property.decoration.model.DecorationModels;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.jetlinks.pro.property.decoration.service.DecorationRules.error;

/** 原PC检查配置19列格式；仅解析、校验文件，不查询主数据、不上传图片或写库。
 * POI调用由编排层放到boundedElastic；项目及房屋类型名称须再由公共目录解析。
 */
public final class DecorationCheckWorkbook {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_ROWS = 1000;
    public static final List<String> HEADERS = List.of(
        "验收场景*", "所属项目*", "房屋类型*", "装修类型*", "检查大类*", "大类排序",
        "检查小项内容*", "填报类型*", "是否必填*", "是否启用*", "小项排序", "文本判断标准",
        "数值判断标准", "最小标准值", "最大标准值", "选项值", "默认标准项", "示例图片", "图片判断标准");
    private static final Map<String, String> SCENES = Map.of("日常巡检", "daily", "首次验收", "firstAcceptance", "二次验收", "secondAcceptance",
        "daily", "daily", "firstAcceptance", "firstAcceptance", "secondAcceptance", "secondAcceptance");
    private static final Map<String, String> TYPES = Map.of("首次装修", "first", "二次装修", "second", "first", "first", "second", "second");
    private static final Map<String, String> INPUTS = Map.of("文本", "text", "数值", "number", "图像", "image", "选项", "option",
        "text", "text", "number", "number", "image", "image", "option", "option");

    private DecorationCheckWorkbook() {}

    /** 行号为Excel物理行号；code供调用方按当前语言解释，不含内部异常或认证信息。 */
    public record Problem(int rowNumber, String columnName, String cellValue, String code) {}
    public record Image(byte[] bytes, String extension) {}
    public record Entry(int rowNumber, String scene, String projectName, String houseType,
        String decorationType, Integer categorySort, Integer itemSort, DecorationModels.CheckItem item, Image image) {}
    public record Parsed(List<Entry> entries, List<Problem> errors) {
        public int validCount() {
            var invalid = errors.stream().map(Problem::rowNumber).collect(java.util.stream.Collectors.toSet());
            return (int) entries.stream().filter(entry -> !invalid.contains(entry.rowNumber())).count();
        }
    }

    public static Parsed read(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw error("import_file", 400);
        var archive = DecorationCheckImages.archive(bytes);
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (!(book instanceof XSSFWorkbook) || book.getNumberOfSheets() == 0) throw error("import_file", 400);
            var sheet = book.getSheetAt(0);
            var formatter = new DataFormatter(Locale.CHINA);
            int header = -1;
            for (int row = 0; row <= Math.min(20, sheet.getLastRowNum()); row++) {
                if (values(sheet.getRow(row), formatter).equals(HEADERS)) { header = row; break; }
            }
            if (header < 0) throw error("import_headers", 400);
            // 物理行数也有界，避免巨大稀疏工作表消耗事件处理线程及内存。
            if (sheet.getLastRowNum() - header > MAX_ROWS) throw error("import_limit", 400);
            var errors = new ArrayList<Problem>();
            var pictures = DecorationCheckImages.read(sheet, archive, errors);
            int lastRow = Math.max(sheet.getLastRowNum(), pictures.keySet().stream().mapToInt(Integer::intValue).max().orElse(0));
            if (lastRow - header > MAX_ROWS) throw error("import_limit", 400);
            var entries = new ArrayList<Entry>();
            for (int row = header + 1; row <= lastRow; row++) {
                var cells = values(sheet.getRow(row), formatter);
                var images = pictures.getOrDefault(row, List.of());
                if (cells.stream().allMatch(String::isBlank) && images.isEmpty() && !extraCells(sheet.getRow(row))) continue;
                validateFormulae(sheet.getRow(row), row + 1, errors);
                entries.add(parse(row + 1, cells, images, errors));
            }
            if (entries.isEmpty()) throw error("import_empty", 400);
            duplicates(entries, errors);
            return new Parsed(entries, errors);
        } catch (org.hswebframework.web.exception.BusinessException e) { throw e; }
        catch (IOException | RuntimeException e) {
            throw error("import_file", 400);
        }
    }

    /** 下载模板按相同19列生成，说明置于独立工作表，不添加会被误导入的示例业务行。 */
    public static byte[] template() {
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("检查配置");
            var header = sheet.createRow(0);
            for (int column = 0; column < HEADERS.size(); column++) {
                header.createCell(column).setCellValue(HEADERS.get(column));
                sheet.setColumnWidth(column, 22 * 256);
            }
            sheet.createFreezePane(0, 1);
            var notes = book.createSheet("填写说明");
            var instructions = List.of("数据填写在检查配置页，从第2行开始。全部有效行均作为正式配置导入。",
                "项目填写有权项目名称，房屋类型填写已启用的公共字典名称或编码；同名项目无法唯一定位时请先修正主档。",
                "验收场景：日常巡检、首次验收、二次验收；装修类型：首次装修、二次装修。",
                "填报类型：文本、数值、图像、选项；是否必填及是否启用：是、否或1、0。排序为正整数，留空时按现有内容追加。",
                "选项值用英文竖线|分隔且至少两个，不可重复；默认标准项须为选项之一。最小值不得大于最大值。",
                "示例图片仅用于图像类型，粘贴到同一行的示例图片列，每行最多一张JPG或PNG，不超过2MB。",
                "同一维度和分类内按小项名称合并；未出现在文件中的检查项保留。任何行校验失败均不导入。",
                "文件仅支持xlsx，最大10MB、1000个数据行；不执行公式，仅识别示例图片的DISPIMG引用。");
            for (int row = 0; row < instructions.size(); row++) notes.createRow(row).createCell(0).setCellValue(instructions.get(row));
            notes.setColumnWidth(0, 120 * 256);
            book.write(output);
            return output.toByteArray();
        } catch (IOException e) { throw error("import_file", 400); }
    }

    private static List<String> values(Row row, DataFormatter formatter) {
        var cells = new ArrayList<String>(HEADERS.size());
        for (int column = 0; column < HEADERS.size(); column++) {
            var cell = row == null ? null : row.getCell(column);
            cells.add(cell == null ? "" : normalize(formatter.formatCellValue(cell)));
        }
        return cells;
    }

    private static Entry parse(int row, List<String> cells, List<Image> images, List<Problem> errors) {
        for (int column : new int[]{0, 1, 2, 3, 4, 6, 7, 8, 9}) {
            if (cells.get(column).isBlank()) problem(errors, row, column, "", "required");
        }
        var item = new DecorationModels.CheckItem();
        item.setCategory(cells.get(4)); item.setName(cells.get(6));
        item.setInputType(mapped(cells, row, 7, INPUTS, errors));
        item.setRequired(bool(cells, row, 8, errors)); item.setEnabled(bool(cells, row, 9, errors));
        Integer categorySort = sort(cells, row, 5, errors), itemSort = sort(cells, row, 10, errors);
        if (itemSort != null) item.setSort(itemSort);
        String scene = mapped(cells, row, 0, SCENES, errors), type = mapped(cells, row, 3, TYPES, errors);
        if ("number".equals(item.getInputType())) {
            item.setStandard(cells.get(12)); item.setMin(decimal(cells, row, 13, errors)); item.setMax(decimal(cells, row, 14, errors));
            if (item.getMin() != null && item.getMax() != null && item.getMin().compareTo(item.getMax()) > 0) {
                problem(errors, row, 14, cells.get(14), "range");
            }
        } else if ("option".equals(item.getInputType())) {
            var options = Arrays.stream(cells.get(15).split("\\|", -1)).map(DecorationCheckWorkbook::normalize).filter(s -> !s.isBlank()).toList();
            if (options.size() < 2 || options.stream().distinct().count() != options.size()) problem(errors, row, 15, cells.get(15), "options");
            item.setOptions(options); item.setStandard(cells.get(16));
            if (!item.getStandard().isBlank() && !options.contains(item.getStandard())) problem(errors, row, 16, cells.get(16), "option_default");
        } else if ("image".equals(item.getInputType())) item.setStandard(cells.get(18));
        else if ("text".equals(item.getInputType())) item.setExample(cells.get(11));
        if (images.size() > 1) problem(errors, row, 17, String.valueOf(images.size()), "image_count");
        if (!images.isEmpty() && !"image".equals(item.getInputType())) problem(errors, row, 17, "", "image_type");
        for (var image : images) {
            if (!List.of("jpg", "jpeg", "png").contains(image.extension()) || image.bytes().length == 0 || image.bytes().length > 2 * 1024 * 1024) {
                problem(errors, row, 17, image.extension(), "image_file");
            }
        }
        // URL/文本不能替代业务私有附件；DISPIMG由图片解析器解析，其他填法报告错误。
        if (!cells.get(17).isBlank() && !DecorationCheckImages.isReference(cells.get(17))) problem(errors, row, 17, cells.get(17), "image_reference");
        for (int column = 0; column < cells.size(); column++) {
            if (cells.get(column).length() > 2000) problem(errors, row, column, cells.get(column).substring(0, 100), "text_limit");
        }
        return new Entry(row, scene, cells.get(1), cells.get(2), type, categorySort, itemSort, item, images.isEmpty() ? null : images.get(0));
    }

    private static void duplicates(List<Entry> entries, List<Problem> errors) {
        var items = new HashMap<List<String>, Integer>();
        var categories = new LinkedHashMap<List<String>, Integer>();
        for (var entry : entries) {
            var key = List.of(entry.projectName(), entry.houseType(), entry.decorationType(), entry.scene(), entry.item().getCategory());
            var itemKey = new ArrayList<>(key); itemKey.add(entry.item().getName());
            if (items.putIfAbsent(itemKey, entry.rowNumber()) != null) problem(errors, entry.rowNumber(), 6, entry.item().getName(), "duplicate_item");
            if (entry.categorySort() != null) {
                var previous = categories.putIfAbsent(key, entry.categorySort());
                if (previous != null && !previous.equals(entry.categorySort())) problem(errors, entry.rowNumber(), 5, entry.categorySort().toString(), "category_sort");
            }
        }
    }

    private static String mapped(List<String> cells, int row, int column, Map<String, String> values, List<Problem> errors) {
        String value = cells.get(column), mapped = values.get(value);
        if (!value.isBlank() && mapped == null) problem(errors, row, column, value, "choice");
        return mapped == null ? "" : mapped;
    }
    private static boolean bool(List<String> cells, int row, int column, List<Problem> errors) {
        var value = cells.get(column);
        if (!value.isBlank() && !List.of("是", "否", "1", "0").contains(value)) problem(errors, row, column, value, "boolean");
        return "是".equals(value) || "1".equals(value);
    }
    private static Integer sort(List<String> cells, int row, int column, List<Problem> errors) {
        if (cells.get(column).isBlank()) return null;
        try { int result = new BigDecimal(cells.get(column)).intValueExact(); if (result > 0) return result; }
        catch (ArithmeticException | NumberFormatException ignored) { /* 由本字段统一报告 */ }
        problem(errors, row, column, cells.get(column), "sort"); return null;
    }
    private static BigDecimal decimal(List<String> cells, int row, int column, List<Problem> errors) {
        if (cells.get(column).isBlank()) return null;
        try { return new BigDecimal(cells.get(column)); }
        catch (NumberFormatException e) { problem(errors, row, column, cells.get(column), "decimal"); return null; }
    }
    private static void validateFormulae(Row row, int number, List<Problem> errors) {
        if (row == null) return;
        for (var cell : row) {
            int column = cell.getColumnIndex();
            if (column >= HEADERS.size()) {
                if (cell.getCellType() != CellType.BLANK && !cell.toString().isBlank()) problem(errors, number, HEADERS.size() - 1, cell.toString(), "extra_column");
            } else if (cell.getCellType() == CellType.FORMULA && !(column == 17 && DecorationCheckImages.isReference(cell.getCellFormula()))) {
                problem(errors, number, column, cell.getCellFormula(), "formula");
            }
        }
    }
    private static boolean extraCells(Row row) {
        if (row == null) return false;
        for (var cell : row) if (cell.getColumnIndex() >= HEADERS.size() && cell.getCellType() != CellType.BLANK && !cell.toString().isBlank()) return true;
        return false;
    }
    static void problem(List<Problem> errors, int row, int column, String value, String code) {
        errors.add(new Problem(row, HEADERS.get(column), value, code));
    }
    public static String normalize(String value) { return value == null ? "" : value.replace('\u00a0', ' ').trim().replaceAll("\\s+", " "); }
}
