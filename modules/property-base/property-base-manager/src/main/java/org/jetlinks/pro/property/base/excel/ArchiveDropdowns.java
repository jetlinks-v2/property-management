package org.jetlinks.pro.property.base.excel;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;

import java.util.*;

/** 命名区域避免Excel显式列表255字符限制；家庭列表按完整空间路径联动。 */
final class ArchiveDropdowns {
    private ArchiveDropdowns() {}

    static void configure(Workbook book, ArchiveExcel.Document document) {
        if (document.options().isEmpty() && document.families().isEmpty()) return;
        Sheet source = book.createSheet("_导入选项");
        int column = 0;
        for (var entry : document.options().entrySet()) {
            String name = "_option_" + column;
            range(book, source, column++, name, entry.getValue());
            int target = document.data().columns().indexOf(entry.getKey());
            if (target >= 0) validation(book.getSheetAt(0), target, name, true);
        }
        int familyTarget = document.data().columns().indexOf("familyName");
        if (familyTarget >= 0) {
            range(book, source, column++, "_empty_family", List.of());
            int mapColumn = column;
            column += 2;
            int index = 0;
            for (var entry : document.families().entrySet()) {
                String name = "_family_" + index;
                cell(source, index, mapColumn).setCellValue(entry.getKey());
                cell(source, index, mapColumn + 1).setCellValue(name);
                range(book, source, column++, name, entry.getValue());
                index++;
            }
            String map =
                    "'_导入选项'!$"
                            + letter(mapColumn)
                            + "$1:$"
                            + letter(mapColumn + 1)
                            + "$"
                            + Math.max(1, index);
            // 验证公式通过命名区域跨表取值，兼容Excel对数据验证跨表引用的限制。
            Name lookup = book.createName();
            lookup.setNameName("_family_map");
            lookup.setRefersToFormula(map);
            String path = "$" + letter(document.data().columns().indexOf("spacePath")) + "2";
            String mode = "$" + letter(document.data().columns().indexOf("familyMode")) + "2";
            String formula =
                    "IF("
                            + mode
                            + "=\"加入已有家庭\",INDIRECT(IFERROR(VLOOKUP("
                            + path
                            + ",_family_map,2,FALSE),\"_empty_family\")),_empty_family)";
            // 新建时自由输入；加入已有家庭的名称与状态由后端再次验证。
            validation(book.getSheetAt(0), familyTarget, formula, false);
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
