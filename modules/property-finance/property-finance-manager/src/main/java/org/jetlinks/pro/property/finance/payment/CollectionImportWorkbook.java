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

    public static final int LIMIT = FinanceExcelRows.LIMIT;
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
            var sheet = book.getSheetAt(0);
            var fmt = new DataFormatter(Locale.ROOT);
            if (sheet.getLastRowNum() > LIMIT) throw rowError(LIMIT + 2, "文件", "collection_limit");
            var header = new ArrayList<String>();
            for (var cell : sheet.getRow(0)) header.add(fmt.formatCellValue(cell));
            var seen = new HashSet<String>();
            var result = new ArrayList<Line>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                var row = sheet.getRow(r);
                if (row == null) continue;
                var values = new ArrayList<String>();
                for (int c = 0; c < header.size(); c++) {
                    var cell = row.getCell(c);
                    if (cell != null
                            && (cell.getCellType() == CellType.FORMULA
                                    || cell.getCellType() == CellType.ERROR))
                        throw rowError(r + 1, header.get(c), "collection_formula_denied");
                    var value = cell == null ? "" : fmt.formatCellValue(cell).trim();
                    if (cell != null
                            && cell.getCellType() == CellType.NUMERIC
                            && DateUtil.isCellDateFormatted(cell))
                        value = cell.getLocalDateTimeCellValue().toString();
                    values.add(value);
                }
                var decoded = decode(kind, header, new FinanceExcelRows.Row(r + 1, values), seen);
                if (decoded != null) result.add(decoded);
            }
            if (result.isEmpty()) throw rowError(2, "文件", "collection_empty");
            return result;
        }
    }

    public static Line decode(
            String kind, List<String> header, FinanceExcelRows.Row row, Set<String> seen) {
        var fields = CollectionImportSchema.fields(kind);
        boolean legacy = header.equals(HEADERS);
        if (!legacy
                && !header.equals(
                        fields.stream().map(CollectionImportSchema.Field::title).toList()))
            throw rowError(1, "表头", "collection_header_invalid");
        var keys =
                legacy ? FIELDS : fields.stream().map(CollectionImportSchema.Field::key).toList();
        var input = new CollectionImportInput();
        var wrap = new BeanWrapperImpl(input);
        if (row.cells().stream().allMatch(String::isBlank)) return null;
        for (int c = 0; c < keys.size(); c++) {
            var value = c < row.cells().size() ? row.cells().get(c) : "";
            if (value.isBlank() || keys.get(c).equals("arrears")) continue;
            try {
                if (Set.of("startDate", "endDate").contains(keys.get(c)) && value.contains("T"))
                    value = value.substring(0, 10);
                wrap.setPropertyValue(
                        keys.get(c),
                        Set.of("amount", "quantity", "reductionAmount").contains(keys.get(c))
                                ? new BigDecimal(value)
                                : value);
            } catch (RuntimeException e) {
                throw rowError(row.number(), header.get(c), "collection_field_invalid");
            }
        }
        // 兼容旧预填模板的空付款行；已填写支付信息却漏填金额时仍报错，不能悄悄丢行。
        if (!legacy
                && "RECEIVED".equals(kind)
                && input.getAmount() == null
                && input.getMethodText() == null
                && input.getPaidAt() == null
                && input.getExternalNo() == null
                && input.getReductionAmount() == null
                && input.getReductionReason() == null
                && input.getPayerName() == null
                && input.getRemark() == null) return null;
        validate(kind, input, row.number());
        if (input.getExternalNo() != null && !seen.add("NO:" + input.getExternalNo()))
            throw rowError(row.number(), "原收款单号", "collection_duplicate");
        if ("RECEIVED".equals(kind)) {
            var key =
                    input.getReceivableId() != null
                            ? input.getReceivableId()
                            : String.join(
                                    "|",
                                    input.getFullName(),
                                    input.getCustomerName(),
                                    input.getSubjectName(),
                                    input.getStandardName(),
                                    input.getStartDate(),
                                    input.getEndDate());
            if (!seen.add("BILL:" + key))
                throw rowError(row.number(), "房屋/客户/科目/标准/计费日期", "collection_receivable_duplicate");
        }
        return new Line(row.number(), input);
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
        require(input.getMethod() == null ? input.getMethodText() : input.getMethod(), row, 7);
        if (input.getPayerName() == null) input.setPayerName(input.getCustomerName());
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
        if (!"RECEIVED".equals(kind) || input.getReceivableId() == null) {
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
        if ((Set.of("ADVANCE", "TEMPORARY").contains(kind)
                        || "RECEIVED".equals(kind) && input.getReceivableId() == null)
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
        if ("TEMPORARY".equals(kind) && input.getAccountPeriod() == null)
            input.setAccountPeriod(input.getPaidAt().substring(0, 7));
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

    /** 四类模板仅填写表内一条脱敏示例；用户删除示例后填写，不预填真实账单。 */
    public static Flux<DataBuffer> template(String kind, Map<String, List<List<String>>> refs) {
        var fields = CollectionImportSchema.fields(kind);
        var headers =
                fields.stream()
                        .map(f -> new ExcelHeader(f.title(), f.title(), CellDataType.STRING))
                        .toList();
        return Flux.defer(
                        () ->
                                ExcelUtils.write(
                                        headers,
                                        Flux.<Map<String, Object>>empty(),
                                        "xlsx",
                                        WorkbookOption.of(
                                                book -> {
                                                    var font = book.createFont();
                                                    font.setColor(IndexedColors.RED.getIndex());
                                                    font.setBold(true);
                                                    var red = book.createCellStyle();
                                                    red.setFont(font);
                                                    var text = book.createCellStyle();
                                                    text.setDataFormat(
                                                            book.createDataFormat().getFormat("@"));
                                                    var sheet = book.createSheet("收款导入");
                                                    var head = sheet.createRow(0);
                                                    var er = sheet.createRow(1);
                                                    Map<String, String> examples =
                                                            Map.ofEntries(
                                                                    Map.entry(
                                                                            "fullName",
                                                                            "示例小区/1栋/1单元/101"),
                                                                    Map.entry(
                                                                            "customerName", "示例客户"),
                                                                    Map.entry("subjectName", "物业费"),
                                                                    Map.entry(
                                                                            "standardName",
                                                                            "住宅物业费标准"),
                                                                    Map.entry("amount", "100.00"),
                                                                    Map.entry(
                                                                            "paidAt",
                                                                            "2024-01-15 10:00"),
                                                                    Map.entry(
                                                                            "startDate",
                                                                            "2024-01-01"),
                                                                    Map.entry(
                                                                            "endDate",
                                                                            "2024-01-31"),
                                                                    Map.entry("methodText", "现金"),
                                                                    Map.entry(
                                                                            "remark",
                                                                            "脱敏示例，导入前请删除本行"));
                                                    for (int c = 0; c < fields.size(); c++) {
                                                        var f = fields.get(c);
                                                        var cell = head.createCell(c);
                                                        cell.setCellValue(f.title());
                                                        if (f.required()) cell.setCellStyle(red);
                                                        sheet.setColumnWidth(
                                                                c, (c == 0 ? 48 : 24) * 256);
                                                        sheet.setDefaultColumnStyle(c, text);
                                                        er.createCell(c)
                                                                .setCellValue(
                                                                        examples.getOrDefault(
                                                                                f.key(), ""));
                                                    }
                                                    sheet.createFreezePane(0, 1);
                                                    var instruction = book.createSheet("填写说明");
                                                    var notes =
                                                            List.of(
                                                                    "红色表头必填；只导入第一张表，最多50000行。整份文件校验通过后后台暂存，审核通过才入账。",
                                                                    "项目由页面带入；公司从应收、标准或项目配置取得，不需要填写内部编号。",
                                                                    "原收款单号选填；没有原单号时检查相同收款内容，疑似重复请先核对原导入记录。缴款人留空默认客户。",
                                                                    "收款时间格式：2024-01-15"
                                                                        + " 10:00，计费日期格式：2024-01-01。金额保留两位小数，禁止公式。",
                                                                    "模板第二行仅为脱敏示例，导入前请自行删除。实收按房屋、客户、科目、标准、计费起止唯一匹配。",
                                                                    "支付方式从下拉选择；转账选项包含账户名称，无需另填银行。减免大于0时必须填写原因。");
                                                    for (int r = 0; r < notes.size(); r++)
                                                        instruction
                                                                .createRow(r)
                                                                .createCell(0)
                                                                .setCellValue(notes.get(r));
                                                    instruction.setColumnWidth(0, 110 * 256);
                                                    for (var entry : refs.entrySet()) {
                                                        if (entry.getKey().equals("应收参考")) continue;
                                                        var ref = book.createSheet(entry.getKey());
                                                        for (int r = 0;
                                                                r < entry.getValue().size();
                                                                r++) {
                                                            var line = ref.createRow(r);
                                                            for (int c = 0;
                                                                    c
                                                                            < entry.getValue()
                                                                                    .get(r)
                                                                                    .size();
                                                                    c++) {
                                                                line.createCell(c)
                                                                        .setCellValue(
                                                                                entry.getValue()
                                                                                        .get(r)
                                                                                        .get(c));
                                                                ref.setColumnWidth(c, 30 * 256);
                                                            }
                                                        }
                                                    }
                                                    var mapping =
                                                            Map.of(
                                                                    "fullName",
                                                                    List.of("房屋客户参考", "0"),
                                                                    "customerName",
                                                                    List.of("房屋客户参考", "1"),
                                                                    "subjectName",
                                                                    List.of("科目标准参考", "0"),
                                                                    "standardName",
                                                                    List.of("科目标准参考", "1"),
                                                                    "methodText",
                                                                    List.of("支付方式参考", "0"));
                                                    for (int c = 0; c < fields.size(); c++) {
                                                        var f = fields.get(c);
                                                        var source = mapping.get(f.key());
                                                        if (source != null)
                                                            dropdown(
                                                                    book,
                                                                    sheet,
                                                                    refs,
                                                                    source.get(0),
                                                                    Integer.parseInt(source.get(1)),
                                                                    c);
                                                    }
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
        if (value != null && value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            return LocalDate.parse(value).atStartOfDay();
        return LocalDateTime.parse(value).withSecond(0).withNano(0);
    }
}
