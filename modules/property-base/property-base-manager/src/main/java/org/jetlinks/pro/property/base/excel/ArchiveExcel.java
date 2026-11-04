package org.jetlinks.pro.property.base.excel;

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

/** ExcelUtils 负责输出；读取使用其 POI 底层保留物理行号，仅解析首张数据表，禁止公式和静默跳行。 */
public final class ArchiveExcel {
    public static final int MAX_ROWS = 500;
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final List<String> SPACE =
            List.of("spaceId", "spaceName", "fullName", "spaceTypeName", "spaceLocation");
    public static final List<String> HOUSE =
            List.of(
                    "spaceId",
                    "houseType",
                    "houseState",
                    "billingArea",
                    "buildingArea",
                    "insideArea",
                    "deliveryDate",
                    "remark");
    public static final List<String> CUSTOMER =
            List.of(
                    "userId",
                    "customerType",
                    "name",
                    "telephone",
                    "email",
                    "address",
                    "certificateType",
                    "certificateNumber",
                    "creditCode",
                    "legalRepresentative",
                    "registeredAddress",
                    "spaceId",
                    "nature",
                    "startTime");
    private static final Map<String, String> HEADERS = new LinkedHashMap<>();
    private static final Map<String, String> GUIDES = new LinkedHashMap<>();
    private static final Set<String> NUMBERS = Set.of("billingArea", "buildingArea", "insideArea");
    private static final Set<String> DATES = Set.of("deliveryDate", "startTime", "endTime");

    static {
        HEADERS.put("completed", "房屋资料状态");
        HEADERS.put("spacePath", "完整空间路径");
        HEADERS.put("personName", "姓名");
        HEADERS.put("companyName", "企业名称");
        HEADERS.put("username", "用户账号");
        HEADERS.put("spaceId", "空间标识（请勿修改）");
        HEADERS.put("spaceName", "空间名称（只读）");
        HEADERS.put("fullName", "完整空间路径（只读）");
        HEADERS.put("spaceTypeName", "空间类型（只读）");
        HEADERS.put("spaceLocation", "空间层级（只读）");
        HEADERS.put("houseType", "房屋类型");
        HEADERS.put("houseState", "房态");
        HEADERS.put("billingArea", "计费面积（㎡）");
        HEADERS.put("buildingArea", "建筑面积（㎡）");
        HEADERS.put("insideArea", "套内面积（㎡）");
        HEADERS.put("deliveryDate", "交房日期");
        HEADERS.put("remark", "备注");
        HEADERS.put("userId", "用户标识");
        HEADERS.put("customerType", "客户类型");
        HEADERS.put("name", "姓名或企业名称");
        HEADERS.put("telephone", "联系电话");
        HEADERS.put("email", "电子邮箱");
        HEADERS.put("address", "联系地址");
        HEADERS.put("certificateType", "证件类型");
        HEADERS.put("certificateNumber", "证件号码");
        HEADERS.put("creditCode", "统一社会信用代码");
        HEADERS.put("legalRepresentative", "法定代表人");
        HEADERS.put("registeredAddress", "注册地址");
        HEADERS.put("nature", "客户性质");
        HEADERS.put("startTime", "开始时间");
        HEADERS.put("endTime", "结束时间");
        HEADERS.put("endReason", "结束原因");
        HEADERS.put("customerId", "客户标识");
        HEADERS.put("relationshipId", "关联标识");
        HEADERS.put("field", "字段");
        HEADERS.put("description", "填写说明");
        HEADERS.put("typeCode", "字典分类");
        HEADERS.put("value", "选项编码（系统使用）");
        HEADERS.put("text", "选项名称（可直接填写）");
        HEADERS.put("projectName", "所属项目");
        HEADERS.put("id", "档案标识");
        HEADERS.put("projectId", "项目标识");
        HEADERS.put("rootId", "根空间标识");
        HEADERS.put("version", "数据版本");
        HEADERS.put("deleted", "已删除");
        HEADERS.put("deletedTime", "删除时间");
        HEADERS.put("createTime", "创建时间");
        HEADERS.put("modifyTime", "修改时间");
        HEADERS.put("modifierId", "修改人标识");
        HEADERS.put("modifierName", "修改人");
        GUIDES.put("spaceId", "必填，复制空间参考中的空间标识");
        GUIDES.put("spacePath", "必填，填写或选择当前项目完整空间路径，例如园区/1栋/101；不创建空间，重名或不存在时拒绝");
        GUIDES.put("personName", "必填，按同步用户姓名匹配；重名再用联系电话区分，不存在或仍重名时拒绝");
        GUIDES.put("companyName", "必填，按同步用户企业名称匹配；重名再用联系电话区分，不存在或仍重名时拒绝");
        GUIDES.put("houseType", "必填，填写字典参考中的房屋类型名称，例如住宅");
        GUIDES.put("houseState", "必填，填写字典参考中的房态名称，例如自住");
        GUIDES.put("billingArea", "必填，大于0，最多2位小数");
        GUIDES.put("buildingArea", "选填，非负，最多2位小数");
        GUIDES.put("insideArea", "选填，非负，最多2位小数");
        GUIDES.put("deliveryDate", "选填，YYYY-MM-DD");
        GUIDES.put("remark", "选填，备注");
        GUIDES.put("userId", "必填，复制用户参考中的用户标识");
        GUIDES.put("customerType", "必填，填写个人或企业");
        GUIDES.put("name", "必填，姓名或企业名称");
        GUIDES.put("telephone", "必填，联系电话按文本填写");
        GUIDES.put("email", "选填，电子邮箱");
        GUIDES.put("address", "选填，联系地址");
        GUIDES.put("certificateType", "个人选填，填写证件类型名称；企业留空");
        GUIDES.put("certificateNumber", "个人选填；企业留空");
        GUIDES.put("creditCode", "企业选填，个人留空");
        GUIDES.put("legalRepresentative", "企业选填，个人留空");
        GUIDES.put("registeredAddress", "企业选填，个人留空");
        GUIDES.put("nature", "个人必填，填写客户性质名称，例如业主、租户、家人；企业留空");
        GUIDES.put("startTime", "系统自动记录开始时间，无需填写");
    }

    private ArchiveExcel() {}

    public static List<String> columns(boolean customer) {
        var keys = new LinkedHashSet<>(SPACE);
        keys.addAll(customer ? CUSTOMER : HOUSE);
        return List.copyOf(keys);
    }

    public record SheetData(String name, List<String> columns, List<Map<String, Object>> rows) {}

    public record Document(
            String filename,
            SheetData data,
            List<SheetData> references,
            Map<String, List<String>> options) {
        public Document(String filename, SheetData data, List<SheetData> references) {
            this(filename, data, references, Map.of());
        }
    }

    public static Flux<DataBuffer> write(Document document) {
        var headers =
                document.data().columns().stream()
                        .map(
                                key ->
                                        new ExcelHeader(
                                                key,
                                                header(key),
                                                NUMBERS.contains(key)
                                                        ? CellDataType.NUMBER
                                                        : CellDataType.STRING))
                        .toList();
        return Flux.defer(
                        () ->
                                ExcelUtils.write(
                                        headers,
                                        Flux.fromIterable(document.data().rows()),
                                        "xlsx",
                                        WorkbookOption.of(
                                                book -> configureWorkbook(book, document))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static void configureWorkbook(Workbook book, Document document) {
        configure(book.createSheet(document.data().name()), document.data().columns());
        for (var reference : document.references()) {
            var sheet = book.createSheet(reference.name());
            configure(sheet, reference.columns());
            var title = sheet.createRow(0);
            for (int c = 0; c < reference.columns().size(); c++)
                title.createCell(c).setCellValue(header(reference.columns().get(c)));
            for (int r = 0; r < reference.rows().size(); r++) {
                var row = sheet.createRow(r + 1);
                for (int c = 0; c < reference.columns().size(); c++) {
                    var value = reference.rows().get(r).get(reference.columns().get(c));
                    row.createCell(c, CellType.STRING)
                            .setCellValue(value == null ? "" : value.toString());
                }
            }
        }
        ArchiveDropdowns.configure(book, document);
    }

    private static void configure(Sheet sheet, List<String> columns) {
        sheet.createFreezePane(0, 1);
        var textStyle = sheet.getWorkbook().createCellStyle();
        textStyle.setDataFormat(sheet.getWorkbook().createDataFormat().getFormat("@"));
        for (int c = 0; c < columns.size(); c++) {
            String key = columns.get(c);
            sheet.setColumnWidth(
                    c,
                    ("spacePath".equals(key)
                                    ? 60
                                    : Math.min(60, Math.max(18, header(key).length() * 2 + 2)))
                            * 256);
            // 预设文本列，业务人员在Excel编辑标识、电话和证件时也不会丢前导零。
            if (!NUMBERS.contains(key)) sheet.setDefaultColumnStyle(c, textStyle);
        }
    }

    public static List<Map<String, Object>> read(byte[] bytes, boolean customer) {
        return read(bytes, columns(customer), customer ? CUSTOMER : HOUSE);
    }

    public static List<Map<String, Object>> read(byte[] bytes, List<String> columns) {
        return read(bytes, columns, columns);
    }

    private static List<Map<String, Object>> read(
            byte[] bytes, List<String> columns, List<String> required) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw failure("file");
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (book.getNumberOfSheets() == 0) throw failure("file");
            var sheet = book.getSheetAt(0);
            var title = sheet.getRow(0);
            if (title == null || title.getLastCellNum() > 80) throw failure("headers");
            Map<Integer, String> keys = new LinkedHashMap<>();
            var seen = new HashSet<String>();
            for (int c = 0; c < title.getLastCellNum(); c++) {
                var cell = title.getCell(c);
                if (cell == null || cell.getCellType() != CellType.STRING) throw failure("headers");
                String text = cell.getStringCellValue().trim();
                String key =
                        HEADERS.entrySet().stream()
                                .filter(e -> e.getValue().equals(text))
                                .map(Map.Entry::getKey)
                                .findFirst()
                                .orElse(null);
                if (key == null || !columns.contains(key) || !seen.add(key))
                    throw failure("headers");
                keys.put(c, key);
            }
            if (!seen.containsAll(required)) throw failure("headers");
            int last = sheet.getLastRowNum();
            while (last > 0 && blank(sheet.getRow(last), keys.size())) last--;
            if (last == 0 || last > MAX_ROWS) throw failure("limit");
            var result = new ArrayList<Map<String, Object>>();
            for (int r = 1; r <= last; r++) {
                var row = sheet.getRow(r);
                if (blank(row, keys.size())) throw cell(r + 1, "spacePath");
                for (int c = keys.size(); c < row.getLastCellNum(); c++) {
                    var extra = row.getCell(c);
                    if (extra != null
                            && extra.getCellType() != CellType.BLANK
                            && (extra.getCellType() != CellType.STRING
                                    || !extra.getStringCellValue().isBlank()))
                        throw failure("headers");
                }
                var values = new LinkedHashMap<String, Object>();
                for (var column : keys.entrySet()) {
                    var source = row.getCell(column.getKey());
                    String key = column.getValue();
                    if (source == null || source.getCellType() == CellType.BLANK) continue;
                    Object value;
                    if (source.getCellType() == CellType.FORMULA
                            || source.getCellType() == CellType.ERROR) throw cell(r + 1, key);
                    if (source.getCellType() == CellType.STRING) {
                        value = source.getStringCellValue().trim();
                    } else if (source.getCellType() == CellType.NUMERIC && NUMBERS.contains(key)) {
                        value =
                                new BigDecimal(
                                        org.apache.poi.ss.util.NumberToTextConverter.toText(
                                                source.getNumericCellValue()));
                    } else if (source.getCellType() == CellType.NUMERIC
                            && DATES.contains(key)
                            && DateUtil.isCellDateFormatted(source)) {
                        value =
                                source.getLocalDateTimeCellValue()
                                        .atZone(ZoneId.systemDefault())
                                        .toInstant()
                                        .toEpochMilli();
                    } else throw cell(r + 1, key);
                    if (!value.toString().isBlank()) values.put(key, value);
                }
                result.add(values);
            }
            return result;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw failure("file");
        }
    }

    private static boolean blank(Row row, int width) {
        if (row == null) return true;
        for (int c = 0; c < Math.max(width, row.getLastCellNum()); c++) {
            var cell = row.getCell(c);
            if (cell != null
                    && cell.getCellType() != CellType.BLANK
                    && (cell.getCellType() != CellType.STRING
                            || !cell.getStringCellValue().isBlank())) return false;
        }
        return true;
    }

    public static String text(Map<String, Object> values, String field, int row, boolean required) {
        var value = values.get(field);
        if (value == null || value.toString().isBlank()) {
            if (required) throw cell(row, field);
            return null;
        }
        return value.toString();
    }

    public static BigDecimal decimal(
            Map<String, Object> values, String field, int row, boolean required) {
        String text = text(values, field, row, required);
        if (text == null) return null;
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            throw cell(row, field);
        }
    }

    public static Long date(Map<String, Object> values, String field, int row, boolean required) {
        Object value = values.get(field);
        if (value instanceof Long time) return time;
        String text = text(values, field, row, required);
        if (text == null) return null;
        try {
            if (text.length() == 10)
                return LocalDate.parse(
                                text,
                                DateTimeFormatter.ofPattern("uuuu-MM-dd")
                                        .withResolverStyle(ResolverStyle.STRICT))
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
            if (text.length() == 19 && text.charAt(10) == ' ')
                return LocalDateTime.parse(
                                text,
                                DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                                        .withResolverStyle(ResolverStyle.STRICT))
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
            return OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (java.time.DateTimeException ex) {
            throw cell(row, field);
        }
    }

    public static String header(String key) {
        return HEADERS.getOrDefault(key, key);
    }

    public static String guide(String key) {
        return GUIDES.getOrDefault(key, "选填");
    }

    public static BusinessException cell(int row, String field) {
        return new BusinessException.NoStackTrace(
                "error.property_excel_cell", "error.property_excel_cell", 400, row, header(field));
    }

    public static BusinessException failure(String reason) {
        return new BusinessException.NoStackTrace("error.property_excel_" + reason, 400);
    }
}
