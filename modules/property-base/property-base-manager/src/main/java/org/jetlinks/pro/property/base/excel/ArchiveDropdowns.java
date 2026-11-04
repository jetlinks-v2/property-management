package org.jetlinks.pro.property.base.excel;

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
        int column = 0;
        for (var entry : document.options().entrySet()) {
            String name = "_option_" + column;
            range(book, source, column++, name, entry.getValue());
            int target = document.data().columns().indexOf(entry.getKey());
            if (target >= 0) validation(book.getSheetAt(0), target, name, true);
        }
        book.setSheetVisibility(book.getSheetIndex(source), SheetVisibility.VERY_HIDDEN);
    }

    private static void range(
            Workbook book, Sheet sheet, int column, String name, List<String> values) {
        for (int row = 0; row < Math.max(1, values.size()); row++)
            cell(sheet, row, column).setCellValue(values.isEmpty() ? "" : values.get(row));
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

    private static Cell cell(Sheet sheet, int row, int column) {
        Row current = sheet.getRow(row);
        if (current == null) current = sheet.createRow(row);
        return current.createCell(column, CellType.STRING);
    }

    private static String letter(int column) {
        return CellReference.convertNumToColString(column);
    }

    private static void validation(Sheet sheet, int column, String formula, boolean rejectInvalid) {
        var helper = sheet.getDataValidationHelper();
        var validation =
                helper.createValidation(
                        helper.createFormulaListConstraint(formula),
                        new CellRangeAddressList(1, ArchiveExcel.MAX_ROWS, column, column));
        validation.setEmptyCellAllowed(true);
        validation.setShowErrorBox(rejectInvalid);
        validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
        validation.createErrorBox("选项无效", "请从下拉选项中选择；导入时将再次校验。");
        sheet.addValidationData(validation);
    }
}
