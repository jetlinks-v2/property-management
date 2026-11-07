package org.jetlinks.pro.property.finance.receivable;

import org.apache.poi.ss.usermodel.*;
import org.hswebframework.reactor.excel.CellDataType;
import org.hswebframework.reactor.excel.ExcelHeader;
import org.hswebframework.reactor.excel.poi.options.WorkbookOption;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.io.excel.ExcelUtils;
import org.springframework.core.io.buffer.DataBuffer;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;

/** 与档案Excel一致：框架输出模板，POI保留物理行号；公式不作为可信业务金额执行。 */
public final class HistoricalWorkbook {
    private HistoricalWorkbook() {}

    public static final int LIMIT =
            org.jetlinks.pro.property.finance.payment.FinanceExcelRows.LIMIT;
    public static final List<String> HEADERS =
            List.of(
                    "合同编号", "房屋完整路径", "客户名称", "收费科目", "收费标准", "计费开始日期", "计费结束日期", "应收账期", "应收日期",
                    "原应收", "减免金额", "实际应收", "备注");

    public record Line(
            int row,
            String contractCode,
            String fullName,
            String customerName,
            String subjectName,
            String standardName,
            LocalDate start,
            LocalDate end,
            String accountPeriod,
            LocalDate due,
            BigDecimal original,
            BigDecimal reduction,
            BigDecimal actual,
            String remark) {}

    public static List<Line> parse(byte[] bytes) throws Exception {
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (book.getNumberOfSheets() == 0) throw rowError(1, "文件", "historical_file_invalid");
            var sheet = book.getSheetAt(0);
            var formatter = new DataFormatter(Locale.ROOT);
            for (int c = 0; c < HEADERS.size(); c++)
                if (!HEADERS.get(c).equals(text(sheet.getRow(0), c, formatter, 1)))
                    throw rowError(1, HEADERS.get(c), "historical_header_invalid");
            if (sheet.getLastRowNum() > LIMIT) throw rowError(LIMIT + 2, "文件", "historical_limit");
            var result = new ArrayList<Line>();
            var identities = new HashSet<List<String>>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                var row = sheet.getRow(i);
                if (row == null) continue;
                var values = new ArrayList<String>();
                for (int c = 0; c < HEADERS.size(); c++) values.add(text(row, c, formatter, i + 1));
                if (values.stream().allMatch(String::isBlank)) continue;
                for (int c = 1; c <= 11; c++)
                    if (values.get(c).isBlank())
                        throw rowError(i + 1, HEADERS.get(c), "historical_required");
                var start = date(row, 5, values.get(5), i + 1);
                var end = date(row, 6, values.get(6), i + 1);
                var due = date(row, 8, values.get(8), i + 1);
                if (start.isAfter(end))
                    throw rowError(i + 1, HEADERS.get(6), "historical_dates_invalid");
                var period = values.get(7);
                var periodCell = row.getCell(7);
                if (periodCell != null
                        && periodCell.getCellType() == CellType.NUMERIC
                        && DateUtil.isCellDateFormatted(periodCell))
                    period = YearMonth.from(periodCell.getLocalDateTimeCellValue()).toString();
                if (!period.matches("[0-9]{4}-(0[1-9]|1[0-2])"))
                    throw rowError(i + 1, HEADERS.get(7), "historical_period_invalid");
                var original = money(values.get(9), i + 1, 9);
                var reduction = money(values.get(10), i + 1, 10);
                var actual = money(values.get(11), i + 1, 11);
                if (original.subtract(reduction).compareTo(actual) != 0)
                    throw rowError(i + 1, HEADERS.get(11), "historical_amount_mismatch");
                if (values.get(0).length() > 100 || values.get(12).length() > 1000)
                    throw rowError(i + 1, "合同编号/备注", "historical_text_limit");
                if (!identities.add(
                        List.of(
                                values.get(1),
                                values.get(2),
                                values.get(3),
                                values.get(4),
                                start.toString(),
                                end.toString())))
                    throw rowError(i + 1, "房屋/客户/科目/标准/日期", "historical_duplicate_file");
                result.add(
                        new Line(
                                i + 1,
                                values.get(0),
                                values.get(1),
                                values.get(2),
                                values.get(3),
                                values.get(4),
                                start,
                                end,
                                period,
                                due,
                                original,
                                reduction,
                                actual,
                                values.get(12)));
            }
            if (result.isEmpty()) throw rowError(2, "文件", "historical_file_empty");
            return result;
        }
    }

    /** 流式行解码，沿用相同字段及金额恒等式，保留Excel物理行号。 */
    public static Line decode(
            org.jetlinks.pro.property.finance.payment.FinanceExcelRows.Row row, Set<String> seen) {
        var v = new ArrayList<String>(row.cells());
        while (v.size() < HEADERS.size()) v.add("");
        if (v.stream().allMatch(String::isBlank)) return null;
        for (int c = 1; c <= 11; c++)
            if (v.get(c).isBlank())
                throw rowError(row.number(), HEADERS.get(c), "historical_required");
        LocalDate start, end, due;
        try {
            start = LocalDate.parse(v.get(5));
            end = LocalDate.parse(v.get(6));
            due = LocalDate.parse(v.get(8));
        } catch (RuntimeException e) {
            throw rowError(row.number(), "计费开始日期/计费结束日期/应收日期", "historical_date_invalid");
        }
        if (start.isAfter(end))
            throw rowError(row.number(), HEADERS.get(6), "historical_dates_invalid");
        if (!v.get(7).matches("[0-9]{4}-(0[1-9]|1[0-2])"))
            throw rowError(row.number(), HEADERS.get(7), "historical_period_invalid");
        var original = money(v.get(9), row.number(), 9);
        var reduction = money(v.get(10), row.number(), 10);
        var actual = money(v.get(11), row.number(), 11);
        if (original.subtract(reduction).compareTo(actual) != 0)
            throw rowError(row.number(), HEADERS.get(11), "historical_amount_mismatch");
        if (v.get(0).length() > 100 || v.get(12).length() > 1000)
            throw rowError(row.number(), "合同编号/备注", "historical_text_limit");
        if (!seen.add(com.alibaba.fastjson.JSON.toJSONString(v.subList(1, 7))))
            throw rowError(row.number(), "房屋/客户/科目/标准/日期", "historical_duplicate_file");
        return new Line(
                row.number(),
                v.get(0),
                v.get(1),
                v.get(2),
                v.get(3),
                v.get(4),
                start,
                end,
                v.get(7),
                due,
                original,
                reduction,
                actual,
                v.get(12));
    }

    private static String text(Row row, int col, DataFormatter formatter, int number) {
        if (row == null) return "";
        var cell = row.getCell(col);
        if (cell == null) return "";
        if (cell.getCellType() == CellType.FORMULA || cell.getCellType() == CellType.ERROR)
            throw rowError(number, HEADERS.get(col), "historical_formula_denied");
        return formatter.formatCellValue(cell).trim();
    }

    private static LocalDate date(Row row, int col, String value, int number) {
        var cell = row.getCell(col);
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell))
                return cell.getLocalDateTimeCellValue().toLocalDate();
            return LocalDate.parse(
                    value,
                    DateTimeFormatter.ofPattern("uuuu-MM-dd")
                            .withResolverStyle(ResolverStyle.STRICT));
        } catch (RuntimeException failure) {
            throw rowError(number, HEADERS.get(col), "historical_date_invalid");
        }
    }

    private static BigDecimal money(String value, int row, int col) {
        try {
            var amount = new BigDecimal(value);
            if (amount.signum() < 0
                    || amount.scale() > 2
                    || amount.precision() - amount.scale() > 18)
                throw new IllegalArgumentException();
            return amount.setScale(2);
        } catch (RuntimeException failure) {
            throw rowError(row, HEADERS.get(col), "historical_money_invalid");
        }
    }

    public static BusinessException rowError(int row, String field, String reason) {
        var message =
                org.hswebframework.web.i18n.LocaleUtils.resolveMessage(
                        "error.property_finance_" + reason, "error.property_finance_" + reason);
        return new BusinessException.NoStackTrace(
                "error.property_finance_historical_row",
                "error.property_finance_historical_row",
                400,
                row,
                field,
                message);
    }

    public static Flux<DataBuffer> template(
            String project, List<List<String>> houses, List<List<String>> standards) {
        var headers =
                HEADERS.stream().map(s -> new ExcelHeader(s, s, CellDataType.STRING)).toList();
        return Flux.defer(
                        () ->
                                ExcelUtils.write(
                                        headers,
                                        Flux.<Map<String, Object>>empty(),
                                        "xlsx",
                                        WorkbookOption.of(
                                                book -> {
                                                    var data = book.createSheet("历史应收");
                                                    data.createFreezePane(0, 1);
                                                    header(book, data);
                                                    var style = book.createCellStyle();
                                                    style.setDataFormat(
                                                            book.createDataFormat().getFormat("@"));
                                                    for (int c = 0; c < HEADERS.size(); c++) {
                                                        data.setColumnWidth(
                                                                c, (c == 1 ? 55 : 23) * 256);
                                                        data.setDefaultColumnStyle(c, style);
                                                    }
                                                    reference(
                                                            book,
                                                            "项目房屋与客户",
                                                            List.of("房屋完整路径", "客户名称"),
                                                            houses);
                                                    reference(
                                                            book,
                                                            "项目科目与标准",
                                                            List.of("收费科目", "收费标准"),
                                                            standards);
                                                    var example = data.createRow(1);
                                                    var values =
                                                            List.of(
                                                                    "HT-示例001",
                                                                    "示例园区/1栋/101",
                                                                    "张某",
                                                                    "物业费",
                                                                    "示例收费标准",
                                                                    "2026-01-01",
                                                                    "2026-01-31",
                                                                    "2026-01",
                                                                    "2026-02-05",
                                                                    "100.00",
                                                                    "0.00",
                                                                    "100.00",
                                                                    "脱敏示例，导入前请删除本行");
                                                    for (int c = 0; c < values.size(); c++)
                                                        example.createCell(c)
                                                                .setCellValue(values.get(c));
                                                    reference(
                                                            book,
                                                            "填写说明",
                                                            List.of("字段", "填写说明"),
                                                            List.of(
                                                                    List.of("所属项目", project),
                                                                    List.of(
                                                                            "必填",
                                                                            "红色表头为必填；最多50000行；只导入第一张表；不接受公式。第二行是脱敏示例，导入前请自行删除。"),
                                                                    List.of(
                                                                            "日期",
                                                                            "计费起止和应收日期填写yyyy-MM-dd，应收账期填写yyyy-MM。"),
                                                                    List.of(
                                                                            "金额",
                                                                            "原应收－减免金额＝实际应收，金额非负且最多两位小数。历史金额全期冻结。"),
                                                                    List.of(
                                                                            "匹配",
                                                                            "填写完整房屋路径；客户须是该房屋有效客户；同名不唯一会拒绝。")));
                                                    dropdown(
                                                            book,
                                                            data,
                                                            "项目房屋与客户",
                                                            0,
                                                            1,
                                                            houses.size());
                                                    dropdown(
                                                            book,
                                                            data,
                                                            "项目房屋与客户",
                                                            1,
                                                            2,
                                                            houses.size());
                                                    dropdown(
                                                            book,
                                                            data,
                                                            "项目科目与标准",
                                                            0,
                                                            3,
                                                            standards.size());
                                                    dropdown(
                                                            book,
                                                            data,
                                                            "项目科目与标准",
                                                            1,
                                                            4,
                                                            standards.size());
                                                })))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static void reference(
            Workbook book, String name, List<String> headers, List<List<String>> rows) {
        var sheet = book.createSheet(name);
        var header = sheet.createRow(0);
        for (int c = 0; c < headers.size(); c++) {
            header.createCell(c).setCellValue(headers.get(c));
            sheet.setColumnWidth(c, (c == 0 ? 55 : 35) * 256);
        }
        for (int r = 0; r < rows.size(); r++) {
            var row = sheet.createRow(r + 1);
            for (int c = 0; c < rows.get(r).size(); c++)
                row.createCell(c, CellType.STRING).setCellValue(rows.get(r).get(c));
        }
    }

    private static void header(Workbook book, Sheet sheet) {
        var requiredFont = book.createFont();
        requiredFont.setBold(true);
        requiredFont.setColor(IndexedColors.RED.getIndex());
        var optionalFont = book.createFont();
        optionalFont.setBold(true);
        var required = book.createCellStyle();
        required.setFont(requiredFont);
        var optional = book.createCellStyle();
        optional.setFont(optionalFont);
        var row = sheet.getRow(0) == null ? sheet.createRow(0) : sheet.getRow(0);
        for (int c = 0; c < HEADERS.size(); c++) {
            var cell = row.getCell(c) == null ? row.createCell(c) : row.getCell(c);
            cell.setCellValue(HEADERS.get(c));
            cell.setCellStyle(c >= 1 && c <= 11 ? required : optional);
        }
    }

    private static void dropdown(
            Workbook book, Sheet sheet, String reference, int source, int target, int count) {
        if (count == 0) return;
        var name = book.createName();
        name.setNameName("options_" + target);
        String letter = org.apache.poi.ss.util.CellReference.convertNumToColString(source);
        name.setRefersToFormula(
                "'" + reference + "'!$" + letter + "$2:$" + letter + "$" + (count + 1));
        var helper = sheet.getDataValidationHelper();
        var validation =
                helper.createValidation(
                        helper.createFormulaListConstraint(name.getNameName()),
                        new org.apache.poi.ss.util.CellRangeAddressList(1, LIMIT, target, target));
        validation.setShowErrorBox(true);
        sheet.addValidationData(validation);
    }
}
