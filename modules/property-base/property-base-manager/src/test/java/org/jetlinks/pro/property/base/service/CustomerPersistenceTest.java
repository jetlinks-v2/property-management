package org.jetlinks.pro.property.base.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.property.base.customer.CustomerRequests;
import org.jetlinks.pro.property.base.entity.*;
import org.jetlinks.pro.property.base.organization.OrganizationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/** 真实 PostgreSQL、事务、项目行锁及 AssetsHolder 边界；仅外部资产授权结果替换。 */
@SpringBootTest(
        classes = CustomerPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CustomerPersistenceTest {
    private static final Duration WAIT = Duration.ofSeconds(20);

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        OrganizationPersistenceTest.database(registry);
    }

    @Autowired PropertyCustomerService service;
    @Autowired PropertyDictionaryService dictionary;
    @Autowired ReactiveRepository<PropertyCustomerEntity, String> customers;
    @Autowired ReactiveRepository<PropertyCustomerRelationshipEntity, String> relationships;
    @Autowired ReactiveRepository<PropertyCustomerFamilyEntity, String> families;
    @Autowired ReactiveRepository<PropertyOrganizationEntity, String> organizations;
    @Autowired ReactiveRepository<PropertySpaceEntity, String> spaces;
    @Autowired ReactiveRepository<PropertySpaceBindingEntity, String> bindings;
    @Autowired ReactiveRepository<PropertyHouseEntity, String> houses;
    @Autowired ReactiveRepository<PropertyUserEntity, String> users;
    @Autowired ReactiveRepository<PropertyDictionaryTypeEntity, String> types;
    @Autowired ReactiveRepository<PropertyDictionaryItemEntity, String> items;

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
        families.deleteById(families.createQuery().fetch().map(PropertyCustomerFamilyEntity::getId))
                .block(WAIT);
        organizations
                .deleteById(
                        organizations.createQuery().fetch().map(PropertyOrganizationEntity::getId))
                .block(WAIT);
        spaces.deleteById(spaces.createQuery().fetch().map(PropertySpaceEntity::getId)).block(WAIT);
        bindings.deleteById(bindings.createQuery().fetch().map(PropertySpaceBindingEntity::getId))
                .block(WAIT);
        houses.deleteById(houses.createQuery().fetch().map(PropertyHouseEntity::getId)).block(WAIT);
        users.deleteById(users.createQuery().fetch().map(PropertyUserEntity::getId)).block(WAIT);
        items.deleteById(items.createQuery().fetch().map(PropertyDictionaryItemEntity::getId))
                .block(WAIT);
        types.deleteById(types.createQuery().fetch().map(PropertyDictionaryTypeEntity::getId))
                .block(WAIT);
        var project = new PropertyOrganizationEntity();
        project.setId("project");
        project.setName("项目");
        project.setDeleted(false);
        project.setVersion(0L);
        project.setOrganizationType(OrganizationType.project);
        organizations.insert(project).block(WAIT);
        var binding = new PropertySpaceBindingEntity();
        binding.setId("root");
        binding.setOrganizationId("project");
        binding.setDeleted(false);
        binding.setVersion(0L);
        bindings.insert(binding).block(WAIT);
        for (String id : List.of("root", "one", "two")) {
            var node = new PropertySpaceEntity();
            node.setId(id);
            node.setName(id);
            node.setRootId("root");
            node.setParentId("root".equals(id) ? null : "root");
            node.setFullName("root/" + id);
            node.setDeleted(false);
            node.setLeaf(!"root".equals(id));
            spaces.insert(node).block(WAIT);
            if (!"root".equals(id)) {
                var house = new PropertyHouseEntity();
                house.setId(id);
                house.setRootId("root");
                house.setProjectId("project");
                house.setHouseType("residential");
                house.setHouseState("delivered");
                house.setBillingArea(new BigDecimal("100.00"));
                house.setDeleted(false);
                house.setVersion(0L);
                houses.insert(house).block(WAIT);
            }
        }
        for (String id : List.of("alice", "bob", "company")) {
            var user = new PropertyUserEntity();
            user.setId(id);
            user.setUsername(id);
            user.setName("来源姓名");
            user.setAccountType("user");
            user.setStatus((byte) 1);
            user.setOwner(true);
            users.insert(user).block(WAIT);
        }
        dictionary
                .createType(
                        new PropertyDictionaryService.TypeRequest(
                                "customer-nature", "客户性质", true, null))
                .contextWrite(all())
                .block(WAIT);
        dictionary
                .createItem(
                        "customer-nature",
                        new PropertyDictionaryService.ItemRequest("owner", "业主", true, null, null))
                .contextWrite(all())
                .block(WAIT);
    }

    @AfterEach
    void cleanCustomerData() {
        relationships
                .deleteById(
                        relationships
                                .createQuery()
                                .fetch()
                                .map(PropertyCustomerRelationshipEntity::getId))
                .block(WAIT);
        families.deleteById(families.createQuery().fetch().map(PropertyCustomerFamilyEntity::getId))
                .block(WAIT);
        customers
                .deleteById(customers.createQuery().fetch().map(PropertyCustomerEntity::getId))
                .block(WAIT);
    }

    @Test
    void incompleteCustomerIsVisibleAtProjectLevelAndCanBeCompletedAndBound() {
        var request = new CustomerRequests.Profile();
        request.setUserId("alice");
        request.setCustomerType("person");
        request.setName("待完善业主");
        var draft = service.createDraft("project", request).contextWrite(all()).block(WAIT);
        assertFalse(draft.isProfileComplete());
        assertTrue(draft.isPendingBinding());
        assertEquals("", draft.getTelephone());
        assertEquals(0, relationships.createQuery().count().block(WAIT));
        assertEquals(0, families.createQuery().count().block(WAIT));
        assertEquals(1, service.query("project", null, new QueryParamEntity())
                .contextWrite(all()).block(WAIT).getTotal());
        assertEquals(0, service.query("project", "one", new QueryParamEntity())
                .contextWrite(all()).block(WAIT).getTotal());
        assertTrue(service.detail("project", draft.getId()).contextWrite(all()).block(WAIT)
                .isPendingBinding());
        StepVerifier.create(service.createDraft("project", request).contextWrite(all()))
                .expectErrorMatches(CustomerPersistenceTest::conflict).verify(WAIT);

        var link = person("alice", "one", "导入分组").link();
        service.link("project", draft.getId(), link).contextWrite(all()).block(WAIT);
        assertFalse(service.detail("project", draft.getId()).contextWrite(all()).block(WAIT)
                .isPendingBinding());
        var completed = new CustomerRequests.Profile();
        completed.setVersion(0L);
        completed.setName(request.getName());
        completed.setTelephone("13800000000");
        service.update("project", draft.getId(), completed).contextWrite(all()).block(WAIT);
        assertTrue(service.detail("project", draft.getId()).contextWrite(all()).block(WAIT)
                .isProfileComplete());
    }

    @Test
    void draftVisibilityDoesNotBypassExistingSpaceOrUserPermissions() {
        var request = new CustomerRequests.Profile();
        request.setUserId("bob");
        request.setCustomerType("person");
        request.setName("待绑定业主");
        StepVerifier.create(service.createDraft("project", request)
                .contextWrite(access(Set.of("two"), Set.of("alice"))))
                .expectError(AccessDenyException.class).verify(WAIT);
        service.create("project", person("alice", "one", "家庭")).contextWrite(all()).block(WAIT);
        var draft = service.createDraft("project", request).contextWrite(all()).block(WAIT);
        var page = service.query("project", null, new QueryParamEntity())
                .contextWrite(access(Set.of("two"), Set.of("bob"))).block(WAIT);
        assertEquals(1, page.getTotal());
        assertEquals(draft.getId(), page.getData().get(0).getId());
        StepVerifier.create(service.query("other-project", null, new QueryParamEntity())
                .contextWrite(all())).expectError(AccessDenyException.class).verify(WAIT);
    }

    @Test
    void familyMembershipEndsSeparatelyAndHistoryRetainsDeletedCustomer() {
        var alice =
                service.create("project", person("alice", "one", "甲家庭"))
                        .contextWrite(all())
                        .block(WAIT);
        String family = alice.getRelationships().get(0).getFamilyId();
        var bobRequest = person("bob", "one", "ignored");
        bobRequest.setFamilyMode("existing");
        bobRequest.setFamilyName(null);
        bobRequest.setFamilyId(family);
        var bob = service.create("project", bobRequest).contextWrite(all()).block(WAIT);
        assertEquals(1, service.families("project", "one").contextWrite(all()).block(WAIT).size());
        assertEquals(family, service.familyReferences("project").contextWrite(all()).block(WAIT).get("one").get(0).id());
        assertTrue(service.familyReferences("project").contextWrite(access(Set.of("two"), Set.of("alice", "bob"))).block(WAIT).isEmpty());
        var ended =
                service.end(
                                "project",
                                alice.getRelationships().get(0).getId(),
                                new CustomerRequests.End(0L, 2000L, "搬离"))
                        .contextWrite(all())
                        .block(WAIT);
        assertEquals(2000L, ended.getEndTime());
        assertEquals("tester", relationships.findById(ended.getId()).block(WAIT).getModifierId());
        assertNotNull(relationships.findById(ended.getId()).block(WAIT).getModifyTime());
        assertEquals(1, service.families("project", "one").contextWrite(all()).block(WAIT).size());
        var current =
                service.query("project", "one", new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT);
        assertEquals(2, current.getTotal());
        assertTrue(
                current.getData().stream()
                        .filter(customer -> customer.getId().equals(alice.getId()))
                        .findFirst()
                        .orElseThrow()
                        .getRelationships()
                        .isEmpty());
        service.delete("project", alice.getId(), 0).contextWrite(all()).block(WAIT);
        var history =
                service.history("project", "one", new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT);
        assertEquals(1, history.getTotal());
        assertEquals("alice本地", history.getData().get(0).getCustomer().getName());
        assertTrue(history.getData().get(0).getCustomer().getDeleted());
        assertEquals("tester", customers.findById(alice.getId()).block(WAIT).getModifierId());
        assertNotNull(customers.findById(alice.getId()).block(WAIT).getDeletedTime());
        service.end(
                        "project",
                        bob.getRelationships().get(0).getId(),
                        new CustomerRequests.End(0L, 3000L, "搬离"))
                .contextWrite(all())
                .block(WAIT);
        assertTrue(service.families("project", "one").contextWrite(all()).block(WAIT).isEmpty());
        assertTrue(service.familyReferences("project").contextWrite(all()).block(WAIT).isEmpty());
        assertEquals(
                2,
                service.exportHistory("project", null, new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT)
                        .size());
    }

    @Test
    void recreatingDeletedProfileRestoresOnlyTheProfileAndAddsANewRelationship() {
        var first =
                service.create("project", person("alice", "one", "旧家庭"))
                        .contextWrite(all())
                        .block(WAIT);
        var oldLink = first.getRelationships().get(0);
        service.end("project", oldLink.getId(), new CustomerRequests.End(0L, 2000L, "搬离"))
                .contextWrite(all())
                .block(WAIT);
        service.delete("project", first.getId(), 0).contextWrite(all()).block(WAIT);
        var request = person("alice", "two", "新家庭");
        request.setName("重新建档姓名");
        request.setStartTime(3000L);
        var restored = service.create("project", request).contextWrite(all()).block(WAIT);
        assertEquals(first.getId(), restored.getId());
        assertEquals("重新建档姓名", restored.getName());
        assertFalse(restored.getDeleted());
        assertNull(customers.findById(first.getId()).block(WAIT).getDeletedTime());
        assertEquals(2L, restored.getVersion());
        assertEquals(1, customers.createQuery().count().block(WAIT));
        assertEquals(1, restored.getRelationships().size());
        assertEquals("two", restored.getRelationships().get(0).getSpaceId());
        assertNotEquals(oldLink.getId(), restored.getRelationships().get(0).getId());
        assertEquals(2000L, relationships.findById(oldLink.getId()).block(WAIT).getEndTime());
        assertEquals(
                1,
                service.history("project", null, new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT)
                        .getTotal());
        StepVerifier.create(service.create("project", request).contextWrite(all()))
                .expectErrorMatches(CustomerPersistenceTest::conflict)
                .verify(WAIT);
    }

    @Test
    void restoringDeletedProfileRequiresOriginalScopeAndRollsBackIfNewLinkIsInvalid() {
        var first =
                service.create("project", person("alice", "one", "旧家庭"))
                        .contextWrite(all())
                        .block(WAIT);
        service.deleteRelationship("project", first.getRelationships().get(0).getId(), 0)
                .contextWrite(all())
                .block(WAIT);
        service.delete("project", first.getId(), 0).contextWrite(all()).block(WAIT);
        StepVerifier.create(
                        service.create("project", person("alice", "two", "不允许认领"))
                                .contextWrite(access(Set.of("two"), Set.of("alice"))))
                .expectErrorMatches(
                        error ->
                                error instanceof BusinessException business
                                        && business.getStatus() == 404)
                .verify(WAIT);
        StepVerifier.create(
                        service.create("project", person("alice", "root", "非法房屋"))
                                .contextWrite(all()))
                .expectError()
                .verify(WAIT);
        assertTrue(customers.findById(first.getId()).block(WAIT).getDeleted());
        assertEquals(1L, customers.findById(first.getId()).block(WAIT).getVersion());
        assertEquals(1, relationships.createQuery().count().block(WAIT));
        var restored =
                service.create("project", person("alice", "one", "新家庭"))
                        .contextWrite(all())
                        .block(WAIT);
        assertFalse(restored.getDeleted());
        assertTrue(
                relationships
                        .findById(first.getRelationships().get(0).getId())
                        .block(WAIT)
                        .getDeleted());
    }

    @Test
    void reuseAcrossRoomsDeduplicatesMainAndDoesNotExposeUnpermittedRelationships() {
        var first =
                service.create("project", person("alice", "one", "家庭1"))
                        .contextWrite(all())
                        .block(WAIT);
        var request = person("alice", "two", "家庭2");
        request.setName("不应覆盖");
        assertEquals(
                1,
                service.importCustomers("project", List.of(request))
                        .contextWrite(all())
                        .block(WAIT)
                        .count());
        assertEquals(1, customers.createQuery().count().block(WAIT));
        var page =
                service.query("project", null, new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT);
        assertEquals(1, page.getTotal());
        assertEquals(2, page.getData().get(0).getRelationships().size());
        assertEquals("alice本地", page.getData().get(0).getName());
        var scoped =
                service.detail("project", first.getId())
                        .contextWrite(access(Set.of("one"), Set.of("alice")))
                        .block(WAIT);
        assertEquals(1, scoped.getRelationships().size());
        assertEquals("one", scoped.getRelationships().get(0).getSpaceId());
        StepVerifier.create(
                        service.query("project", "two", new QueryParamEntity())
                                .contextWrite(access(Set.of("one"), Set.of("alice"))))
                .expectError(AccessDenyException.class)
                .verify(WAIT);
        assertEquals(
                0,
                service.query("project", null, new QueryParamEntity())
                        .contextWrite(access(Set.of(), Set.of("alice")))
                        .block(WAIT)
                        .getTotal());
        StepVerifier.create(service.delete("project", first.getId(), 0).contextWrite(all()))
                .expectErrorMatches(CustomerPersistenceTest::conflict)
                .verify(WAIT);
    }

    @Test
    void importingAVisibleAccountCannotClaimAnInaccessibleExistingProfile() {
        service.create("project", person("alice", "one", "仅一房")).contextWrite(all()).block(WAIT);
        StepVerifier.create(
                        service.importCustomers("project", List.of(person("alice", "two", "不可认领")))
                                .contextWrite(access(Set.of("two"), Set.of("alice"))))
                .expectErrorMatches(
                        error ->
                                error instanceof BusinessException business
                                        && business.getStatus() == 404)
                .verify(WAIT);
        assertEquals(1, relationships.createQuery().count().block(WAIT));
        assertEquals(
                0,
                service.query("project", null, new QueryParamEntity())
                        .contextWrite(access(Set.of("two"), Set.of("alice")))
                        .block(WAIT)
                        .getTotal());
    }

    @Test
    void invalidImportRollsBackMainFamilyAndEarlierRowsAndExplicitGroupsShareOnlyWithinFile() {
        var first = person("alice", "one", "同家庭");
        first.setFamilyGroup("file-group");
        var second = person("bob", "one", "同家庭");
        second.setFamilyGroup("file-group");
        var invalid = person("company", "root", "非末端");
        StepVerifier.create(
                        service.importCustomers("project", List.of(first, invalid))
                                .contextWrite(all()))
                .expectError()
                .verify(WAIT);
        assertEquals(0, customers.createQuery().count().block(WAIT));
        assertEquals(0, families.createQuery().count().block(WAIT));
        assertEquals(0, relationships.createQuery().count().block(WAIT));
        service.importCustomers("project", List.of(first, second)).contextWrite(all()).block(WAIT);
        assertEquals(1, families.createQuery().count().block(WAIT));
        assertEquals(
                1,
                relationships
                        .createQuery()
                        .fetch()
                        .map(PropertyCustomerRelationshipEntity::getFamilyId)
                        .distinct()
                        .count()
                        .block(WAIT));
    }

    @Test
    void companyBoundaryVersionAndMistakenLinkDeletionArePersisted() {
        var company = person("company", "one", "bad");
        company.setCustomerType("company");
        StepVerifier.create(service.create("project", company).contextWrite(all()))
                .expectError(BusinessException.class)
                .verify(WAIT);
        company.setNature(null);
        company.setFamilyMode(null);
        company.setFamilyName(null);
        company.setCreditCode("credit");
        var saved = service.create("project", company).contextWrite(all()).block(WAIT);
        var changed = new CustomerRequests.Profile();
        changed.setName("本地企业");
        changed.setTelephone("123");
        changed.setCreditCode("new-credit");
        changed.setVersion(0L);
        service.update("project", saved.getId(), changed).contextWrite(all()).block(WAIT);
        StepVerifier.create(service.update("project", saved.getId(), changed).contextWrite(all()))
                .expectErrorMatches(CustomerPersistenceTest::conflict)
                .verify(WAIT);
        assertEquals("new-credit", customers.findById(saved.getId()).block(WAIT).getCreditCode());
        assertEquals("tester", customers.findById(saved.getId()).block(WAIT).getModifierId());
        String relationshipId = saved.getRelationships().get(0).getId();
        service.deleteRelationship("project", relationshipId, 0).contextWrite(all()).block(WAIT);
        var link = relationships.findById(relationshipId).block(WAIT);
        assertTrue(link.getDeleted());
        assertNull(link.getEndTime());
        assertNotNull(link.getDeletedTime());
        assertEquals("tester", link.getModifierId());
        assertEquals(
                0,
                service.history("project", null, new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT)
                        .getTotal());
        var visible =
                service.query("project", null, new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT);
        assertEquals(1, visible.getTotal());
        assertTrue(visible.getData().get(0).getRelationships().isEmpty());
        service.delete("project", saved.getId(), 1).contextWrite(all()).block(WAIT);
        assertTrue(customers.findById(saved.getId()).block(WAIT).getDeleted());
    }

    @Test
    void userAssetAndActiveOwnerAreCheckedAndConcurrentCreateHasOneWinner() {
        StepVerifier.create(
                        service.create("project", person("alice", "one", "家庭"))
                                .contextWrite(access(Set.of("one"), Set.of("bob"))))
                .expectError(AccessDenyException.class)
                .verify(WAIT);
        users.createUpdate().set("owner", false).where("id", "alice").execute().block(WAIT);
        StepVerifier.create(
                        service.create("project", person("alice", "one", "家庭")).contextWrite(all()))
                .expectError(BusinessException.class)
                .verify(WAIT);
        users.createUpdate().set("owner", true).where("id", "alice").execute().block(WAIT);
        var signals =
                Flux.merge(
                                service.create("project", person("alice", "one", "家庭"))
                                        .materialize(),
                                service.create("project", person("alice", "two", "另一家庭"))
                                        .materialize())
                        .contextWrite(all())
                        .collectList()
                        .block(WAIT);
        assertEquals(1, signals.stream().filter(signal -> signal.isOnNext()).count());
        assertEquals(1, signals.stream().filter(signal -> conflict(signal.getThrowable())).count());
        assertEquals(1, customers.createQuery().count().block(WAIT));
        assertEquals(1, relationships.createQuery().count().block(WAIT));
    }

    @Test
    void natureFilteringUsesOnlyCurrentRelationshipsInsideTheAuthorizedSelectedScope() {
        dictionary
                .createItem(
                        "customer-nature",
                        new PropertyDictionaryService.ItemRequest("tenant", "租户", true, null, null))
                .contextWrite(all())
                .block(WAIT);
        var alice =
                service.create("project", person("alice", "one", "家庭1"))
                        .contextWrite(all())
                        .block(WAIT);
        var tenantRequest = person("alice", "two", "家庭2");
        tenantRequest.setNature("tenant");
        service.importCustomers("project", List.of(tenantRequest)).contextWrite(all()).block(WAIT);

        var tenant = new QueryParamEntity();
        tenant.and("nature", "eq", "tenant");
        assertEquals(
                1,
                service.query("project", null, tenant).contextWrite(all()).block(WAIT).getTotal());
        assertEquals(
                1,
                service.query("project", "two", tenant).contextWrite(all()).block(WAIT).getTotal());
        assertEquals(
                0,
                service.query("project", "one", tenant).contextWrite(all()).block(WAIT).getTotal());
        assertEquals(
                0,
                service.query("project", null, tenant)
                        .contextWrite(access(Set.of("one"), Set.of("alice")))
                        .block(WAIT)
                        .getTotal());

        var anyNature = new QueryParamEntity();
        anyNature.and("nature", "in", List.of("tenant", "unavailable"));
        assertEquals(
                1,
                service.export("project", null, anyNature).contextWrite(all()).block(WAIT).size());
        assertTrue(
                service.export("project", "one", anyNature)
                        .contextWrite(all())
                        .block(WAIT)
                        .isEmpty());
        var noTenant = new QueryParamEntity();
        noTenant.and("nature", "not", "tenant");
        assertEquals(
                0,
                service.query("project", null, noTenant)
                        .contextWrite(all())
                        .block(WAIT)
                        .getTotal());
        assertEquals(
                1,
                service.query("project", "one", noTenant)
                        .contextWrite(all())
                        .block(WAIT)
                        .getTotal());

        var tenantRelationship =
                service
                        .detail("project", alice.getId())
                        .contextWrite(all())
                        .block(WAIT)
                        .getRelationships()
                        .stream()
                        .filter(row -> "two".equals(row.getSpaceId()))
                        .findFirst()
                        .orElseThrow();
        service.end(
                        "project",
                        tenantRelationship.getId(),
                        new CustomerRequests.End(0L, 2000L, "搬离"))
                .contextWrite(all())
                .block(WAIT);
        assertEquals(
                0,
                service.query("project", null, tenant).contextWrite(all()).block(WAIT).getTotal());
        assertEquals(
                0,
                service.query("project", "two", tenant).contextWrite(all()).block(WAIT).getTotal());
        assertEquals(
                1,
                service.query("project", null, noTenant)
                        .contextWrite(all())
                        .block(WAIT)
                        .getTotal());
        assertEquals(
                List.of("owner"),
                service.detail("project", alice.getId())
                        .contextWrite(all())
                        .block(WAIT)
                        .getNature());
    }

    private static boolean conflict(Throwable error) {
        return error instanceof BusinessException business && business.getStatus() == 409;
    }

    private static CustomerRequests.Create person(String user, String space, String family) {
        var request = new CustomerRequests.Create();
        request.setUserId(user);
        request.setCustomerType("person");
        request.setName(user + "本地");
        request.setTelephone("123");
        request.setSpaceId(space);
        request.setNature("owner");
        request.setFamilyMode("new");
        request.setFamilyName(family);
        request.setStartTime(1000L);
        return request;
    }

    private static Context all() {
        return access(Set.of("root", "one", "two"), Set.of("alice", "bob", "company"));
    }

    @SuppressWarnings("unchecked")
    private static Context access(Set<String> allowedSpaces, Set<String> allowedUsers) {
        var holder = mock(AssetsHolder.class);
        when(holder.assertPermission(anyString(), anyCollection(), any()))
                .thenAnswer(
                        call -> {
                            String type = call.getArgument(0);
                            Collection<String> ids = call.getArgument(1);
                            Set<String> allowed =
                                    "space".equals(type)
                                            ? allowedSpaces
                                            : "user".equals(type)
                                                    ? allowedUsers
                                                    : Set.of("project");
                            return allowed.containsAll(ids)
                                    ? Mono.empty()
                                    : Mono.error(new AccessDenyException.NoStackTrace());
                        });
        when(holder.filter(any(Flux.class), any(), any(Function.class), any()))
                .thenAnswer(
                        call -> {
                            Function<Object, String> id = call.getArgument(2);
                            return ((Flux<Object>) call.getArgument(0))
                                    .filter(row -> allowedSpaces.contains(id.apply(row)));
                        });
        var user = new SimpleUser();
        user.setId("tester");
        user.setUsername("测试人员");
        var auth = new SimpleAuthentication();
        auth.setUser(user);
        return Context.of(AssetsHolder.class, holder, Authentication.class, auth);
    }

    @Import({
        OrganizationPersistenceTest.Config.class,
        ArchiveScope.class,
        PropertyDictionaryService.class,
        PropertyHouseService.class,
        PropertyCustomerService.class
    })
    static class Config {}
}
