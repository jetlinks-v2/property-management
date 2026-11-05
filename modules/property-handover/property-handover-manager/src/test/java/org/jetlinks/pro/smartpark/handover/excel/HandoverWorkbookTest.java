package org.jetlinks.pro.smartpark.handover.excel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.smartpark.handover.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;

import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

class HandoverWorkbookTest {
    @Test
    void stageTemplatesMatchFormsAndDisclosureUsesTextCategoryAndReceiver() throws Exception {
        byte[] data =
                bytes(
                        HandoverWorkbook.template(
                                "record", "disclosure", Map.of("receiverName", List.of("接收员"))));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            var header = book.getSheetAt(0).getRow(0);
            var titles = new java.util.ArrayList<String>();
            header.forEach(cell -> titles.add(cell.getStringCellValue()));
            assertTrue(titles.containsAll(List.of("交底分类", "交底人", "交底接收人")));
            assertFalse(titles.contains("资料分类"));
            var col = header.getCell(titles.indexOf("交底分类"));
            assertEquals(
                    IndexedColors.RED.getIndex(),
                    book.getFontAt(col.getCellStyle().getFontIndex()).getColor());
        }
        var user =
                new org.jetlinks.pro.property.base.user.PropertyEmployeeInfo(
                        "receiver", "接收员", null, List.of());
        var row =
                new HandoverWorkbook.ImportRow(
                        3,
                        Map.of(
                                "title",
                                "消防交底",
                                "receiverName",
                                "接收员",
                                "properties.disclosureCategory",
                                "消防",
                                "properties.disclosureUserName",
                                "工程师",
                                "time",
                                "2026-11-04"),
                        List.of());
        var record =
                (HandoverRecordEntity)
                        HandoverImportMapper.map(
                                        "record",
                                        "project",
                                        null,
                                        "disclosure",
                                        row,
                                        new HandoverImportCatalog.Catalog(
                                                List.of(), List.of(), List.of(), List.of(user)))
                                .value();
        assertEquals("receiver", record.getProperties().get("receiverId"));
        assertEquals("消防", record.getProperties().get("disclosureCategory"));
        assertNull(record.getCategoryId());
    }

    @Test
    void equipmentCategoryIsFreeTextAndKnownFieldsRemainIndependent() {
        var catalog = new HandoverImportCatalog.Catalog(List.of(), List.of(), List.of(), List.of());
        var values = new java.util.HashMap<String, String>();
        values.put("name", "消防水泵");
        values.put("categoryName", "消防设备");
        values.put("controlArea", "A组团");
        values.put("mainParameters", "75kW 中文参数");
        values.put("productionDate", "2025-03-01");
        values.put("installationDate", "2025-04-01");
        values.put("supplier", "设备供应公司");
        values.put("quantity", "2.125");
        var mapped =
                HandoverImportMapper.map(
                        "equipment",
                        "project",
                        null,
                        null,
                        new HandoverWorkbook.ImportRow(2, values, List.of()),
                        catalog);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(mapped.value());
        assertEquals("消防设备", json.path("categoryName").asText());
        assertEquals("A组团", json.path("controlArea").asText());
        assertEquals("75kW 中文参数", json.path("mainParameters").asText());
        assertEquals("设备供应公司", json.path("supplier").asText());
        assertTrue(json.path("productionDate").asLong() < json.path("installationDate").asLong());
        assertEquals(2.125, json.path("quantity").asDouble());
    }

    @Test
    void templateHasEmptyDataRedRequiredExampleAndRealNamedDropdown() {
        byte[] bytes =
                bytes(
                        HandoverWorkbook.template(
                                "problem", Map.of("categoryName", List.of("公共区域/给排水"))));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var data = book.getSheetAt(0);
            assertEquals("导入数据", data.getSheetName());
            assertEquals(0, data.getLastRowNum(), "真实导入表不能预填业务行");
            assertEquals("问题描述", data.getRow(0).getCell(0).getStringCellValue());
            var font = book.getFontAt(data.getRow(0).getCell(0).getCellStyle().getFontIndex());
            assertEquals(IndexedColors.RED.getIndex(), font.getColor());
            assertEquals(1, data.getDataValidations().size());
            String name = data.getDataValidations().get(0).getValidationConstraint().getFormula1();
            assertNotNull(book.getName(name));
            assertEquals(
                    "公共区域/给排水", book.getSheet("选项参考").getRow(0).getCell(0).getStringCellValue());
            assertTrue(book.isSheetHidden(book.getSheetIndex("选项参考")));
            assertNotNull(book.getSheet("填写示例（不导入）").getRow(1));
            var error =
                    assertThrows(
                            HandoverImportException.class,
                            () -> HandoverWorkbook.read(bytes, "problem"));
            assertEquals(
                    2,
                    error.getErrors().get(0).row(),
                    error.getErrors() + "; cause=" + error.getCause());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void invalidSecondRowReportsVisibleRowWithoutAcceptingFirstRow() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("问题描述");
            sheet.createRow(1).createCell(0).setCellValue("有效的第一条");
            var title = sheet.getRow(0);
            title.createCell(1).setCellValue("位置");
            sheet.createRow(2).createCell(1).setCellValue("有位置但缺必填描述");
            var error =
                    assertThrows(
                            HandoverImportException.class,
                            () -> HandoverWorkbook.read(serialize(book), "problem"));
            assertEquals(3, error.getErrors().get(0).row());
            assertEquals("问题描述", error.getErrors().get(0).column());
        }
    }

    @Test
    void floatingPictureBelongsToVisibleProblemRow() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("问题描述");
            sheet.getRow(0).createCell(1).setCellValue("问题图片");
            sheet.createRow(1).createCell(0).setCellValue("渗漏问题");
            byte[] image =
                    java.util.Base64.getDecoder()
                            .decode(
                                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=");
            int index = book.addPicture(image, Workbook.PICTURE_TYPE_PNG);
            var anchor = book.getCreationHelper().createClientAnchor();
            anchor.setRow1(1);
            anchor.setCol1(1);
            sheet.createDrawingPatriarch().createPicture(anchor, index);
            var rows = HandoverWorkbook.read(serialize(book), "problem");
            assertEquals(1, rows.size());
            assertEquals(2, rows.get(0).row());
            assertEquals(1, rows.get(0).images().size());
            assertArrayEquals(image, rows.get(0).images().get(0).bytes());
        }
    }

    @Test
    void formulasCannotExecuteOrSilentlyImportCalculatedValues() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("问题描述");
            sheet.createRow(1).createCell(0).setCellFormula("1+1");
            var error =
                    assertThrows(
                            HandoverImportException.class,
                            () -> HandoverWorkbook.read(serialize(book), "problem"));
            assertTrue(error.getErrors().get(0).message().contains("公式"));
        }
    }

    @Test
    void duplicateLookupNamesFailAndFullCategoryPathResolves() {
        var first = new HandoverCategoryEntity();
        first.setId("c1");
        first.setName("给排水");
        var second = new HandoverCategoryEntity();
        second.setId("c2");
        second.setName("给排水");
        var catalog =
                new HandoverImportCatalog.Catalog(
                        List.of(first, second), List.of(), List.of(), List.of());
        var row =
                new HandoverWorkbook.ImportRow(
                        6,
                        Map.of("title", "现场问题", "description", "渗漏", "categoryName", "给排水"),
                        List.of());
        var error =
                assertThrows(
                        HandoverImportException.class,
                        () ->
                                HandoverImportMapper.map(
                                        "ledger", "project-5g", null, null, row, catalog));
        assertEquals(6, error.getErrors().get(0).row());
        assertTrue(error.getErrors().get(0).message().contains("同名"));
        first.setName("公共区域");
        second.setParentId(first.getId());
        var full =
                new HandoverWorkbook.ImportRow(
                        6,
                        Map.of("title", "现场问题", "description", "渗漏", "categoryName", "公共区域/给排水"),
                        List.of());
        var prepared = HandoverImportMapper.map("ledger", "project-5g", null, null, full, catalog);
        assertEquals("c2", ((HandoverLedgerEntity) prepared.value()).getCategoryId());
        assertEquals("project-5g", ((HandoverLedgerEntity) prepared.value()).getProjectId());
    }

    @Test
    void everyLegacyEquipmentColumnSurvivesTemplateAndMapping() {
        assertTrue(HandoverExcelSchema.EQUIPMENT.size() > 70);
        var row =
                new HandoverWorkbook.ImportRow(
                        2,
                        Map.of(
                                "name",
                                "电梯",
                                "properties.elevatorRatedLoad",
                                "1000",
                                "properties.certificateNumber",
                                "TS-2026",
                                "properties.ratedVoltage",
                                "380"),
                        List.of());
        var prepared =
                HandoverImportMapper.map(
                        "equipment",
                        "project-5g",
                        null,
                        null,
                        row,
                        new HandoverImportCatalog.Catalog(
                                List.of(), List.of(), List.of(), List.of()));
        var equipment = (HandoverEquipmentEntity) prepared.value();
        assertEquals("1000", equipment.getProperties().get("elevatorRatedLoad"));
        assertEquals("TS-2026", equipment.getProperties().get("certificateNumber"));
        assertEquals("380", equipment.getProperties().get("ratedVoltage"));
    }

    @Test
    void configurationImportPermissionCannotBeUsedForProblemImport() {
        Authentication configOnly = mock(Authentication.class);
        when(configOnly.hasPermission("handover-config", "import")).thenReturn(true);
        StepVerifier.create(
                        HandoverExcelPermission.assertImport("problem")
                                .contextWrite(
                                        reactor.util.context.Context.of(
                                                Authentication.class, configOnly)))
                .expectError(AccessDenyException.class)
                .verify();
        StepVerifier.create(
                        HandoverExcelPermission.assertImport("equipment")
                                .contextWrite(
                                        reactor.util.context.Context.of(
                                                Authentication.class, configOnly)))
                .verifyComplete();
        assertEquals("handover-process", HandoverExcelPermission.resource("item"));
    }

    private static byte[] serialize(Workbook book) {
        try {
            var output = new ByteArrayOutputStream();
            book.write(output);
            return output.toByteArray();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static byte[] bytes(
            reactor.core.publisher.Flux<org.springframework.core.io.buffer.DataBuffer> source) {
        return DataBufferUtils.join(source)
                .map(
                        buffer -> {
                            try {
                                byte[] value = new byte[buffer.readableByteCount()];
                                buffer.read(value);
                                return value;
                            } finally {
                                DataBufferUtils.release(buffer);
                            }
                        })
                .block();
    }
}
