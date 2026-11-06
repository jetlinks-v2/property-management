package org.jetlinks.pro.property.base.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.entity.*;
import org.jetlinks.pro.property.base.command.*;
import org.jetlinks.pro.property.base.organization.OrganizationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/** 真实 PostgreSQL 验证档案事务、CAS 与资产范围，仅替换外部 AssetsHolder 授权结果。 */
@SpringBootTest(
        classes = ArchivePersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ArchivePersistenceTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Set<String> SPACE_IDS =
            Set.of("single", "tower", "middle", "room", "other");
    private static final Set<String> ORG_IDS = Set.of("project", "other-project", "untyped");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        OrganizationPersistenceTest.database(registry);
    }

    @Autowired ArchiveScope scope;
    @Autowired PropertySpaceService spaceService;
    @Autowired PropertyOrganizationService organizationService;
    @Autowired PropertyHouseService houseService;
    @Autowired PropertyDictionaryService dictionary;
    @Autowired ReactiveRepository<PropertyOrganizationEntity, String> organizations;
    @Autowired ReactiveRepository<PropertySpaceEntity, String> spaces;
    @Autowired ReactiveRepository<PropertySpaceBindingEntity, String> bindings;
    @Autowired ReactiveRepository<PropertyHouseEntity, String> houses;
    @Autowired ReactiveRepository<PropertyCustomerEntity, String> customers;
    @Autowired ReactiveRepository<PropertyCustomerRelationshipEntity, String> relationships;
    @Autowired ReactiveRepository<PropertyDictionaryTypeEntity, String> types;
    @Autowired ReactiveRepository<PropertyDictionaryItemEntity, String> items;


    @Autowired PropertyHouseCommandSupport financeHouseCommands;
    @Autowired PropertyDictionaryCommandSupport financeDictionaryCommands;
    @Autowired PropertySpaceCommandSupport financeSpaceCommands;

    @Autowired SpaceOrganizationAssets organizationAssets;

    @Test
    void failedOrganizationAssetRegistrationRollsBackBusinessBinding() {
        when(organizationAssets.change(eq("single"), eq("project"), isNull()))
                .thenReturn(Mono.error(new BusinessException.NoStackTrace("资产登记失败", 503)));
        try {
            StepVerifier.create(spaceService.bind("single", null, 0).contextWrite(all()))
                    .expectErrorMatches(error -> status(error, 503)).verify(WAIT);
            var retained = bindings.findById("single").block(WAIT);
            assertEquals("project", retained.getOrganizationId());
            assertEquals(0L, retained.getVersion());
        } finally {
            when(organizationAssets.change(eq("single"), eq("project"), isNull()))
                    .thenReturn(Mono.empty());
        }
    }

    @Test
    void financeCommandsReadOnlyCompletedHousesAndKeepExistingDirectorySeparate() {
        houseService.save("project", "single", write("single", 0L, "123.45"))
                .contextWrite(all()).block(WAIT);
        var request = new QueryPropertyHousesCommand();
        request.setProjectId("project");
        var page = financeHouseCommands.queryHouses(request).contextWrite(all()).block(WAIT);
        assertEquals(1, page.total());
        assertEquals("single", page.data().get(0).spaceId());
        assertEquals(new BigDecimal("123.45"), page.data().get(0).billingArea());
        request.setPageSize(201);
        StepVerifier.create(financeHouseCommands.queryHouses(request).contextWrite(all()))
                .expectErrorMatches(e -> status(e, 400)).verify(WAIT);
        var spaceRequest = new QueryPropertySpaceNodesCommand();
        spaceRequest.setProjectId("project");
        var nodes = financeSpaceCommands.querySpaces(spaceRequest).contextWrite(all()).block(WAIT);
        assertEquals(Set.of("single", "tower", "middle", "room"),
                nodes.stream().map(n -> n.id()).collect(java.util.stream.Collectors.toSet()));
        assertEquals("propertyService:directory", QueryPropertySpacesCommand.SERVICE_ID);
    }

    @Test
    void financeContextProviderOpensItsOwnTransactionAndRejectsIncompleteOrUnreadableHouse() {
        var request = new QueryPropertyHouseContextCommand();
        request.setProjectId("project"); request.setSpaceId("single"); request.setForUpdate(true);
        StepVerifier.create(financeHouseCommands.queryHouseContext(request).contextWrite(all()))
                .expectErrorMatches(e -> status(e, 409)).verify(WAIT);
        houseService.save("project", "single", write("single", 0L, "30.00"))
                .contextWrite(all()).block(WAIT);
        // No caller transaction: the separately deployed provider owns the read transaction.
        var restored = new QueryPropertyHouseContextCommand();
        request.readable().forEach(restored::with);
        var commandSupport = org.jetlinks.supports.command.JavaBeanCommandSupport.create(financeHouseCommands);
        var context = commandSupport.execute(restored).contextWrite(all()).block(WAIT);
        assertEquals("single", context.house().spaceId());
        assertTrue(context.customers().isEmpty());
        StepVerifier.create(financeHouseCommands.queryHouseContext(request)
                .contextWrite(access(Set.of("room"), ORG_IDS)))
                .expectError().verify(WAIT);
    }

    @Test
    void financeDictionaryCommandFiltersDisabledOptionsAndRejectsDisabledCategory() {
        var request = new QueryPropertyDictionaryOptionsCommand(); request.setTypeCode("house-type");
        dictionary.createItem("house-type", item("closed", false, null)).contextWrite(all()).block(WAIT);
        var options = financeDictionaryCommands.queryOptions(request).contextWrite(all()).block(WAIT);
        assertEquals(List.of("residential"), options.stream().map(n -> n.value()).toList());
        var type = dictionary.types().contextWrite(all()).block(WAIT).stream()
                .filter(n -> "house-type".equals(n.getCode())).findFirst().orElseThrow();
        dictionary.updateType(type.getId(), type("house-type", false, type.getVersion()))
                .contextWrite(all()).block(WAIT);
        StepVerifier.create(financeDictionaryCommands.queryOptions(request).contextWrite(all()))
                .expectErrorMatches(e -> status(e, 409)).verify(WAIT);
        StepVerifier.create(financeDictionaryCommands.queryOptions(request)).expectError().verify(WAIT);
    }

    @BeforeEach
    void prepare() {
        customers
                .deleteById(customers.createQuery().fetch().map(PropertyCustomerEntity::getId))
                .block(WAIT);
        relationships
                .deleteById(
                        relationships
                                .createQuery()
                                .fetch()
                                .map(PropertyCustomerRelationshipEntity::getId))
                .block(WAIT);
        houses.deleteById(houses.createQuery().fetch().map(PropertyHouseEntity::getId)).block(WAIT);
        bindings.deleteById(bindings.createQuery().fetch().map(PropertySpaceBindingEntity::getId))
                .block(WAIT);
        spaces.deleteById(spaces.createQuery().fetch().map(PropertySpaceEntity::getId)).block(WAIT);
        organizations
                .deleteById(
                        organizations.createQuery().fetch().map(PropertyOrganizationEntity::getId))
                .block(WAIT);
        items.deleteById(items.createQuery().fetch().map(PropertyDictionaryItemEntity::getId))
                .block(WAIT);
        types.deleteById(types.createQuery().fetch().map(PropertyDictionaryTypeEntity::getId))
                .block(WAIT);
        organization("project", OrganizationType.project);
        organization("other-project", OrganizationType.project);
        organization("untyped", null);
        var snapshot =
                SpaceSnapshot.from(
                        List.of(
                                SpaceSnapshotTest.node("single", null, "仅一栋"),
                                SpaceSnapshotTest.node("tower", null, "多层根"),
                                SpaceSnapshotTest.node("middle", "tower", "任意中间层"),
                                SpaceSnapshotTest.node("room", "middle", "末端"),
                                SpaceSnapshotTest.node("other", null, "其他项目")));
        spaces.insertBatch(new ArrayList<>(snapshot.values())).block(WAIT);
        binding("single", "project");
        binding("tower", "project");
        binding("other", "other-project");
        dictionary.createType(type("house-type", true, null)).contextWrite(all()).block(WAIT);
        dictionary.createType(type("house-state", true, null)).contextWrite(all()).block(WAIT);
        dictionary
                .createItem("house-type", item("residential", true, null))
                .contextWrite(all())
                .block(WAIT);
        dictionary
                .createItem("house-state", item("delivered", true, null))
                .contextWrite(all())
                .block(WAIT);
    }

    @Test
    void typedSpaceInformationUsesRealAncestorsBeforeSubtreeCropping() {
        spaces.createUpdate()
                .set("spaceTypeId", "building-type")
                .set("spaceTypeName", "楼栋")
                .where("id", "single")
                .execute()
                .block(WAIT);
        spaces.createUpdate()
                .set("spaceTypeId", "project-type")
                .set("spaceTypeName", "项目")
                .where("id", "tower")
                .execute()
                .block(WAIT);
        spaces.createUpdate()
                .set("spaceTypeId", "custom-type")
                .set("spaceTypeName", "自定义层级")
                .where("id", "middle")
                .execute()
                .block(WAIT);
        spaces.createUpdate()
                .set("spaceTypeId", "room-type")
                .set("spaceTypeName", "房间号")
                .set("name", "末端/含斜线")
                .where("id", "room")
                .execute()
                .block(WAIT);
        var single = houseService.detail("project", "single").contextWrite(all()).block(WAIT);
        assertEquals(
                List.of(
                        new PropertySpaceEntity.SpaceSegment(
                                "single", "仅一栋", "building-type", "楼栋")),
                single.getSpaceSegments());
        var leaf =
                houseService
                        .query("project", "room", new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT)
                        .getData()
                        .get(0);
        assertEquals(
                List.of("tower", "middle", "room"),
                leaf.getSpaceSegments().stream()
                        .map(PropertySpaceEntity.SpaceSegment::id)
                        .toList());
        assertEquals(
                List.of("项目", "自定义层级", "房间号"),
                leaf.getSpaceSegments().stream()
                        .map(PropertySpaceEntity.SpaceSegment::typeName)
                        .toList());
        assertEquals("末端/含斜线", leaf.getSpaceSegments().get(2).name());
    }

    @Test
    void dictionaryCommandRequiresAuthenticationAndReturnsOnlyEnabledPublicValues() {
        var provider = new PropertyDictionaryItemsCommandSupport(dictionary);
        var request = new org.jetlinks.pro.property.base.command.QueryPropertyDictionaryItemsCommand(); request.setTypeCode("house-type");
        StepVerifier.create(provider.query(request)).expectError(UnAuthorizedException.class).verify(WAIT);
        var result = provider.query(request).contextWrite(all()).collectList().block(WAIT);
        assertEquals(1, result.size()); assertEquals("residential", result.get(0).value());
        var itemId = PropertyDictionaryService.identity("house-type", "residential");
        dictionary.updateItem(itemId, item("residential", false, 0L)).contextWrite(all()).block(WAIT);
        assertTrue(provider.query(request).contextWrite(all()).collectList().block(WAIT).isEmpty());
        dictionary.updateItem(itemId, item("residential", true, 1L)).contextWrite(all()).block(WAIT);
        dictionary.updateType(PropertyDictionaryService.identity("type", "house-type"), type("house-type", false, 0L)).contextWrite(all()).block(WAIT);
        assertTrue(provider.query(request).contextWrite(all()).collectList().block(WAIT).isEmpty());
        request.setTypeCode("missing-type"); assertTrue(provider.query(request).contextWrite(all()).collectList().block(WAIT).isEmpty());
    }
    @Test
    void singleRootAndArbitraryDepthLeafCanBecomeHouseButNonLeafCannot() {
        var initial =
                houseService
                        .query("project", null, new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT);
        assertEquals(2, initial.getTotal());
        assertEquals(
                Set.of("single", "room"),
                initial.getData().stream()
                        .map(PropertyHouseService.HouseView::getId)
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(
                initial.getData().stream().noneMatch(PropertyHouseService.HouseView::getCompleted));
        assertTrue(
                houseService
                        .save("project", "single", write("single", 0L, "10.25"))
                        .contextWrite(all())
                        .block(WAIT)
                        .getCompleted());
        assertTrue(
                houseService
                        .save("project", "room", write("room", 0L, "20"))
                        .contextWrite(all())
                        .block(WAIT)
                        .getCompleted());
        StepVerifier.create(
                        houseService
                                .save("project", "middle", write("middle", 0L, "30"))
                                .contextWrite(all()))
                .expectErrorMatches(error -> status(error, 409))
                .verify(WAIT);
        assertFalse(spaces.findById("middle").block(WAIT).getLeaf());
        assertEquals(2, houses.createQuery().count().block(WAIT));
    }

    @Test
    void permissionFilteredTreeNeverTurnsIntermediateNodeIntoLeafAndProjectScopeCannotBeCrossed() {
        var limited = access(Set.of("middle"), Set.of("project"));
        var tree = spaceService.tree("project").contextWrite(limited).block(WAIT);
        assertEquals(1, tree.size());
        assertEquals("middle", tree.get(0).getId());
        assertTrue(tree.get(0).getChildren().isEmpty());
        assertFalse(tree.get(0).getLeaf());
        assertEquals(
                0,
                houseService
                        .query("project", null, new QueryParamEntity())
                        .contextWrite(limited)
                        .block(WAIT)
                        .getTotal());
        StepVerifier.create(houseService.detail("project", "room").contextWrite(limited))
                .expectError(AccessDenyException.class)
                .verify(WAIT);
        StepVerifier.create(houseService.detail("project", "other").contextWrite(all()))
                .expectErrorMatches(error -> status(error, 404))
                .verify(WAIT);
        StepVerifier.create(
                        scope.project("other-project", CrudAssetPermission.read)
                                .contextWrite(limited))
                .expectError(AccessDenyException.class)
                .verify(WAIT);
        StepVerifier.create(spaceService.tree("project"))
                .expectError(UnAuthorizedException.class)
                .verify(WAIT);
        assertEquals(1, spaceService.projects().contextWrite(limited).block(WAIT).size());
        assertTrue(spaceService.roots().contextWrite(limited).block(WAIT).isEmpty());
    }

    @Test
    void rootBindingUsesCasRejectsInvalidOrganizationAndChecksBothAssetBoundaries() {
        // 此测试验证单根 CAS；移除其他测试为多种房屋层级准备的旧关联。
        bindings.deleteById("tower").block(WAIT);
        bindings.deleteById("other").block(WAIT);
        StepVerifier.create(spaceService.bind("middle", "project", 0).contextWrite(all()))
                .expectErrorMatches(error -> status(error, 409))
                .verify(WAIT);
        StepVerifier.create(
                        spaceService
                                .bind("single", "missing", 0)
                                .contextWrite(access(SPACE_IDS, Set.of("project", "missing"))))
                .expectErrorMatches(error -> status(error, 404))
                .verify(WAIT);
        StepVerifier.create(spaceService.bind("single", "untyped", 0).contextWrite(all()))
                .expectErrorMatches(error -> status(error, 409))
                .verify(WAIT);
        organizations.createUpdate()
                .set(PropertyOrganizationEntity::getOrganizationType, OrganizationType.external)
                .where(PropertyOrganizationEntity::getId, "other-project")
                .execute().block(WAIT);
        StepVerifier.create(spaceService.bind("single", "other-project", 0).contextWrite(all()))
                .expectErrorMatches(error -> status(error, 409)
                        && "error.property_archive_organization_not_project".equals(error.getMessage()))
                .verify(WAIT);
        assertEquals("project", bindings.findById("single").block(WAIT).getOrganizationId());
        assertEquals(0L, bindings.findById("single").block(WAIT).getVersion());
        organizations.createUpdate()
                .set(PropertyOrganizationEntity::getOrganizationType, OrganizationType.project)
                .where(PropertyOrganizationEntity::getId, "other-project")
                .execute().block(WAIT);
        StepVerifier.create(
                        spaceService
                                .bind("single", "other-project", 0)
                                .contextWrite(access(SPACE_IDS, Set.of("project"))))
                .expectError(AccessDenyException.class)
                .verify(WAIT);
        StepVerifier.create(
                        spaceService
                                .bind("single", null, 0)
                                .contextWrite(access(Set.of("room"), ORG_IDS)))
                .expectError(AccessDenyException.class)
                .verify(WAIT);
        var signals =
                Flux.merge(
                                spaceService.bind("single", "project", 0).materialize(),
                                spaceService.bind("single", "other-project", 0).materialize())
                        .contextWrite(all())
                        .collectList()
                        .block(WAIT);
        oneWinner(signals);
        var persisted = bindings.findById("single").block(WAIT);
        assertEquals(1L, persisted.getVersion());
        var root =
                spaceService.roots().contextWrite(all()).block(WAIT).stream()
                        .filter(row -> row.getId().equals("single"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(persisted.getOrganizationId(), root.getOrganizationId());
        assertEquals(1L, root.getVersion());
        spaceService.bind("single", null, 1).contextWrite(all()).block(WAIT);
        assertNull(bindings.findById("single").block(WAIT).getOrganizationId());
        assertEquals(2L, bindings.findById("single").block(WAIT).getVersion());
    }

    @Test
    void boundOrganizationTypeCannotChangeOrClearUntilSpaceIsUnbound() {
        bindings.deleteById("tower").block(WAIT);
        for (OrganizationType type : new OrganizationType[] {OrganizationType.external, null}) {
            StepVerifier.create(organizationService.updateType("project", type, 0).contextWrite(all()))
                    .expectErrorMatches(error -> status(error, 409)
                            && "error.property_archive_organization_in_use".equals(error.getMessage()))
                    .verify(WAIT);
        }
        var unchanged = organizations.findById("project").block(WAIT);
        assertEquals(OrganizationType.project, unchanged.getOrganizationType());
        assertEquals(0L, unchanged.getVersion());
        spaceService.bind("single", null, 0).contextWrite(all()).block(WAIT);
        var changed = organizationService.updateType("project", OrganizationType.external, 0)
                .contextWrite(all()).block(WAIT);
        assertEquals(OrganizationType.external, changed.getOrganizationType());
        var cleared = organizationService.updateType("project", null, changed.getVersion())
                .contextWrite(all()).block(WAIT);
        assertNull(cleared.getOrganizationType());
    }

    @Test
    void organizationHasOnlyOneRootEvenWhenDifferentRootsBindConcurrently() {
        bindings.deleteById(bindings.createQuery().fetch().map(PropertySpaceBindingEntity::getId))
                .block(WAIT);
        var outcomes =
                Flux.merge(
                                spaceService.bind("single", "project", 0).materialize(),
                                spaceService.bind("tower", "project", 0).materialize())
                        .contextWrite(all())
                        .collectList()
                        .block(WAIT);
        oneWinner(outcomes);
        var winner =
                bindings.createQuery().where("organizationId", "project").fetchOne().block(WAIT);
        assertNotNull(winner);
        String loser = winner.getId().equals("single") ? "tower" : "single";
        // 即使原关联空间对调用者不可见，也不能给同一组织再追加一个根。
        StepVerifier.create(
                        spaceService
                                .bind(loser, "project", 0)
                                .contextWrite(access(Set.of(loser), Set.of("project"))))
                .expectErrorMatches(
                        error ->
                                error instanceof BusinessException e
                                        && e.getStatus() == 409
                                        && e.getMessage()
                                                .equals(
                                                        "error.property_archive_organization_already_bound"))
                .verify(WAIT);
        spaceService.bind(winner.getId(), "project", 1).contextWrite(all()).block(WAIT);
        assertEquals(
                1, bindings.createQuery().where("organizationId", "project").count().block(WAIT));
        spaceService.bind(winner.getId(), null, 2).contextWrite(all()).block(WAIT);
        spaceService.bind(loser, "project", 0).contextWrite(all()).block(WAIT);
        assertEquals(
                loser,
                bindings.createQuery()
                        .where("organizationId", "project")
                        .fetchOne()
                        .block(WAIT)
                        .getId());
        assertNull(bindings.findById(winner.getId()).block(WAIT).getOrganizationId());
    }

    @Test
    void rootPreviewChecksOrganizationAndSpacePermissionsAndPreservesSourceLeaf() {
        var tree = spaceService.rootTree("tower").contextWrite(all()).block(WAIT);
        assertEquals("tower", tree.get(0).getId());
        assertEquals("room", tree.get(0).getChildren().get(0).getChildren().get(0).getId());
        var limited = access(Set.of("tower", "middle"), Set.of("project"));
        var middle =
                spaceService
                        .rootTree("tower")
                        .contextWrite(limited)
                        .block(WAIT)
                        .get(0)
                        .getChildren()
                        .get(0);
        assertTrue(middle.getChildren().isEmpty());
        assertFalse(middle.getLeaf());
        StepVerifier.create(
                        spaceService
                                .rootTree("tower")
                                .contextWrite(access(Set.of("middle"), Set.of("project"))))
                .expectError(AccessDenyException.class)
                .verify(WAIT);
        StepVerifier.create(
                        spaceService
                                .rootTree("tower")
                                .contextWrite(access(SPACE_IDS, Set.of("other-project"))))
                .expectError(AccessDenyException.class)
                .verify(WAIT);
        StepVerifier.create(spaceService.rootTree("middle").contextWrite(all()))
                .expectErrorMatches(error -> status(error, 409))
                .verify(WAIT);
        StepVerifier.create(spaceService.rootTree("tower"))
                .expectError(UnAuthorizedException.class)
                .verify(WAIT);
    }

    @Test
    void concurrentDictionaryCodesAndValuesHaveOneWinnerButDifferentTypesMayReuseValues() {
        oneWinner(
                Flux.merge(
                                dictionary.createType(type("custom", true, null)).materialize(),
                                dictionary.createType(type("custom", true, null)).materialize())
                        .contextWrite(all())
                        .collectList()
                        .block(WAIT));
        oneWinner(
                Flux.merge(
                                dictionary
                                        .createItem("custom", item("same", true, null))
                                        .materialize(),
                                dictionary
                                        .createItem("custom", item("same", true, null))
                                        .materialize())
                        .contextWrite(all())
                        .collectList()
                        .block(WAIT));
        dictionary.createType(type("another", true, null)).contextWrite(all()).block(WAIT);
        var independent =
                dictionary
                        .createItem("another", item("same", true, null))
                        .contextWrite(all())
                        .block(WAIT);
        assertEquals("same", independent.getValue());
        assertEquals("another", independent.getTypeCode());
        assertEquals(2, items.createQuery().where("value", "same").count().block(WAIT));
    }

    @Test
    void dictionaryIdentityIsImmutableAndDisabledSelectionsOnlyRetainExistingValue() {
        var kind =
                types.findById(PropertyDictionaryService.identity("type", "house-type"))
                        .block(WAIT);
        var residential =
                items.findById(PropertyDictionaryService.identity("house-type", "residential"))
                        .block(WAIT);
        StepVerifier.create(
                        dictionary
                                .updateType(kind.getId(), type("renamed-code", true, 0L))
                                .contextWrite(all()))
                .expectErrorMatches(error -> status(error, 400))
                .verify(WAIT);
        StepVerifier.create(
                        dictionary
                                .updateItem(residential.getId(), item("changed", true, 0L))
                                .contextWrite(all()))
                .expectErrorMatches(error -> status(error, 400))
                .verify(WAIT);
        StepVerifier.create(
                        dictionary
                                .updateItem(
                                        residential.getId(),
                                        new PropertyDictionaryService.ItemRequest(
                                                "residential", "住宅", true, 0L, "house-state"))
                                .contextWrite(all()))
                .expectErrorMatches(error -> status(error, 400))
                .verify(WAIT);
        houseService
                .save("project", "single", write("single", 0L, "10"))
                .contextWrite(all())
                .block(WAIT);
        dictionary
                .updateItem(residential.getId(), item("residential", false, 0L))
                .contextWrite(all())
                .block(WAIT);
        StepVerifier.create(
                        houseService
                                .save("project", "room", write("room", 0L, "20"))
                                .contextWrite(all()))
                .expectErrorMatches(error -> status(error, 409))
                .verify(WAIT);
        assertEquals(
                2L,
                houseService
                        .save("project", "single", write("single", 1L, "11"))
                        .contextWrite(all())
                        .block(WAIT)
                        .getVersion());
        assertFalse(
                dictionary.items("house-type").contextWrite(all()).block(WAIT).get(0).getEnabled());
        dictionary
                .updateItem(residential.getId(), item("residential", true, 1L))
                .contextWrite(all())
                .block(WAIT);
        dictionary.updateType(kind.getId(), type(null, false, 0L)).contextWrite(all()).block(WAIT);
        StepVerifier.create(
                        houseService
                                .save("project", "room", write("room", 0L, "20"))
                                .contextWrite(all()))
                .expectErrorMatches(error -> status(error, 409))
                .verify(WAIT);
        assertEquals(
                3L,
                houseService
                        .save("project", "single", write("single", 2L, "12"))
                        .contextWrite(all())
                        .block(WAIT)
                        .getVersion());
        assertEquals("house-type", types.findById(kind.getId()).block(WAIT).getCode());
    }

    @Test
    void billingAreaValidationAndSameVersionConcurrentUpdateProtectPersistedHouse() {
        var invalid =
                new ArrayList<BigDecimal>(
                        Arrays.asList(
                                null,
                                BigDecimal.ZERO,
                                new BigDecimal("-1"),
                                new BigDecimal("0.001"),
                                new BigDecimal("10000000000000000")));
        for (var area : invalid) {
            var input =
                    new PropertyHouseService.HouseWrite(
                            0L, "residential", "delivered", area, null, null, null, null, "single");
            StepVerifier.create(houseService.save("project", "single", input).contextWrite(all()))
                    .expectErrorMatches(error -> status(error, 400))
                    .verify(WAIT);
        }
        assertEquals(0, houses.createQuery().count().block(WAIT));
        houseService
                .save("project", "single", write("single", 0L, "10.00"))
                .contextWrite(all())
                .block(WAIT);
        oneWinner(
                Flux.merge(
                                houseService
                                        .save("project", "single", write("single", 1L, "21.25"))
                                        .materialize(),
                                houseService
                                        .save("project", "single", write("single", 1L, "31.50"))
                                        .materialize())
                        .contextWrite(all())
                        .collectList()
                        .block(WAIT));
        var saved = houses.findById("single").block(WAIT);
        assertEquals(2L, saved.getVersion());
        assertTrue(
                Set.of(new BigDecimal("21.25"), new BigDecimal("31.50"))
                        .contains(saved.getBillingArea()));
        assertEquals("tester", saved.getModifierId());
    }

    @Test
    void houseImportIsAtomicAndCannotOverwriteCompletedRows() {
        StepVerifier.create(
                        houseService
                                .importRows(
                                        "project",
                                        List.of(
                                                write("single", 0L, "10"),
                                                write("middle", 0L, "20")))
                                .contextWrite(all()))
                .expectError()
                .verify(WAIT);
        assertEquals(0, houses.createQuery().count().block(WAIT));
        assertEquals(
                2,
                houseService
                        .importRows(
                                "project",
                                List.of(write("single", 0L, "10"), write("room", 0L, "20")))
                        .contextWrite(all())
                        .block(WAIT)
                        .count());
        StepVerifier.create(
                        houseService
                                .importRows("project", List.of(write("single", 1L, "999")))
                                .contextWrite(all()))
                .expectErrorMatches(error -> status(error, 409))
                .verify(WAIT);
        assertEquals(
                0,
                new BigDecimal("10")
                        .compareTo(houses.findById("single").block(WAIT).getBillingArea()));
        assertEquals(1L, houses.findById("single").block(WAIT).getVersion());
    }

    @Test
    void clearedHouseHidesBusinessFieldsInDetailListAndExportAndCanBeCompletedAgain() {
        var input =
                new PropertyHouseService.HouseWrite(
                        0L,
                        "residential",
                        "delivered",
                        new BigDecimal("100"),
                        new BigDecimal("110"),
                        new BigDecimal("90"),
                        1000L,
                        "原备注",
                        "single");
        houseService.save("project", "single", input).contextWrite(all()).block(WAIT);
        houseService.delete("project", "single", 1).contextWrite(all()).block(WAIT);

        var detail = houseService.detail("project", "single").contextWrite(all()).block(WAIT);
        var listed =
                houseService
                        .query("project", "single", new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT)
                        .getData()
                        .get(0);
        var exported =
                houseService
                        .export("project", "single", new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT)
                        .get(0);
        for (var view : List.of(detail, listed, exported)) {
            assertFalse(view.getCompleted());
            assertEquals("single", view.getSpaceId());
            assertEquals("仅一栋", view.getName());
            assertNotNull(view.getFullName());
            assertEquals(2L, view.getVersion());
            assertNull(view.getHouseType());
            assertNull(view.getHouseState());
            assertNull(view.getBillingArea());
            assertNull(view.getBuildingArea());
            assertNull(view.getInsideArea());
            assertNull(view.getDeliveryDate());
            assertNull(view.getRemark());
        }
        // 逻辑删除保留原始资料；重新完善仍使用删除后的版本，拒绝旧页面覆盖。
        var deleted = houses.findById("single").block(WAIT);
        assertTrue(deleted.getDeleted());
        assertEquals("原备注", deleted.getRemark());
        StepVerifier.create(
                        houseService
                                .save("project", "single", write("single", 1L, "80"))
                                .contextWrite(all()))
                .expectErrorMatches(error -> status(error, 409))
                .verify(WAIT);
        var saved =
                houseService
                        .save("project", "single", write("single", detail.getVersion(), "80"))
                        .contextWrite(all())
                        .block(WAIT);
        assertTrue(saved.getCompleted());
        assertEquals(3L, saved.getVersion());
        assertEquals(0, new BigDecimal("80").compareTo(saved.getBillingArea()));
        assertNull(saved.getRemark());
    }

    @Test
    void activeCustomerPreventsDeleteThenSoftDeletePreservesSourceAndBindingCannotMoveUsedRoot() {
        houseService
                .save("project", "single", write("single", 0L, "10"))
                .contextWrite(all())
                .block(WAIT);
        var relation = new PropertyCustomerRelationshipEntity();
        relation.setId("active");
        relation.setProjectId("project");
        relation.setCustomerId("customer");
        relation.setSpaceId("single");
        relation.setStartTime(1000L);
        relation.setDeleted(false);
        relation.setVersion(0L);
        relationships.insert(relation).block(WAIT);
        StepVerifier.create(houseService.delete("project", "single", 1).contextWrite(all()))
                .expectErrorMatches(error -> status(error, 409))
                .verify(WAIT);
        assertFalse(houses.findById("single").block(WAIT).getDeleted());
        relationships
                .createUpdate()
                .set("endTime", 2000L)
                .where("id", "active")
                .execute()
                .block(WAIT);
        houseService.delete("project", "single", 1).contextWrite(all()).block(WAIT);
        assertTrue(houses.findById("single").block(WAIT).getDeleted());
        assertFalse(spaces.findById("single").block(WAIT).getDeleted());
        assertFalse(
                houseService
                        .detail("project", "single")
                        .contextWrite(all())
                        .block(WAIT)
                        .getCompleted());
        assertEquals(2L, houses.findById("single").block(WAIT).getVersion());
        StepVerifier.create(spaceService.bind("single", "other-project", 0).contextWrite(all()))
                .expectErrorMatches(error -> status(error, 409))
                .verify(WAIT);
        assertEquals("project", bindings.findById("single").block(WAIT).getOrganizationId());
    }

    @Test
    void dictionarySortDescriptionAndNullClearingArePersistedForTypesAndItems() {
        var first =
                dictionary
                        .createType(
                                new PropertyDictionaryService.TypeRequest(
                                        "z-sort", "前分类", true, null, -1L, "分类说明"))
                        .contextWrite(all())
                        .block(WAIT);
        dictionary
                .createType(
                        new PropertyDictionaryService.TypeRequest(
                                "a-sort", "后分类", true, null, 5L, null))
                .contextWrite(all())
                .block(WAIT);
        dictionary
                .createType(
                        new PropertyDictionaryService.TypeRequest(
                                "b-sort", "同序分类", true, null, 5L, null))
                .contextWrite(all())
                .block(WAIT);
        dictionary
                .createType(
                        new PropertyDictionaryService.TypeRequest(
                                "null-sort", "空序分类", true, null, null, null))
                .contextWrite(all())
                .block(WAIT);
        var ordered = dictionary.types().contextWrite(all()).block(WAIT);
        assertEquals("z-sort", ordered.get(0).getCode());
        assertEquals("null-sort", ordered.get(ordered.size() - 1).getCode());
        assertTrue(
                ordered.indexOf(
                                ordered.stream()
                                        .filter(t -> t.getCode().equals("a-sort"))
                                        .findFirst()
                                        .orElseThrow())
                        < ordered.indexOf(
                                ordered.stream()
                                        .filter(t -> t.getCode().equals("b-sort"))
                                        .findFirst()
                                        .orElseThrow()));
        assertEquals("分类说明", types.findById(first.getId()).block(WAIT).getDescription());
        var changed =
                dictionary
                        .updateType(
                                first.getId(),
                                new PropertyDictionaryService.TypeRequest(
                                        null, "更新名称", false, 0L, null, null))
                        .contextWrite(all())
                        .block(WAIT);
        assertNull(changed.getSortIndex());
        assertNull(changed.getDescription());
        assertEquals(1L, changed.getVersion());
        for (String value : List.of("b", "a")) {
            dictionary
                    .createItem(
                            "a-sort",
                            new PropertyDictionaryService.ItemRequest(
                                    value, value, true, null, null, 1L, "说明" + value))
                    .contextWrite(all())
                    .block(WAIT);
        }
        var last =
                dictionary
                        .createItem(
                                "a-sort",
                                new PropertyDictionaryService.ItemRequest(
                                        "c", "末位", true, null, null, null, "待清除"))
                        .contextWrite(all())
                        .block(WAIT);
        assertEquals(
                List.of("a", "b", "c"),
                dictionary.items("a-sort").contextWrite(all()).block(WAIT).stream()
                        .map(PropertyDictionaryItemEntity::getValue)
                        .toList());
        var item =
                dictionary
                        .updateItem(
                                last.getId(),
                                new PropertyDictionaryService.ItemRequest(
                                        null, "最前", true, 0L, null, -2L, null))
                        .contextWrite(all())
                        .block(WAIT);
        assertEquals(-2L, item.getSortIndex());
        assertNull(item.getDescription());
        assertEquals(
                "c", dictionary.items("a-sort").contextWrite(all()).block(WAIT).get(0).getValue());
        StepVerifier.create(
                        dictionary
                                .updateType(
                                        first.getId(),
                                        new PropertyDictionaryService.TypeRequest(
                                                null, "说明超长", true, 1L, 0L, "x".repeat(513)))
                                .contextWrite(all()))
                .expectErrorMatches(error -> status(error, 400))
                .verify(WAIT);
        StepVerifier.create(
                        dictionary
                                .updateItem(
                                        item.getId(),
                                        new PropertyDictionaryService.ItemRequest(
                                                null, "说明超长", true, 1L, null, 0L, "x".repeat(513)))
                                .contextWrite(all()))
                .expectErrorMatches(error -> status(error, 400))
                .verify(WAIT);
        assertEquals(
                0L,
                new PropertyDictionaryService.TypeRequest("old", "旧构造", true, null).sortIndex());
        assertEquals(
                0L,
                new PropertyDictionaryService.ItemRequest("old", "旧构造", true, null, null)
                        .sortIndex());
    }

    @Test
    void boundTypesCannotBeDeletedAndCustomTypeRequiresItsItemsToBeDeletedFirst() {
        dictionary.createType(type("customer-nature", true, null)).contextWrite(all()).block(WAIT);
        dictionary.createType(type("certificate-type", true, null)).contextWrite(all()).block(WAIT);
        for (String code :
                List.of("house-type", "house-state", "customer-nature", "certificate-type")) {
            String id = PropertyDictionaryService.identity("type", code);
            StepVerifier.create(dictionary.deleteType(id, 0).contextWrite(all()))
                    .expectErrorMatches(error -> status(error, 409))
                    .verify(WAIT);
            assertFalse(types.findById(id).block(WAIT).getDeleted());
            assertEquals(0L, types.findById(id).block(WAIT).getVersion());
        }
        var custom =
                dictionary
                        .createType(type("custom-delete", true, null))
                        .contextWrite(all())
                        .block(WAIT);
        var option =
                dictionary
                        .createItem("custom-delete", item("keep-code", false, null))
                        .contextWrite(all())
                        .block(WAIT);
        StepVerifier.create(dictionary.deleteType(custom.getId(), 0).contextWrite(all()))
                .expectErrorMatches(error -> status(error, 409))
                .verify(WAIT);
        dictionary.deleteItem(option.getId(), 0).contextWrite(all()).block(WAIT);
        assertTrue(items.findById(option.getId()).block(WAIT).getDeleted());
        assertTrue(dictionary.items("custom-delete").contextWrite(all()).block(WAIT).isEmpty());
        StepVerifier.create(
                        dictionary
                                .createItem("custom-delete", item("keep-code", true, null))
                                .contextWrite(all()))
                .expectErrorMatches(error -> status(error, 409))
                .verify(WAIT);
        dictionary.deleteType(custom.getId(), 0).contextWrite(all()).block(WAIT);
        assertTrue(types.findById(custom.getId()).block(WAIT).getDeleted());
        assertTrue(
                dictionary.types().contextWrite(all()).block(WAIT).stream()
                        .noneMatch(t -> t.getId().equals(custom.getId())));
        StepVerifier.create(
                        dictionary
                                .createType(type("custom-delete", true, null))
                                .contextWrite(all()))
                .expectErrorMatches(error -> status(error, 409))
                .verify(WAIT);
    }

    @Test
    void referencedItemsCannotBeDeletedEvenWhenTheirBusinessRowsAreHistoricalOrSoftDeleted() {
        houseService
                .save("project", "single", write("single", 0L, "10"))
                .contextWrite(all())
                .block(WAIT);
        houseService.delete("project", "single", 1).contextWrite(all()).block(WAIT);
        dictionary.createType(type("certificate-type", true, null)).contextWrite(all()).block(WAIT);
        dictionary.createType(type("customer-nature", true, null)).contextWrite(all()).block(WAIT);
        dictionary
                .createItem("certificate-type", item("passport", true, null))
                .contextWrite(all())
                .block(WAIT);
        dictionary
                .createItem("customer-nature", item("owner", true, null))
                .contextWrite(all())
                .block(WAIT);
        var customer = new PropertyCustomerEntity();
        customer.setId("deleted-customer");
        customer.setProjectId("project");
        customer.setUserId("historical-user");
        customer.setCustomerType("person");
        customer.setName("历史客户");
        customer.setTelephone("123");
        customer.setCertificateType("passport");
        customer.setVersion(0L);
        customer.setDeleted(true);
        customers.insert(customer).block(WAIT);
        var relation = new PropertyCustomerRelationshipEntity();
        relation.setId("ended-deleted");
        relation.setProjectId("project");
        relation.setCustomerId(customer.getId());
        relation.setSpaceId("single");
        relation.setNature("owner");
        relation.setStartTime(1000L);
        relation.setEndTime(2000L);
        relation.setVersion(0L);
        relation.setDeleted(true);
        relationships.insert(relation).block(WAIT);
        var references =
                Map.of(
                        "house-type",
                        "residential",
                        "house-state",
                        "delivered",
                        "certificate-type",
                        "passport",
                        "customer-nature",
                        "owner");
        for (var reference : references.entrySet()) {
            String id =
                    PropertyDictionaryService.identity(reference.getKey(), reference.getValue());
            StepVerifier.create(dictionary.deleteItem(id, 0).contextWrite(all()))
                    .expectErrorMatches(error -> status(error, 409))
                    .verify(WAIT);
            assertFalse(items.findById(id).block(WAIT).getDeleted());
            dictionary
                    .updateItem(id, item(reference.getValue(), false, 0L))
                    .contextWrite(all())
                    .block(WAIT);
            var resolved = dictionary.items(reference.getKey()).contextWrite(all()).block(WAIT);
            assertTrue(
                    resolved.stream()
                            .anyMatch(
                                    i ->
                                            i.getValue().equals(reference.getValue())
                                                    && !i.getEnabled()));
        }
        assertTrue(customers.findById(customer.getId()).block(WAIT).getDeleted());
        assertTrue(relationships.findById(relation.getId()).block(WAIT).getDeleted());
        assertTrue(houses.findById("single").block(WAIT).getDeleted());
    }

    private static PropertyHouseService.HouseWrite write(String space, Long version, String area) {
        return new PropertyHouseService.HouseWrite(
                version,
                "residential",
                "delivered",
                new BigDecimal(area),
                null,
                null,
                null,
                null,
                space);
    }

    private static PropertyDictionaryService.TypeRequest type(
            String code, boolean enabled, Long version) {
        return new PropertyDictionaryService.TypeRequest(code, "字典分类", enabled, version);
    }

    private static PropertyDictionaryService.ItemRequest item(
            String value, boolean enabled, Long version) {
        return new PropertyDictionaryService.ItemRequest(value, "选项", enabled, version, null);
    }

    private void organization(String id, OrganizationType kind) {
        var row = new PropertyOrganizationEntity();
        row.setId(id);
        row.setName(id);
        row.setOrganizationType(kind);
        row.setVersion(0L);
        row.setDeleted(false);
        organizations.insert(row).block(WAIT);
    }

    private void binding(String root, String project) {
        var row = new PropertySpaceBindingEntity();
        row.setId(root);
        row.setOrganizationId(project);
        row.setVersion(0L);
        row.setDeleted(false);
        bindings.insert(row).block(WAIT);
    }

    private static boolean status(Throwable error, int status) {
        return error instanceof BusinessException business && business.getStatus() == status;
    }

    private static void oneWinner(List<? extends Signal<?>> signals) {
        assertEquals(1, signals.stream().filter(Signal::isOnNext).count());
        assertEquals(
                1, signals.stream().filter(signal -> status(signal.getThrowable(), 409)).count());
    }

    private static Context all() {
        return access(SPACE_IDS, ORG_IDS);
    }

    @SuppressWarnings("unchecked")
    private static Context access(Set<String> allowedSpaces, Set<String> allowedOrganizations) {
        var holder = mock(AssetsHolder.class);
        when(holder.assertPermission(anyString(), anyCollection(), any()))
                .thenAnswer(
                        call -> {
                            String type = call.getArgument(0);
                            Collection<String> ids = call.getArgument(1);
                            Set<String> allowed =
                                    "space".equals(type)
                                            ? allowedSpaces
                                            : "organization".equals(type)
                                                    ? allowedOrganizations
                                                    : Set.of();
                            return allowed.containsAll(ids)
                                    ? Mono.empty()
                                    : Mono.error(new AccessDenyException.NoStackTrace());
                        });
        when(holder.filter(any(Flux.class), any(), any(Function.class), any()))
                .thenAnswer(
                        call -> {
                            Function<Object, String> id = call.getArgument(2);
                            return ((Flux<Object>) call.getArgument(0))
                                    .filter(
                                            row ->
                                                    (row instanceof PropertyOrganizationEntity
                                                                    ? allowedOrganizations
                                                                    : allowedSpaces)
                                                            .contains(id.apply(row)));
                        });
        var user = new SimpleUser();
        user.setId("tester");
        user.setUsername("测试人员");
        var auth = new SimpleAuthentication();
        auth.setUser(user);
        return Context.of(AssetsHolder.class, holder, Authentication.class, auth);
    }

    @Import({CustomerPersistenceTest.Config.class, PropertySpaceService.class, PropertyHouseCommandSupport.class, PropertyDictionaryCommandSupport.class, PropertySpaceCommandSupport.class})
    static class Config {
        @org.springframework.context.annotation.Bean
        SpaceOrganizationAssets organizationAssets() {
            var assets = org.mockito.Mockito.mock(SpaceOrganizationAssets.class);
            org.mockito.Mockito.when(assets.change(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.nullable(String.class), org.mockito.ArgumentMatchers.nullable(String.class)))
                    .thenReturn(reactor.core.publisher.Mono.empty());
            return assets;
        }
    }
}
