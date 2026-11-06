package org.jetlinks.pro.property.finance.payment;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.hswebframework.reactor.excel.CellDataType;
import org.hswebframework.reactor.excel.ExcelHeader;
import org.hswebframework.reactor.excel.poi.options.WorkbookOption;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.io.excel.ExcelUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.core.io.buffer.*;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 有界Excel编解码保留物理行号；公式、超限及无效末行使整文件失败。 */
public final class CollectionImportWorkbook {
    private CollectionImportWorkbook() {}

    public static final int LIMIT = 500;
    public static final Set<String> KINDS = Set.of("RECEIVED", "ADVANCE", "DEPOSIT", "TEMPORARY");
    public static final List<String> FIELDS =
            List.of(
                    "externalNo",
                    "receivableId",
                    "fullName",
                    "customerName",
                    "subjectName",
                    "standardName",
                    "companyName",
                    "methodText",
                    "bankName",
                    "amount",
                    "paidAt",
                    "payerName",
                    "startDate",
                    "endDate",
                    "accountPeriod",
                    "quantity",
                    "remark",
                    "reductionAmount",
                    "reductionReason");
    public static final List<String> HEADERS =
            List.of(
                    "外部收款编号", "应收明细ID", "房屋完整路径", "客户名称", "收费科目", "收费标准", "收款公司", "支付方式", "入账银行",
                    "收款金额", "收款时间", "缴款人", "计费开始日期", "计费结束日期", "账期", "数量", "备注", "减免金额", "减免原因");

    public record Line(int row, CollectionImportInput payload) {}

    public static List<Line> parse(byte[] bytes, String kind) throws Exception {
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (book.getNumberOfSheets() == 0) throw rowError(1, "文件", "collection_file_invalid");
            var sheet = book.getSheetAt(0);
            var formatter = new DataFormatter(Locale.ROOT);
            for (int c = 0; c < HEADERS.size(); c++)
                if (!HEADERS.get(c).equals(text(sheet.getRow(0), c, formatter, 1)))
                    throw rowError(1, HEADERS.get(c), "collection_header_invalid");
            if (sheet.getLastRowNum() > LIMIT) throw rowError(LIMIT + 2, "文件", "collection_limit");
            var result = new ArrayList<Line>();
            var sources = new HashSet<String>();
            var receivables = new HashSet<String>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                var row = sheet.getRow(r);
                if (row == null) continue;
                var values = new ArrayList<String>();
                for (int c = 0; c < FIELDS.size(); c++) values.add(text(row, c, formatter, r + 1));
                if (values.stream().allMatch(String::isBlank)) continue;
                var input = new CollectionImportInput();
                var wrapper = new BeanWrapperImpl(input);
                for (int c = 0; c < FIELDS.size(); c++) {
                    var value = values.get(c);
                    if (value.isBlank()) continue;
                    try {
                        var cell = row.getCell(c);
                        if (Set.of(10, 12, 13).contains(c)
                                && cell.getCellType() == CellType.NUMERIC
                                && DateUtil.isCellDateFormatted(cell))
                            value =
                                    c == 10
                                            ? cell.getLocalDateTimeCellValue().toString()
                                            : cell.getLocalDateTimeCellValue()
                                                    .toLocalDate()
                                                    .toString();
                        wrapper.setPropertyValue(
                                FIELDS.get(c),
                                Set.of(9, 15, 17).contains(c) ? new BigDecimal(value) : value);
                    } catch (RuntimeException e) {
                        throw rowError(r + 1, HEADERS.get(c), "collection_field_invalid");
                    }
                }
                validate(kind, input, r + 1);
                if (!sources.add(input.getExternalNo()))
                    throw rowError(r + 1, HEADERS.get(0), "collection_duplicate");
                if ("RECEIVED".equals(kind) && !receivables.add(input.getReceivableId()))
                    throw rowError(r + 1, "应收明细ID", "collection_receivable_duplicate");
                result.add(new Line(r + 1, input));
            }
            if (result.isEmpty()) throw rowError(2, "文件", "collection_empty");
            return result;
        }
    }

    public static void validate(String kind, CollectionImportInput input, int row) {
        if (!KINDS.contains(Objects.toString(kind, "")) || input == null)
            throw rowError(row, "类型", "collection_field_invalid");
        var wrapper = new BeanWrapperImpl(input);
        for (var property : wrapper.getPropertyDescriptors()) {
            if (property.getPropertyType() == String.class
                    && wrapper.isWritableProperty(property.getName())) {
                var value = (String) wrapper.getPropertyValue(property.getName());
                if (value != null)
                    wrapper.setPropertyValue(
                            property.getName(), value.isBlank() ? null : value.trim());
            }
        }
        for (var field : FIELDS) {
            var value = wrapper.getPropertyValue(field);
            if (value instanceof String text
                    && text.length()
                            > (field.equals("fullName")
                                    ? 2000
                                    : Set.of("remark", "reductionReason").contains(field)
                                            ? 1000
                                            : 255))
                throw rowError(row, HEADERS.get(FIELDS.indexOf(field)), "collection_field_invalid");
        }
        require(input.getExternalNo(), row, 0);
        require(
                input.getCompanyId() == null ? input.getCompanyName() : input.getCompanyId(),
                row,
                6);
        require(input.getMethod() == null ? input.getMethodText() : input.getMethod(), row, 7);
        require(input.getPayerName(), row, 11);
        if (input.getQuantity() != null
                && (input.getQuantity().signum() <= 0
                        || input.getQuantity().scale() > 4
                        || input.getQuantity().precision() > 12))
            throw rowError(row, "数量", "collection_field_invalid");
        try {
            FundRules.money(input.getAmount());
        } catch (RuntimeException e) {
            throw rowError(row, HEADERS.get(9), "collection_field_invalid");
        }
        try {
            input.setPaidAt(parseTime(input.getPaidAt()).toString());
            if (LocalDateTime.parse(input.getPaidAt()).toLocalDate().isAfter(LocalDate.now()))
                throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw rowError(row, HEADERS.get(10), "collection_field_invalid");
        }
        if (input.getReductionAmount() != null) {
            if (!"RECEIVED".equals(kind)
                    || input.getReductionAmount().signum() < 0
                    || input.getReductionAmount().scale() > 2)
                throw rowError(row, "减免金额", "collection_field_invalid");
            if (input.getReductionAmount().signum() > 0)
                require(input.getReductionReason(), row, 18);
        }
        if ("RECEIVED".equals(kind)) require(input.getReceivableId(), row, 1);
        else {
            require(
                    input.getSubjectId() == null ? input.getSubjectName() : input.getSubjectId(),
                    row,
                    4);
            require(input.getSpaceId() == null ? input.getFullName() : input.getSpaceId(), row, 2);
            require(
                    input.getRelationshipId() == null
                            ? input.getCustomerName()
                            : input.getRelationshipId(),
                    row,
                    3);
            if (!"DEPOSIT".equals(kind)
                    || input.getStandardId() != null
                    || input.getStandardName() != null)
                require(
                        input.getStandardId() == null
                                ? input.getStandardName()
                                : input.getStandardId(),
                        row,
                        5);
            else
                require(
                        input.getSubjectId() == null
                                ? input.getSubjectName()
                                : input.getSubjectId(),
                        row,
                        4);
        }
        if (Set.of("ADVANCE", "TEMPORARY").contains(kind)
                || input.getStartDate() != null
                || input.getEndDate() != null) {
            try {
                if (LocalDate.parse(input.getStartDate())
                        .isAfter(LocalDate.parse(input.getEndDate())))
                    throw new IllegalArgumentException();
            } catch (RuntimeException e) {
                throw rowError(row, "计费开始日期/计费结束日期", "collection_field_invalid");
            }
        }
        if ("TEMPORARY".equals(kind)) {
            try {
                YearMonth.parse(input.getAccountPeriod());
            } catch (RuntimeException e) {
                throw rowError(row, HEADERS.get(14), "collection_field_invalid");
            }
        }
    }

    private static void require(String value, int row, int col) {
        if (value == null || value.isBlank())
            throw rowError(row, HEADERS.get(col), "collection_required");
    }

    private static String text(Row row, int col, DataFormatter fmt, int number) {
        if (row == null || row.getCell(col) == null) return "";
        var cell = row.getCell(col);
        if (cell.getCellType() == CellType.FORMULA || cell.getCellType() == CellType.ERROR)
            throw rowError(number, HEADERS.get(col), "collection_formula_denied");
        return fmt.formatCellValue(cell).trim();
    }

    public static BusinessException rowError(int row, String field, String reason) {
        return new BusinessException.NoStackTrace(
                "error.property_finance_collection_row",
                "error.property_finance_collection_row",
                400,
                row,
                field,
                org.hswebframework.web.i18n.LocaleUtils.resolveMessage(
                        "error.property_finance_" + reason, "error.property_finance_" + reason));
    }

    /** 数据页保持空白；脱敏示例独立在第二张表，不会作为实际收款导入。 */
    public static Flux<DataBuffer> template(
            String kind, Map<String, List<List<String>>> references) {
        var headers =
                HEADERS.stream().map(h -> new ExcelHeader(h, h, CellDataType.STRING)).toList();
        var refs = references;
        return Flux.defer(
                        () ->
                                ExcelUtils.write(
                                        headers,
                                        Flux.<Map<String, Object>>empty(),
                                        "xlsx",
                                        WorkbookOption.of(
                                                book -> {
                                                    var red = book.createFont();
                                                    red.setColor(IndexedColors.RED.getIndex());
                                                    red.setBold(true);
                                                    var required = book.createCellStyle();
                                                    required.setFont(red);
                                                    var data = book.createSheet("收款导入");
                                                    var text = book.createCellStyle();
                                                    text.setDataFormat(
                                                            book.createDataFormat().getFormat("@"));
                                                    var head = data.createRow(0);
                                                    var requiredColumns =
                                                            new HashSet<>(
                                                                    List.of(0, 6, 7, 9, 10, 11));
                                                    if ("RECEIVED".equals(kind))
                                                        requiredColumns.add(1);
                                                    else requiredColumns.addAll(List.of(2, 3, 4));
                                                    if (Set.of("ADVANCE", "TEMPORARY")
                                                            .contains(kind))
                                                        requiredColumns.addAll(List.of(5, 12, 13));
                                                    if ("TEMPORARY".equals(kind))
                                                        requiredColumns.add(14);
                                                    for (int c = 0; c < HEADERS.size(); c++) {
                                                        var cell = head.createCell(c);
                                                        cell.setCellValue(HEADERS.get(c));
                                                        if (requiredColumns.contains(c))
                                                            cell.setCellStyle(required);
                                                        data.setColumnWidth(
                                                                c, (c == 2 ? 48 : 23) * 256);
                                                        data.setDefaultColumnStyle(c, text);
                                                    }
                                                    data.createFreezePane(0, 1);
                                                    var example = book.createSheet("填写示例");
                                                    var eh = example.createRow(0);
                                                    var er = example.createRow(1);
                                                    var examples =
                                                            List.of(
                                                                    "示例-请勿导入",
                                                                    "请从应收参考表选择",
                                                                    "示例小区/1栋/1单元/101",
                                                                    "示例客户",
                                                                    "示例科目",
                                                                    "示例标准",
                                                                    "示例物业公司",
                                                                    "现金",
                                                                    "",
                                                                    "100.00",
                                                                    "2024-01-15 10:00",
                                                                    "示例缴款人",
                                                                    "2024-01-01",
                                                                    "2024-01-31",
                                                                    "2024-01",
                                                                    "1",
                                                                    "脱敏填写示例，不会自动导入",
                                                                    "0.00",
                                                                    "");
                                                    for (int c = 0; c < HEADERS.size(); c++) {
                                                        eh.createCell(c)
                                                                .setCellValue(HEADERS.get(c));
                                                        er.createCell(c)
                                                                .setCellValue(examples.get(c));
                                                        example.setColumnWidth(c, 25 * 256);
                                                    }
                                                    var instruction = book.createSheet("填写说明");
                                                    instruction
                                                            .createRow(0)
                                                            .createCell(0)
                                                            .setCellValue(
                                                                    "红色表头必填；只导入第一张表，最多500行；全文件校验通过才暂存，审批才入账。外部编号同项目/类型内不得重复。金额正数最多两位小数，不接受公式；同名多义必须先修正资料。押金可只填收费科目，其他非实收需收费标准；预收、临时必须填写计费起止，临时必须填写账期。收款时间格式"
                                                                        + " yyyy-MM-dd"
                                                                        + " HH:mm（精确到分钟）；审批后的款项统一待交账，不复刻旧系统已交账/已入账状态。实收仅需应收ID，房屋客户科目标准由该应收取得；预收/临时需科目和标准，押金科目必填标准选填。实收有减免时填写减免金额和原因。");
                                                    for (var ref : refs.entrySet()) {
                                                        var sheet = book.createSheet(ref.getKey());
                                                        for (int r = 0;
                                                                r < ref.getValue().size();
                                                                r++) {
                                                            var line = sheet.createRow(r);
                                                            for (int c = 0;
                                                                    c
                                                                            < ref.getValue()
                                                                                    .get(r)
                                                                                    .size();
                                                                    c++) {
                                                                line.createCell(c)
                                                                        .setCellValue(
                                                                                ref.getValue()
                                                                                        .get(r)
                                                                                        .get(c));
                                                                sheet.setColumnWidth(c, 30 * 256);
                                                            }
                                                        }
                                                    }
                                                    dropdown(book, data, refs, "应收参考", 0, 1);
                                                    dropdown(book, data, refs, "房屋客户参考", 0, 2);
                                                    dropdown(book, data, refs, "房屋客户参考", 1, 3);
                                                    dropdown(book, data, refs, "科目标准参考", 0, 4);
                                                    dropdown(book, data, refs, "科目标准参考", 1, 5);
                                                    dropdown(book, data, refs, "公司支付参考", 0, 6);
                                                    dropdown(book, data, refs, "公司支付参考", 1, 7);
                                                    dropdown(book, data, refs, "公司支付参考", 2, 8);
                                                })))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static void dropdown(
            Workbook book,
            Sheet data,
            Map<String, List<List<String>>> refs,
            String reference,
            int source,
            int target) {
        var rows = refs.get(reference);
        if (rows == null || rows.size() < 2) return;
        var name = book.createName();
        name.setNameName("reference_" + target);
        var letter = org.apache.poi.ss.util.CellReference.convertNumToColString(source);
        name.setRefersToFormula(
                "'" + reference + "'!$" + letter + "$2:$" + letter + "$" + rows.size());
        var helper = data.getDataValidationHelper();
        var validation =
                helper.createValidation(
                        helper.createFormulaListConstraint(name.getNameName()),
                        new CellRangeAddressList(1, LIMIT, target, target));
        validation.setShowErrorBox(true);
        data.addValidationData(validation);
    }

    private static LocalDateTime parseTime(String value) {
        if (value != null && value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}"))
            return LocalDateTime.parse(
                    value,
                    java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
                            .withResolverStyle(java.time.format.ResolverStyle.STRICT));
        return LocalDateTime.parse(value);
    }
}
