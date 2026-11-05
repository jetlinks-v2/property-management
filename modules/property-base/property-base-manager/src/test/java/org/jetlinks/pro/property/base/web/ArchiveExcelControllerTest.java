package org.jetlinks.pro.property.base.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.entity.*;
import org.jetlinks.pro.property.base.excel.ArchiveExcel;
import org.jetlinks.pro.property.base.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.*;

/** HTTP 文件契约测试；真实Excel编解码，领域资产和事务在持久化测试中验证。 */
class ArchiveExcelControllerTest {
    final ArchiveScope scope = mock(ArchiveScope.class);
    final PropertyHouseService houses = mock(PropertyHouseService.class);
    final PropertyCustomerService customers = mock(PropertyCustomerService.class);
    final PropertyUserService users = mock(PropertyUserService.class);
    final PropertyDictionaryService dictionary = mock(PropertyDictionaryService.class);
    final ArchiveExcelService excel =
            new ArchiveExcelService(scope, houses, customers, users, dictionary);
    final WebTestClient client =
            WebTestClient.bindToController(
                            new PropertyHouseController(houses, excel, mock(org.jetlinks.pro.property.base.command.PropertyHouseholdCommandSupport.class)),
                            new PropertyCustomerController(customers, excel, mock(org.jetlinks.pro.property.base.command.PropertyHouseholdCommandSupport.class)))
                    .build();

    @BeforeEach
    void fixtures() {
        var project = new PropertyOrganizationEntity();
        project.setName("5G园区");
        when(scope.project("project", CrudAssetPermission.save)).thenReturn(Mono.just(project));
        var type = new PropertyDictionaryTypeEntity();
        type.setCode("house-type");
        type.setEnabled(true);
        var state = new PropertyDictionaryTypeEntity();
        state.setCode("house-state");
        state.setEnabled(true);
        when(dictionary.types()).thenReturn(Mono.just(List.of(type, state)));
        when(dictionary.items("house-type"))
                .thenReturn(Mono.just(List.of(item("house-type", "住宅", "residential"))));
        when(dictionary.items("house-state"))
                .thenReturn(Mono.just(List.of(item("house-state", "自住", "occupied"))));
    }

    @Test
    void templateIsBackendXlsxWithProjectSpaceReferenceEvenWhenCompleted() throws Exception {
        var house = new PropertyHouseService.HouseView();
        house.setSpaceId("00101");
        house.setName("101");
        house.setFullName("园区/1栋/101");
        house.setCompleted(true);
        when(houses.export(eq("project"), isNull(), any())).thenReturn(Mono.just(List.of(house)));
        byte[] bytes =
                client.get()
                        .uri("/property/house/template.xlsx?projectId=project")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectHeader()
                        .contentType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        .expectHeader()
                        .valueMatches("Content-Disposition", "attachment;.*")
                        .expectBody(byte[].class)
                        .returnResult()
                        .getResponseBody();
        try (var workbook =
                org.apache.poi.ss.usermodel.WorkbookFactory.create(
                        new ByteArrayInputStream(bytes))) {
            assertEquals("导入数据", workbook.getSheetName(0));
            assertEquals(0, workbook.getSheetAt(0).getLastRowNum());
            var dropdown =
                    workbook.getSheetAt(0).getDataValidations().stream()
                            .filter(
                                    v ->
                                            v.getRegions()
                                                            .getCellRangeAddresses()[0]
                                                            .getFirstColumn()
                                                    == 0)
                            .findFirst()
                            .orElseThrow();
            var range = workbook.getName(dropdown.getValidationConstraint().getFormula1());
            var area =
                    new org.apache.poi.ss.util.AreaReference(
                            range.getRefersToFormula(),
                            org.apache.poi.ss.SpreadsheetVersion.EXCEL2007);
            assertEquals(
                    "园区/1栋/101",
                    workbook.getSheet(area.getFirstCell().getSheetName())
                            .getRow(area.getFirstCell().getRow())
                            .getCell(area.getFirstCell().getCol())
                            .getStringCellValue());
            assertEquals(1, workbook.getSheet("填写示例").getLastRowNum());
            for (var sheet : List.of(workbook.getSheetAt(0), workbook.getSheet("填写示例"))) {
                for (int column = 0; column < 8; column++) {
                    var font =
                            workbook.getFontAt(
                                    sheet.getRow(0).getCell(column).getCellStyle().getFontIndex());
                    assertEquals(
                            column < 4
                                    ? org.apache.poi.ss.usermodel.IndexedColors.RED.getIndex()
                                    : org.apache.poi.ss.usermodel.IndexedColors.BLACK.getIndex(),
                            font.getColor());
                }
            }
            assertEquals(
                    "园区/1栋/101",
                    workbook.getSheet("空间参考").getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    void multipartOriginalWorkbookIsConvertedOnBackendAndReturnsCount() {
        var house = new PropertyHouseService.HouseView();
        house.setSpaceId("00101");
        house.setFullName("园区/1栋/101");
        when(houses.export(eq("project"), isNull(), any())).thenReturn(Mono.just(List.of(house)));
        var document =
                new ArchiveExcel.Document(
                        "房屋.xlsx",
                        new ArchiveExcel.SheetData(
                                "导入数据",
                                org.jetlinks.pro.property.base.excel.ArchiveImportSchema.columns(
                                        "house"),
                                List.of(
                                        Map.of(
                                                "spacePath",
                                                "园区/1栋/101",
                                                "houseType",
                                                "住宅",
                                                "houseState",
                                                "自住",
                                                "billingArea",
                                                new BigDecimal("123.45")))),
                        List.of());
        var buffer =
                org.springframework.core.io.buffer.DataBufferUtils.join(
                                ArchiveExcel.write(document))
                        .block();
        byte[] bytes;
        try {
            bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
        } finally {
            org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
        }
        var multipart = new MultipartBodyBuilder();
        multipart.part("file", new ByteArrayResource(bytes)).filename("房屋.xlsx");
        when(houses.importRows(eq("project"), anyList()))
                .thenAnswer(
                        call -> {
                            List<PropertyHouseService.HouseWrite> rows = call.getArgument(1);
                            assertEquals("00101", rows.get(0).spaceId());
                            assertEquals("residential", rows.get(0).houseType());
                            assertEquals(
                                    0,
                                    new BigDecimal("123.45").compareTo(rows.get(0).billingArea()));
                            return Mono.just(new PropertyHouseService.ImportResult(1));
                        });
        client.post()
                .uri("/property/house/_import?projectId=project")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(multipart.build())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.count")
                .isEqualTo(1);
        verify(houses).importRows(eq("project"), anyList());
        client.post()
                .uri("/property/house/_import?projectId=project")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(List.of())
                .exchange()
                .expectStatus()
                .isEqualTo(415);
    }

    @Test
    void historyExportReturnsXlsxAndPreservesDeletedCustomerAndRelationshipDates()
            throws Exception {
        var customer = new PropertyCustomerEntity();
        customer.setId("customer");
        customer.setUserId("001");
        customer.setName("旧客户");
        customer.setTelephone("001234");
        customer.setDeleted(true);
        customer.setCustomerType("person");
        var link = new org.jetlinks.pro.property.base.customer.RelationshipView();
        link.setId("relation");
        link.setCustomer(customer);
        link.setSpaceId("room");
        link.setStartTime(1000L);
        link.setEndTime(2000L);
        link.setEndReason("搬离");
        when(customers.exportHistory(eq("project"), eq("room"), any()))
                .thenReturn(Mono.just(List.of(link)));
        byte[] bytes =
                client.post()
                        .uri(
                                "/property/customer/_export?projectId=project&spaceId=room&history=true")
                        .bodyValue(new QueryParamEntity())
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectHeader()
                        .contentType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        .expectBody(byte[].class)
                        .returnResult()
                        .getResponseBody();
        try (var book =
                org.apache.poi.ss.usermodel.WorkbookFactory.create(
                        new ByteArrayInputStream(bytes))) {
            var sheet = book.getSheetAt(0);
            var byHeader = new HashMap<String, String>();
            for (var cell : sheet.getRow(0))
                byHeader.put(
                        cell.getStringCellValue(),
                        sheet.getRow(1).getCell(cell.getColumnIndex()).getStringCellValue());
            assertEquals("旧客户", byHeader.get("姓名或企业名称"));
            assertEquals("001234", byHeader.get("联系电话"));
            assertEquals("搬离", byHeader.get("结束原因"));
            assertEquals("relation", byHeader.get("关联标识"));
            assertTrue(byHeader.get("结束时间").contains("1970-01-01"));
        }
    }

    private static PropertyDictionaryItemEntity item(String type, String text, String value) {
        var item = new PropertyDictionaryItemEntity();
        item.setTypeCode(type);
        item.setText(text);
        item.setValue(value);
        item.setEnabled(true);
        return item;
    }
}
