package org.jetlinks.pro.property.base.excel;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;

import java.util.*;

/** 命名区域避免Excel显式列表255字符限制。 */
final class ArchiveDropdowns {
    private ArchiveDropdowns() {}

    static void configure(Workbook book, ArchiveExcel.Document document) {
        if (document.options().isEmpty()) return;
        Sheet source = book.createSheet("_导入选项");
        var entries = new ArrayList<>(document.options().entrySet());
        int count = entries.stream().mapToInt(entry -> entry.getValue().size()).max().orElse(0);
        // SXSSF会刷出旧行，须按行写完所有选项列，不能写完一列再回到首行。
        for (int row = 0; row < Math.max(1, count); row++) {
            var current = source.createRow(row);
            for (int col = 0; col < entries.size(); col++) {
                var values = entries.get(col).getValue();
                if (row < values.size())
                    current.createCell(col, CellType.STRING).setCellValue(values.get(row));
                else if (row == 0) current.createCell(col, CellType.STRING).setCellValue("");
            }
        }
        int column = 0;
        for (var entry : document.options().entrySet()) {
            String name = "_option_" + column;
            range(book, column++, name, entry.getValue());
            int target = document.data().columns().indexOf(entry.getKey());
            if (target >= 0) validation(book.getSheetAt(0), target, name, true);
        }
        book.setSheetVisibility(book.getSheetIndex(source), SheetVisibility.VERY_HIDDEN);
    }

    private static void range(Workbook book, int column, String name, List<String> values) {
        Name range = book.createName();
        range.setNameName(name);
        range.setRefersToFormula(
                "'_导入选项'!$"
                        + letter(column)
                        + "$1:$"
                        + letter(column)
                        + "$"
                        + Math.max(1, values.size()));
    }

    private static String letter(int column) {
        return CellReference.convertNumToColString(column);
    }

    private static void validation(Sheet sheet, int column, String formula, boolean rejectInvalid) {
        var helper = sheet.getDataValidationHelper();
        var validation =
                helper.createValidation(
                        helper.createFormulaListConstraint(formula),
                        new CellRangeAddressList(
                                1, SpreadsheetVersion.EXCEL2007.getLastRowIndex(), column, column));
        validation.setEmptyCellAllowed(true);
        validation.setShowErrorBox(rejectInvalid);
        validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
        validation.createErrorBox("选项无效", "请从下拉选项中选择；导入时将再次校验。");
        sheet.addValidationData(validation);
    }
}
