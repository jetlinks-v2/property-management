package org.jetlinks.pro.property.finance.tax;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hswebframework.web.exception.BusinessException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.*;

/** 保留旧工作簿表头和物理行号，全量验证后才返回写入计划，不跳过错误行。 */
public final class TaxWorkbook {
    private TaxWorkbook() {}

    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_ROWS = 500;
    public static final List<String> HEADERS =
            List.of(
                    "商品名称*",
                    "简称",
                    "税率*",
                    "规格型号",
                    "计量单位",
                    "单价",
                    "含税价标志",
                    "税收分类编码",
                    "税收分类编码名称",
                    "是否享受优惠政策",
                    "优惠政策名称");
    private static final List<String> FIELDS =
            List.of(
                    "goodsName",
                    "goodsShortName",
                    "taxRate",
                    "specAndTypes",
                    "unit",
                    "price",
                    "includingTaxFlag",
                    "goodsCode",
                    "classificationName",
                    "favouredPolicyFlag",
                    "favouredPolicyName");

    public static byte[] template() throws Exception {
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("税目");
            var row = sheet.createRow(0);
            for (int i = 0; i < HEADERS.size(); i++) {
                row.createCell(i).setCellValue(HEADERS.get(i));
                sheet.setColumnWidth(i, 24 * 256);
            }
            sheet.createFreezePane(0, 1);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    public static List<TaxItemInput> read(byte[] bytes, String companyId) {
        if (bytes.length > MAX_BYTES) throw error("tax_file_size", 400);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) throw error("tax_file_header", 400);
            var sheet = workbook.getSheetAt(0);
            var formatter = new DataFormatter();
            Row header = null;
            for (int i = sheet.getFirstRowNum(); i <= Math.min(sheet.getLastRowNum(), 9); i++) {
                var row = sheet.getRow(i);
                if (row != null
                        && headerIndexes(row, formatter).containsKey("商品名称")
                        && headerIndexes(row, formatter).containsKey("税率")) {
                    header = row;
                    break;
                }
            }
            if (header == null) throw error("tax_file_header", 400);
            var indexes = headerIndexes(header, formatter);
            for (var title : HEADERS)
                if (!indexes.containsKey(normalize(title))) throw error("tax_file_header", 400);
            var result = new ArrayList<TaxItemInput>();
            var keys = new HashMap<String, Integer>();
            var failures = new ArrayList<String>();
            var invalidRows = new HashSet<Integer>();
            int totalCount = 0;
            if (sheet.getLastRowNum() - header.getRowNum() > MAX_ROWS)
                throw error("tax_file_rows", 400);
            for (int i = header.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                var row = sheet.getRow(i);
                if (row == null) continue;
                var values = new LinkedHashMap<String, String>();
                boolean invalidFormula = false;
                for (int c = 0; c < HEADERS.size(); c++) {
                    var cell = row.getCell(indexes.get(normalize(HEADERS.get(c))));
                    if (cell != null
                            && (cell.getCellType() == CellType.FORMULA
                                    || cell.getCellType() == CellType.ERROR)) {
                        failures.add((i + 1) + ":" + HEADERS.get(c) + ":formula");
                        invalidRows.add(i + 1);
                        invalidFormula = true;
                    }
                    var value =
                            cell == null
                                    ? ""
                                    : cell.getCellType() == CellType.NUMERIC
                                            ? BigDecimal.valueOf(cell.getNumericCellValue())
                                                    .stripTrailingZeros()
                                                    .toPlainString()
                                            : formatter.formatCellValue(cell).trim();
                    values.put(FIELDS.get(c), value);
                }
                if (!invalidFormula && values.values().stream().allMatch(String::isBlank)) continue;
                totalCount++;
                if (invalidFormula) continue;
                try {
                    var input = new TaxItemInput();
                    input.setCompanyId(companyId);
                    var wrapper = new org.springframework.beans.BeanWrapperImpl(input);
                    for (var field : TaxItemRules.FIELDS.keySet())
                        wrapper.setPropertyValue(field, values.get(field));
                    input.setTaxRate(decimal(values, "taxRate", i + 1));
                    input.setPrice(decimal(values, "price", i + 1));
                    input.setIncludingTaxFlag(flag(values, "includingTaxFlag", i + 1));
                    input.setFavouredPolicyFlag(flag(values, "favouredPolicyFlag", i + 1));
                    var valid = TaxItemRules.validate(input);
                    input.setPrice(valid.getPrice());
                    var previous = keys.putIfAbsent(valid.getMatchKey(), i + 1);
                    if (previous != null) {
                        failures.add((i + 1) + ":税目:duplicate");
                        invalidRows.add(i + 1);
                        if (invalidRows.add(previous)) failures.add(previous + ":税目:duplicate");
                    }
                    result.add(input);
                } catch (BusinessException failure) {
                    invalidRows.add(i + 1);
                    failures.add(
                            (i + 1)
                                    + ":"
                                    + org.hswebframework.web.i18n.LocaleUtils.resolveMessage(
                                            failure.getMessage(),
                                            failure.getMessage(),
                                            failure.getArgs()));
                }
            }
            if (!failures.isEmpty())
                throw new BusinessException.NoStackTrace(
                        "error.property_finance_tax_file_cells",
                        "error.property_finance_tax_file_cells",
                        400,
                        String.join("; ", failures),
                        totalCount,
                        invalidRows.size());
            if (result.isEmpty()) throw error("tax_file_empty", 400);
            return result;
        } catch (BusinessException failure) {
            throw failure;
        } catch (Exception failure) {
            throw error("tax_file_format", 400);
        }
    }

    private static BigDecimal decimal(Map<String, String> row, String field, int number) {
        var value = row.get(field);
        if (value.isBlank()) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException failure) {
            throw new BusinessException.NoStackTrace(
                    "error.property_finance_tax_file_cell",
                    "error.property_finance_tax_file_cell",
                    400,
                    number,
                    field);
        }
    }

    private static Integer flag(Map<String, String> row, String field, int number) {
        var value = row.get(field);
        if (value.isBlank()) return 0;
        if (value.equals("0") || value.equals("1")) return Integer.valueOf(value);
        throw new BusinessException.NoStackTrace(
                "error.property_finance_tax_file_cell",
                "error.property_finance_tax_file_cell",
                400,
                number,
                field);
    }

    private static Map<String, Integer> headerIndexes(Row row, DataFormatter formatter) {
        var indexes = new LinkedHashMap<String, Integer>();
        for (var cell : row)
            indexes.putIfAbsent(normalize(formatter.formatCellValue(cell)), cell.getColumnIndex());
        return indexes;
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", "").replaceFirst("\\*$", "");
    }
}
