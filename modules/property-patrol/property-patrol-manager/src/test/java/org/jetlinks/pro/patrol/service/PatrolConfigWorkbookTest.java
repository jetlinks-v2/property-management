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
        level.setProjectName("项目甲");
        level.setRule(new PatrolRule(Map.of(), Map.of(LocalDate.of(2026, 11, 4), List.of(
                new PatrolRule.Window(1320, 120, 60, 2, 15, "2", "1")), LocalDate.of(2026, 11, 5), List.of()), 30));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(PatrolConfigWorkbook.write(PatrolAssetType.patrolLevel, List.of(level))))) {
            assertEquals(CellType.STRING, book.getSheetAt(0).getRow(1).getCell(0).getCellType());
            assertEquals("所属项目", book.getSheetAt(0).getRow(0).getCell(5).getStringCellValue());
            assertEquals("项目甲", book.getSheetAt(0).getRow(1).getCell(5).getStringCellValue());
            var summary = book.getSheetAt(0).getRow(1).getCell(6).getStringCellValue();
            assertEquals(String.join("\n", level.getRuleSummary()), summary);
            assertTrue(summary.contains("2026-11-04：22:00–02:00（次日） 每 1 小时 巡更 2 次，每次至少间隔 15 分钟"));
            assertTrue(summary.contains("2026-11-05：不巡更"));
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
            assertEquals("项目甲", rules.getRow(1).getCell(9).getStringCellValue());
            assertEquals("项目甲", rules.getRow(2).getCell(9).getStringCellValue());
        }
    }

    @Test
    void frequencyExportUsesTheListFieldWithMergedWeekdaysAndOriginalUnits() throws Exception {
        var level = new PatrolLevelEntity(); level.setName("白班");
        var window = new PatrolRule.Window(480, 1080, 120, 1, 60, "2", "2");
        level.setRule(new PatrolRule(Map.of(1, List.of(window), 2, List.of(window),
                3, List.of(window), 4, List.of(window), 5, List.of(window)), Map.of(), 0));
        assertEquals(List.of("周一、周二、周三、周四、周五：08:00–18:00 每 2 小时 巡更 1 次，每次至少间隔 1 小时"), level.getRuleSummary());
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(PatrolConfigWorkbook.write(PatrolAssetType.patrolLevel, List.of(level))))) {
            assertEquals(String.join("\n", level.getRuleSummary()), book.getSheetAt(0).getRow(1).getCell(6).getStringCellValue());
        }
        // 派生字段不可写入；只更新 rule，展示值会随之更新，不保留过期说明。
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var decoded = mapper.readValue("{\"name\":\"Empty\",\"ruleSummary\":[\"forged description\"]}", PatrolLevelEntity.class);
        assertEquals(List.of("未配置巡更时段"), decoded.getRuleSummary());
        level.setRule(new PatrolRule(Map.of(1, List.of(new PatrolRule.Window(0, 0, 1440, 1))), Map.of(), 60));
        assertEquals(List.of("周一：00:00–00:00（全天） 每 1 天 巡更 1 次，每次至少间隔 1 小时"), level.getRuleSummary());
    }

    @Test
    void standardsKeepItemTypesExactDecimalOptionsAndLiteralText() throws Exception {
        var standard = new PatrolStandardEntity(); standard.setName("Water"); standard.setProjectName("项目乙");
        standard.setItems(List.of(new PatrolStandardEntity.CheckItem("i", "=1+1", true, List.of("A", "B"),
                PatrolStandardEntity.ItemType.number, new BigDecimal("999999999999999999.000001"), null,
                "Check pressure", List.of("A"), true, "private-image")));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(PatrolConfigWorkbook.write(PatrolAssetType.patrolStandard, List.of(standard))))) {
            var row = book.getSheet("检查项目").getRow(1);
            assertEquals("数值", row.getCell(3).getStringCellValue());
            assertEquals("是", row.getCell(4).getStringCellValue());
            assertEquals("是", row.getCell(5).getStringCellValue());
            assertEquals("", row.getCell(6).getStringCellValue()); // 数值题不显示历史残留的选项配置。
            assertEquals("999999999999999999.000001", row.getCell(8).getStringCellValue());
            assertEquals(CellType.STRING, row.getCell(2).getCellType());
            assertEquals("项目乙", row.getCell(11).getStringCellValue());
            assertEquals("项目乙", book.getSheetAt(0).getRow(1).getCell(5).getStringCellValue());
            assertEquals("1、检查项名称：=1+1\n检查项类型：数值\n数值下限：999999999999999999.000001\n数值上限：未设置\n必填：是\n必拍：是\n参考说明：Check pressure\n",
                    book.getSheetAt(0).getRow(1).getCell(6).getStringCellValue());
            assertEquals(12, row.getLastCellNum()); // 不导出未经另行授权的附件内容或链接。
        }
    }

    @Test
    void standardSummaryNumbersItemsAndShowsOnlyFieldsForTheirTypes() throws Exception {
        var standard = new PatrolStandardEntity(); standard.setName("综合检查");
        standard.setItems(List.of(
                new PatrolStandardEntity.CheckItem("choice", "门锁", true, List.of("正常", "异常"),
                        PatrolStandardEntity.ItemType.choice, BigDecimal.ONE, BigDecimal.TEN, null, List.of("正常"), false, null),
                new PatrolStandardEntity.CheckItem("text", "现场说明", false, List.of(),
                        PatrolStandardEntity.ItemType.text, null, null, "记录实际情况", List.of(), false, null),
                new PatrolStandardEntity.CheckItem("image", "现场照片", true, List.of(),
                        PatrolStandardEntity.ItemType.image, null, null, null, List.of(), false, null)));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(PatrolConfigWorkbook.write(PatrolAssetType.patrolStandard, List.of(standard))))) {
            var row = book.getSheetAt(0).getRow(1);
            var text = row.getCell(6).getStringCellValue();
            assertTrue(text.startsWith("1、检查项名称：门锁\n检查项类型：选项\n选项内容：正常、异常\n合格选项：正常\n"));
            assertTrue(text.contains("\n\n2、检查项名称：现场说明\n检查项类型：文本\n必填：否\n必拍：否\n参考说明：记录实际情况\n"));
            assertTrue(text.contains("\n\n3、检查项名称：现场照片\n检查项类型：图像\n"));
            assertFalse(text.contains("数值下限")); assertFalse(text.contains("数值上限"));
            assertTrue(row.getCell(6).getCellStyle().getWrapText());
            assertTrue(row.getHeightInPoints() > book.getSheetAt(0).getDefaultRowHeightInPoints());
            var items = book.getSheet("检查项目");
            assertEquals(3, items.getLastRowNum());
            assertEquals("正常、异常", items.getRow(1).getCell(6).getStringCellValue());
            assertEquals("", items.getRow(1).getCell(8).getStringCellValue());
            assertEquals("", items.getRow(2).getCell(6).getStringCellValue());
            assertEquals("记录实际情况", items.getRow(2).getCell(10).getStringCellValue());
        }
    }

    @Test
    void streamingRowsBeyondWindowAndEmptySheetsKeepHeadersAndAllRows() throws Exception {
        var tags = java.util.stream.IntStream.range(0, 1000).mapToObj(index -> {
            var tag = new PatrolTagEntity(); tag.setName("Tag" + index); tag.setProjectName("标签项目"); return tag;
        }).toList();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(PatrolConfigWorkbook.write(PatrolAssetType.patrolTag, tags)))) {
            assertEquals(1000, book.getSheetAt(0).getLastRowNum());
            assertEquals("标签项目", book.getSheetAt(0).getRow(1000).getCell(4).getStringCellValue());
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
