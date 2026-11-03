package org.jetlinks.pro.property.base.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.entity.*;
import org.jetlinks.pro.property.base.excel.ArchiveExcel;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.multipart.FilePart;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.*;

class ArchiveExcelServiceTest {
    final ArchiveScope scope = mock(ArchiveScope.class);
    final PropertyHouseService houses = mock(PropertyHouseService.class);
    final PropertyCustomerService customers = mock(PropertyCustomerService.class);
    final PropertyUserService users = mock(PropertyUserService.class);
    final PropertyDictionaryService dictionary = mock(PropertyDictionaryService.class);
    final ArchiveExcelService service =
            new ArchiveExcelService(scope, houses, customers, users, dictionary);

    @Test
    void completedProjectStillDownloadsTemplateWithAllSpaceReferences() {
        var project = new PropertyOrganizationEntity();
        project.setName("5G园区");
        when(scope.project("project", CrudAssetPermission.save)).thenReturn(Mono.just(project));
        var house = new PropertyHouseService.HouseView();
        house.setCompleted(true);
        house.setSpaceId("101");
        house.setName("101");
        house.setFullName("5G园区/1栋/101");
        when(houses.export(eq("project"), isNull(), any())).thenReturn(Mono.just(List.of(house)));
        when(dictionary.types()).thenReturn(Mono.just(List.of()));
        var document = service.template("project", "house").block();
        assertEquals("5G园区-房屋导入模板.xlsx", document.filename());
        assertTrue(document.data().rows().isEmpty());
        var reference =
                document.references().stream()
                        .filter(sheet -> sheet.name().equals("空间参考"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("5G园区/1栋/101", reference.rows().get(0).get("spacePath"));
        assertEquals("已完善", reference.rows().get(0).get("completed"));
    }

    @Test
    void unrecognizedDictionaryLastRowNeverCallsDomainImporter() {
        when(scope.project("project", CrudAssetPermission.save))
                .thenReturn(Mono.just(new PropertyOrganizationEntity()));
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
        when(houses.export(eq("project"), isNull(), any()))
                .thenReturn(
                        Mono.just(
                                List.of(
                                        ArchiveImportMatcherTest.house("101", "园区/101"),
                                        ArchiveImportMatcherTest.house("102", "园区/102"))));
        var rows =
                List.<Map<String, Object>>of(
                        Map.of(
                                "spacePath",
                                "园区/101",
                                "houseType",
                                "住宅",
                                "houseState",
                                "自住",
                                "billingArea",
                                "100"),
                        Map.of(
                                "spacePath",
                                "园区/102",
                                "houseType",
                                "未知类型",
                                "houseState",
                                "自住",
                                "billingArea",
                                "100"));
        var doc =
                new ArchiveExcel.Document(
                        "x.xlsx",
                        new ArchiveExcel.SheetData(
                                "导入数据",
                                org.jetlinks.pro.property.base.excel.ArchiveImportSchema.columns(
                                        "house"),
                                rows),
                        List.of());
        var file = mock(FilePart.class);
        when(file.filename()).thenReturn("x.xlsx");
        when(file.content()).thenReturn(ArchiveExcel.write(doc));
        StepVerifier.create(service.importHouses("project", file))
                .expectErrorSatisfies(
                        error -> {
                            assertInstanceOf(BusinessException.class, error);
                            assertEquals(3, ((BusinessException) error).getArgs()[0]);
                        })
                .verify();
        verify(houses, never()).importRows(anyString(), anyList());
    }

    @Test
    void houseDomainPreflightsAllRowsBeforeFirstSave() {
        var dictionary = mock(PropertyDictionaryService.class);
        org.hswebframework.ezorm.rdb.mapping.ReactiveRepository<PropertyHouseEntity, String> repo =
                mock(org.hswebframework.ezorm.rdb.mapping.ReactiveRepository.class);
        when(repo.findById(anyString())).thenReturn(Mono.empty());
        var service =
                spy(
                        new PropertyHouseService(
                                scope,
                                dictionary,
                                repo,
                                mock(
                                        org.hswebframework.ezorm.rdb.mapping.ReactiveRepository
                                                .class)));
        when(scope.project("project", CrudAssetPermission.save))
                .thenReturn(Mono.just(new PropertyOrganizationEntity()));
        when(scope.lock("project")).thenReturn(Mono.empty());
        when(scope.leaf(eq("project"), anyString(), eq(CrudAssetPermission.save)))
                .thenReturn(Mono.just(new PropertySpaceEntity()));
        var first = new PropertyHouseService.HouseView();
        first.setProjectId("project");
        first.setVersion(0L);
        first.setCompleted(false);
        doReturn(Mono.just(first)).when(service).detail("project", "101");
        doReturn(Mono.error(new BusinessException.NoStackTrace("not-a-leaf", 400)))
                .when(service)
                .detail("project", "102");
        when(dictionary.validateSelection(anyString(), anyString(), isNull()))
                .thenReturn(Mono.empty());
        var one =
                new PropertyHouseService.HouseWrite(
                        0L,
                        "residential",
                        "occupied",
                        java.math.BigDecimal.TEN,
                        null,
                        null,
                        null,
                        null,
                        "101");
        var two =
                new PropertyHouseService.HouseWrite(
                        0L,
                        "residential",
                        "occupied",
                        java.math.BigDecimal.TEN,
                        null,
                        null,
                        null,
                        null,
                        "102");
        StepVerifier.create(service.importRows("project", List.of(one, two)))
                .expectErrorSatisfies(
                        error -> {
                            assertInstanceOf(BusinessException.class, error);
                            assertEquals(3, ((BusinessException) error).getArgs()[0]);
                        })
                .verify();
        verify(service, never()).save(anyString(), anyString(), any());
    }

    @Test
    void customerDomainValidatesLastRowBeforeAnyProfileFamilyOrRelationshipInsert() {
        org.hswebframework.ezorm.rdb.mapping.ReactiveRepository<PropertyCustomerEntity, String>
                customerRepo = mock(org.hswebframework.ezorm.rdb.mapping.ReactiveRepository.class);
        org.hswebframework.ezorm.rdb.mapping.ReactiveRepository<
                        PropertyCustomerRelationshipEntity, String>
                relationRepo =
                        mock(
                                org.hswebframework.ezorm.rdb.mapping.ReactiveRepository.class,
                                RETURNS_DEEP_STUBS);
        org.hswebframework.ezorm.rdb.mapping.ReactiveRepository<
                        PropertyCustomerFamilyEntity, String>
                familyRepo = mock(org.hswebframework.ezorm.rdb.mapping.ReactiveRepository.class);
        org.hswebframework.ezorm.rdb.mapping.ReactiveRepository<PropertyUserEntity, String>
                userRepo = mock(org.hswebframework.ezorm.rdb.mapping.ReactiveRepository.class);
        var domain =
                new PropertyCustomerService(
                        customerRepo,
                        relationRepo,
                        familyRepo,
                        userRepo,
                        scope,
                        dictionary,
                        houses);
        when(scope.spaces("project", null, CrudAssetPermission.save))
                .thenReturn(Mono.just(List.of()));
        when(scope.lock("project")).thenReturn(Mono.empty());
        var auth = new org.hswebframework.web.authorization.simple.SimpleAuthentication();
        when(scope.authenticated()).thenReturn(Mono.just(auth));
        var user = new PropertyUserEntity();
        user.setOwner(true);
        user.setStatus((byte) 1);
        user.setDeleted(false);
        when(userRepo.findById(anyString())).thenReturn(Mono.just(user));
        when(customerRepo.findById(anyString())).thenReturn(Mono.empty());
        org.hswebframework.ezorm.rdb.mapping.ReactiveQuery<PropertyCustomerRelationshipEntity>
                query =
                        mock(
                                org.hswebframework.ezorm.rdb.mapping.ReactiveQuery.class,
                                RETURNS_SELF);
        when(relationRepo.createQuery()).thenReturn(query);
        when(query.count()).thenReturn(Mono.just(0));
        when(dictionary.validateSelection("customer-nature", "owner", null))
                .thenReturn(Mono.empty());
        when(houses.requireCompleted("project", "101", CrudAssetPermission.save))
                .thenReturn(Mono.just(new PropertyHouseEntity()));
        when(houses.requireCompleted("project", "102", CrudAssetPermission.save))
                .thenReturn(
                        Mono.error(new BusinessException.NoStackTrace("house-incomplete", 409)));
        var first = person("alice", "101");
        var second = person("bob", "102");
        try (var assets = mockStatic(org.jetlinks.pro.assets.AssetsHolder.class)) {
            assets.when(
                            () ->
                                    org.jetlinks.pro.assets.AssetsHolder.assertPermission(
                                            any(org.jetlinks.pro.assets.AssetType.class),
                                            anyString(),
                                            any(CrudAssetPermission.class)))
                    .thenReturn(Mono.empty());
            StepVerifier.create(domain.importCustomers("project", List.of(first, second)))
                    .expectErrorSatisfies(
                            error -> {
                                assertInstanceOf(BusinessException.class, error);
                                assertEquals(3, ((BusinessException) error).getArgs()[0]);
                            })
                    .verify();
        }
        verify(customerRepo, never()).insert(any(PropertyCustomerEntity.class));
        verify(familyRepo, never()).insert(any(PropertyCustomerFamilyEntity.class));
        verify(relationRepo, never()).insert(any(PropertyCustomerRelationshipEntity.class));
    }

    private static org.jetlinks.pro.property.base.customer.CustomerRequests.Create person(
            String user, String space) {
        var row = new org.jetlinks.pro.property.base.customer.CustomerRequests.Create();
        row.setUserId(user);
        row.setCustomerType("person");
        row.setName(user);
        row.setTelephone("001");
        row.setSpaceId(space);
        row.setNature("owner");
        row.setFamilyMode("new");
        row.setFamilyName("家庭");
        row.setStartTime(1000L);
        return row;
    }

    private static PropertyDictionaryItemEntity item(String code, String text, String value) {
        var item = new PropertyDictionaryItemEntity();
        item.setTypeCode(code);
        item.setText(text);
        item.setValue(value);
        item.setEnabled(true);
        return item;
    }
}
