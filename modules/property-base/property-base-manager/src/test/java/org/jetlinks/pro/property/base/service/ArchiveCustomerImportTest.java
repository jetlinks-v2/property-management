package org.jetlinks.pro.property.base.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.customer.CustomerRequests;
import org.jetlinks.pro.property.base.entity.*;
import org.jetlinks.pro.property.base.excel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.multipart.FilePart;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.*;

class ArchiveCustomerImportTest {
    final ArchiveScope scope = mock(ArchiveScope.class);
    final PropertyHouseService houses = mock(PropertyHouseService.class);
    final PropertyCustomerService customers = mock(PropertyCustomerService.class);
    final PropertyUserService users = mock(PropertyUserService.class);
    final PropertyDictionaryService dictionaries = mock(PropertyDictionaryService.class);
    final ArchiveExcelService service =
            new ArchiveExcelService(scope, houses, customers, users, dictionaries);

    @BeforeEach
    void references() {
        var project = new PropertyOrganizationEntity();
        project.setName("测试项目");
        when(scope.project("project", CrudAssetPermission.save)).thenReturn(Mono.just(project));
        var house = ArchiveImportMatcherTest.house("101", "园区/101");
        house.setCompleted(true);
        when(houses.export(eq("project"), isNull(), any())).thenReturn(Mono.just(List.of(house)));
        when(users.customerReferences())
                .thenReturn(
                        Mono.just(
                                List.of(
                                        ArchiveImportMatcherTest.user("user", "张三", "001"),
                                        ArchiveImportMatcherTest.user("company", "测试企业", "002"))));
        var type = new PropertyDictionaryTypeEntity();
        type.setCode("customer-nature");
        type.setEnabled(true);
        var item = new PropertyDictionaryItemEntity();
        item.setTypeCode("customer-nature");
        item.setValue("owner");
        item.setText("业主");
        item.setEnabled(true);
        when(dictionaries.types()).thenReturn(Mono.just(List.of(type)));
        when(dictionaries.items("customer-nature")).thenReturn(Mono.just(List.of(item)));
        when(customers.importCustomers(eq("project"), anyList()))
                .thenReturn(Mono.just(new CustomerRequests.ImportResult(1)));
    }

    @Test
    void personalFileConvertsNamesAndDictionaryBeforeSingleDomainCall() {
        service.importCustomers("project", "person", file("person", List.of(person("张三")))).block();
        verify(customers)
                .importCustomers(
                        eq("project"),
                        argThat(
                                rows -> {
                                    var row = rows.get(0);
                                    assertEquals("user", row.getUserId());
                                    assertEquals("101", row.getSpaceId());
                                    assertEquals("owner", row.getNature());
                                    assertEquals("001", row.getTelephone());
                                    assertEquals("补充地址", row.getAddress());
                                    return true;
                                }));
    }

    @Test
    void corporateFileHasNoPersonalNature() {
        service.importCustomers(
                        "project",
                        "company",
                        file(
                                "company",
                                List.of(
                                        Map.of(
                                                "spacePath",
                                                "园区/101",
                                                "companyName",
                                                "测试企业",
                                                "telephone",
                                                "002",
                                                "creditCode",
                                                "000001",
                                                "startTime",
                                                "2026-11-04 09:00:00"))))
                .block();
        verify(customers)
                .importCustomers(
                        eq("project"),
                        argThat(
                                rows -> {
                                    var row = rows.get(0);
                                    assertEquals("company", row.getUserId());
                                    assertEquals("000001", row.getCreditCode());
                                    assertNull(row.getNature());
                                    return true;
                                }));
    }

    @Test
    void lastUnmatchedCustomerPreventsAnyDomainWriteAndReportsRow() {
        StepVerifier.create(
                        service.importCustomers(
                                "project",
                                "person",
                                file("person", List.of(person("张三"), person("未同步")))))
                .expectErrorSatisfies(
                        error -> {
                            assertInstanceOf(BusinessException.class, error);
                            assertEquals(3, ((BusinessException) error).getArgs()[0]);
                        })
                .verify();
        verify(customers, never()).importCustomers(anyString(), anyList());
    }

    @Test
    void wrongTypeAndLegacyTemplatesCannotBeImported() {
        StepVerifier.create(
                        service.importCustomers(
                                "project", "company", file("person", List.of(person("张三")))))
                .expectError(BusinessException.class)
                .verify();
        verify(customers, never()).importCustomers(anyString(), anyList());
        verifyNoInteractions(users);
    }

    @Test
    void unauthorizedProjectDoesNotReadMatchingReferencesOrWrite() {
        when(scope.project("project", CrudAssetPermission.save))
                .thenReturn(Mono.error(new BusinessException.NoStackTrace("denied", 403)));
        StepVerifier.create(
                        service.importCustomers(
                                "project", "person", file("person", List.of(person("张三")))))
                .expectError(BusinessException.class)
                .verify();
        verifyNoInteractions(users, houses, dictionaries);
        verify(customers, never()).importCustomers(anyString(), anyList());
    }

    @Test
    void templateSeparatesPersonalAndCompanyAndSuppliesOnlyBusinessNames() {
        var personal = service.template("project", "person").block();
        assertEquals(ArchiveImportSchema.columns("person"), personal.data().columns());
        assertEquals(List.of("园区/101"), personal.options().get("spacePath"));
        assertTrue(
                personal.references().stream()
                        .flatMap(s -> s.columns().stream())
                        .noneMatch(s -> s.endsWith("Id")));
        var company = service.template("project", "company").block();
        assertEquals(ArchiveImportSchema.columns("company"), company.data().columns());
    }

    static Map<String, Object> person(String name) {
        var row = new HashMap<>(ArchiveImportMatcherTest.person(name, "001"));
        row.put("nature", "业主");
        row.put("address", "补充地址");
        return row;
    }

    static FilePart file(String type, List<Map<String, Object>> rows) {
        var file = mock(FilePart.class);
        when(file.filename()).thenReturn("客户.xlsx");
        when(file.content())
                .thenReturn(
                        ArchiveExcel.write(
                                new ArchiveExcel.Document(
                                        "客户.xlsx",
                                        new ArchiveExcel.SheetData(
                                                "导入数据", ArchiveImportSchema.columns(type), rows),
                                        List.of())));
        return file;
    }
}
