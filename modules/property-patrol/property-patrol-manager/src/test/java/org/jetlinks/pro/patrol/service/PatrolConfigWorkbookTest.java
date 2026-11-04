package org.jetlinks.pro.patrol.service;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.domain.PatrolRule;
import org.jetlinks.pro.patrol.entity.PatrolLevelEntity;
import org.jetlinks.pro.patrol.entity.PatrolStandardEntity;
import org.jetlinks.pro.patrol.entity.PatrolTagEntity;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PatrolConfigWorkbookTest {
    @Test
    void rulesIncludeCrossDayPerWindowCooldownAndAdvancedOnlyRestWithoutTruncation() throws Exception {
        var level = new PatrolLevelEntity(); level.setName("=SUM(1,2)"); level.setModifierName("=Actor()");
        level.setRule(new PatrolRule(Map.of(), Map.of(LocalDate.of(2026, 11, 4), List.of(
                new PatrolRule.Window(1320, 120, 60, 2, 15, "2", "1")), LocalDate.of(2026, 11, 5), List.of()), 30));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(PatrolConfigWorkbook.write(PatrolAssetType.patrolLevel, List.of(level))))) {
            assertEquals(CellType.STRING, book.getSheetAt(0).getRow(1).getCell(0).getCellType());
            assertEquals("编辑人", book.getSheetAt(0).getRow(0).getCell(2).getStringCellValue());
            assertEquals("=Actor()", book.getSheetAt(0).getRow(1).getCell(2).getStringCellValue());
            assertEquals(CellType.STRING, book.getSheetAt(0).getRow(1).getCell(2).getCellType());
            var rules = book.getSheet("巡更规则"); assertEquals(2, rules.getLastRowNum());
            assertEquals("2026-11-04", rules.getRow(1).getCell(1).getStringCellValue());
            assertEquals("22:00", rules.getRow(1).getCell(3).getStringCellValue());
            assertEquals("02:00", rules.getRow(1).getCell(4).getStringCellValue());
            assertEquals("次日", rules.getRow(1).getCell(5).getStringCellValue());
            assertEquals(15, rules.getRow(1).getCell(8).getNumericCellValue());
            assertEquals("不巡更", rules.getRow(2).getCell(2).getStringCellValue());
        }
    }

    @Test
    void standardsKeepItemTypesExactDecimalOptionsAndLiteralText() throws Exception {
        var standard = new PatrolStandardEntity(); standard.setName("Water");
        standard.setItems(List.of(new PatrolStandardEntity.CheckItem("i", "=1+1", true, List.of("A", "B"),
                PatrolStandardEntity.ItemType.number, new BigDecimal("999999999999999999.000001"), null,
                "Check pressure", List.of("A"), true, "private-image")));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(PatrolConfigWorkbook.write(PatrolAssetType.patrolStandard, List.of(standard))))) {
            var row = book.getSheet("检查项目").getRow(1);
            assertEquals("数值", row.getCell(3).getStringCellValue());
            assertEquals("是", row.getCell(4).getStringCellValue());
            assertEquals("是", row.getCell(5).getStringCellValue());
            assertEquals("A、B", row.getCell(6).getStringCellValue());
            assertEquals("999999999999999999.000001", row.getCell(8).getStringCellValue());
            assertEquals(CellType.STRING, row.getCell(2).getCellType());
            assertEquals(11, row.getLastCellNum()); // 不导出未经另行授权的附件内容或链接。
        }
    }

    @Test
    void streamingRowsBeyondWindowAndEmptySheetsKeepHeadersAndAllRows() throws Exception {
        var tags = java.util.stream.IntStream.range(0, 1000).mapToObj(index -> {
            var tag = new PatrolTagEntity(); tag.setName("Tag" + index); return tag;
        }).toList();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(PatrolConfigWorkbook.write(PatrolAssetType.patrolTag, tags)))) {
            assertEquals(1000, book.getSheetAt(0).getLastRowNum());
            assertEquals("Tag999", book.getSheetAt(0).getRow(1000).getCell(0).getStringCellValue());
        }
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(PatrolConfigWorkbook.write(PatrolAssetType.patrolTag, List.of())))) {
            assertEquals("标签名称", book.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals(0, book.getSheetAt(0).getLastRowNum());
        }
    }

    @Test
    void oversizedDetailExportFailsBeforeCreatingWorkbookRatherThanTruncating() {
        var standard = new PatrolStandardEntity(); standard.setName("Many items");
        standard.setItems(java.util.stream.IntStream.range(0, 100).mapToObj(index ->
                new PatrolStandardEntity.CheckItem("item" + index, "Check", true, List.of("Yes"))).toList());
        var rows = java.util.Collections.nCopies(1001, standard);
        var error = assertThrows(org.hswebframework.web.exception.BusinessException.class,
                () -> PatrolConfigWorkbook.write(PatrolAssetType.patrolStandard, rows));
        assertEquals("error.patrol.config.export_detail_limit", error.getMessage());
    }
}
