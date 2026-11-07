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
import org.jetlinks.pro.property.base.command.PropertyCustomerCommandSupport;
import org.jetlinks.pro.property.base.command.QueryPropertyCustomersCommand;
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
    @Autowired PropertyCustomerCommandSupport directory;
    @Autowired PropertyDictionaryService dictionary;
    @Autowired PropertyHouseService houseService;
    @Autowired ReactiveRepository<PropertyCustomerEntity, String> customers;
    @Autowired ReactiveRepository<PropertyCustomerRelationshipEntity, String> relationships;
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
        customers
                .deleteById(customers.createQuery().fetch().map(PropertyCustomerEntity::getId))
                .block(WAIT);
    }

    @Test
    void nonLeafCustomerAssociationRequiresCompletedSpaceAndPreservesLeafFact() {
        StepVerifier.create(service.create("project", person("alice", "root")).contextWrite(all()))
                .expectErrorMatches(error -> error instanceof BusinessException e
                        && e.getMessage().equals("error.property_archive_house_incomplete"))
                .verify(WAIT);
        assertEquals(0, customers.createQuery().count().block(WAIT));
        for (var entry : Map.of("house-type", "residential", "house-state", "delivered").entrySet()) {
            dictionary.createType(new PropertyDictionaryService.TypeRequest(
                    entry.getKey(), entry.getKey(), true, null)).contextWrite(all()).block(WAIT);
            dictionary.createItem(entry.getKey(), new PropertyDictionaryService.ItemRequest(
                    entry.getValue(), entry.getValue(), true, null, null)).contextWrite(all()).block(WAIT);
        }
        houseService.save("project", "root", new PropertyHouseService.HouseWrite(
                0L, "residential", "delivered", BigDecimal.TEN, null, null, null, null, "root"))
                .contextWrite(all()).block(WAIT);
        var customer = service.create("project", person("alice", "root")).contextWrite(all()).block(WAIT);
        assertEquals("root", customer.getRelationships().get(0).getSpaceId());
        assertFalse(spaces.findById("root").block(WAIT).getLeaf());
        var another = service.create("project", person("bob", "one")).contextWrite(all()).block(WAIT);
        service.link("project", another.getId(), link("root")).contextWrite(all()).block(WAIT);
        assertEquals(2, relationships.createQuery().where("spaceId", "root").count().block(WAIT));
    }

    @Test
    void publishedDirectoryAggregatesOnlyReadableCurrentRelationships() {
        var alice = service.create("project", person("alice", "one")).contextWrite(all()).block(WAIT);
        service.link("project", alice.getId(), link("two")).contextWrite(all()).block(WAIT);
        service.create("project", person("bob", "two")).contextWrite(all()).block(WAIT);
        var query = new QueryPropertyCustomersCommand();
        query.setProjectId("project");
        var rows = directory.query(query).contextWrite(all()).collectList().block(WAIT);
        assertEquals(2, rows.size());
        assertEquals(Set.of("one", "two"), new HashSet<>(rows.stream()
                .filter(row -> "alice".equals(row.getUserId())).findFirst().orElseThrow().getSpaceIds()));
        var restricted = directory.query(query)
                .contextWrite(access(Set.of("one"), Set.of("alice"))).collectList().block(WAIT);
        assertEquals(1, restricted.size());
        assertEquals(List.of("one"), restricted.get(0).getSpaceIds());
        query.setSpaceId("two");
        StepVerifier.create(directory.query(query).contextWrite(access(Set.of("one"), Set.of("alice"))))
                .expectError(AccessDenyException.class).verify();
        query.setSpaceId("one");
        var relation = alice.getRelationships().get(0);
        service.deleteRelationship("project", relation.getId(), relation.getVersion()).contextWrite(all()).block(WAIT);
        assertEquals(0, directory.query(query).contextWrite(all()).count().block(WAIT));
    }

    private static CustomerRequests.Link link(String spaceId) {
        var request = new CustomerRequests.Link();
        request.setSpaceId(spaceId);
        request.setNature("owner");
        return request;
    }

    @Test
    void createDeleteReaddRecordsHistoryAndKeepsTheCustomerProfile() {
        long before = System.currentTimeMillis();
        var created =
                service.create("project", person("alice", "one")).contextWrite(all()).block(WAIT);
        var first = created.getRelationships().get(0);
        assertTrue(first.getStartTime() >= before);
        assertNull(first.getEndTime());
        service.deleteRelationship("project", first.getId(), first.getVersion())
                .contextWrite(all())
                .block(WAIT);
        assertEquals(
                0,
                service.query("project", "one", new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT)
                        .getTotal());
        var history =
                service.history("project", "one", new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT);
        assertEquals(1, history.getTotal());
        assertEquals(first.getId(), history.getData().get(0).getId());
        assertTrue(history.getData().get(0).getEndTime() >= first.getStartTime());
        var stored = relationships.findById(first.getId()).block(WAIT);
        assertTrue(stored.getDeleted());
        assertEquals(stored.getDeletedTime(), stored.getEndTime());
        assertEquals("tester", stored.getModifierId());
        assertFalse(customers.findById(created.getId()).block(WAIT).getDeleted());
        assertEquals(
                1,
                service.profiles("project", new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT)
                        .getTotal());
        var again =
                service.create("project", person("alice", "one")).contextWrite(all()).block(WAIT);
        assertEquals(created.getId(), again.getId());
        assertNotEquals(first.getId(), again.getRelationships().get(0).getId());
        assertEquals(
                1,
                service.history("project", "one", new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT)
                        .getTotal());
    }

    @Test
    void currentPaginationAndNatureArePerSpaceAndDeleteDoesNotTouchOtherSpaces() {
        tenantNature();
        var created =
                service.create("project", person("alice", "one")).contextWrite(all()).block(WAIT);
        var link = person("alice", "two").link();
        link.setNature("tenant");
        service.link("project", created.getId(), link).contextWrite(all()).block(WAIT);
        var page =
                service.query("project", null, new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT);
        assertEquals(2, page.getTotal());
        assertEquals(2, page.getData().stream().map(row -> row.getRecordId()).distinct().count());
        var tenant = new QueryParamEntity();
        tenant.and("nature", "eq", "tenant");
        assertEquals(
                1,
                service.query("project", null, tenant).contextWrite(all()).block(WAIT).getTotal());
        assertEquals(
                0,
                service.query("project", "one", tenant).contextWrite(all()).block(WAIT).getTotal());
        service.deleteRelationship("project", created.getRelationships().get(0).getId(), 0)
                .contextWrite(all())
                .block(WAIT);
        assertEquals(
                1,
                service.query("project", null, new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT)
                        .getTotal());
        assertEquals(
                "tenant",
                service.query("project", "two", new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT)
                        .getData()
                        .get(0)
                        .getRelationships()
                        .get(0)
                        .getNature());
        assertEquals(
                1,
                service.history("project", "one", new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT)
                        .getTotal());
    }

    @Test
    void editingSpaceNatureUsesBothVersionsAndRollsBackProfileOnConflict() {
        tenantNature();
        var created =
                service.create("project", person("alice", "one")).contextWrite(all()).block(WAIT);
        service.link("project", created.getId(), person("alice", "two").link())
                .contextWrite(all())
                .block(WAIT);
        var request = person("alice", "one");
        request.setName("本地修改");
        request.setVersion(0L);
        request.setRelationshipId(created.getRelationships().get(0).getId());
        request.setRelationshipVersion(0L);
        request.setNature("tenant");
        service.update("project", created.getId(), request).contextWrite(all()).block(WAIT);
        assertEquals(
                "tenant",
                service.query("project", "one", new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT)
                        .getData()
                        .get(0)
                        .getRelationships()
                        .get(0)
                        .getNature());
        assertEquals(
                "owner",
                service.query("project", "two", new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT)
                        .getData()
                        .get(0)
                        .getRelationships()
                        .get(0)
                        .getNature());
        request.setVersion(1L);
        request.setName("不能部分保存");
        StepVerifier.create(service.update("project", created.getId(), request).contextWrite(all()))
                .expectErrorMatches(CustomerPersistenceTest::conflict)
                .verify();
        assertEquals("本地修改", customers.findById(created.getId()).block(WAIT).getName());
    }

    @Test
    void sourceValuesPrefillOnlyOnFirstCreationAndSavedLocalValuesSurviveSourceChanges() {
        users.createUpdate()
                .set("telephone", "19123288779")
                .set("email", "first@example.com")
                .where("id", "alice")
                .execute()
                .block(WAIT);
        var created =
                service.create("project", person("alice", "one")).contextWrite(all()).block(WAIT);
        assertEquals("来源姓名", created.getName());
        assertEquals("19123288779", created.getTelephone());
        var edit = person("alice", "one");
        edit.setVersion(0L);
        edit.setName("本地客户姓名");
        edit.setTelephone("13800000000");
        edit.setEmail("local@example.com");
        edit.setNature(null);
        service.update("project", created.getId(), edit).contextWrite(all()).block(WAIT);
        users.createUpdate()
                .set("telephone", "13900000000")
                .set("name", "来源新姓名")
                .set("email", "source@example.com")
                .where("id", "alice")
                .execute()
                .block(WAIT);
        var reused =
                service.create("project", person("alice", "two")).contextWrite(all()).block(WAIT);
        assertEquals("本地客户姓名", reused.getName());
        assertEquals("13800000000", reused.getTelephone());
        assertEquals("local@example.com", reused.getEmail());
    }

    @Test
    void lastImportErrorDoesNotWriteAnyCustomerAndProfilesMustAgreeAcrossSpaces() {
        var second = person("bob", "two");
        second.setNature("missing");
        StepVerifier.create(
                        service.importCustomers("project", List.of(person("alice", "one"), second))
                                .contextWrite(all()))
                .expectErrorSatisfies(
                        error -> assertEquals(3, ((BusinessException) error).getArgs()[0]))
                .verify();
        assertEquals(0, customers.createQuery().count().block(WAIT));
        assertEquals(0, relationships.createQuery().count().block(WAIT));
        tenantNature();
        var same = person("alice", "two");
        same.setNature("tenant");
        assertEquals(
                2,
                service.importCustomers("project", List.of(person("alice", "one"), same))
                        .contextWrite(all())
                        .block(WAIT)
                        .count());
        assertEquals(1, customers.createQuery().count().block(WAIT));
    }

    @Test
    void inaccessibleSpaceCannotBeEditedDeletedReusedOrReadInHistory() {
        var created =
                service.create("project", person("alice", "one")).contextWrite(all()).block(WAIT);
        var record = created.getRelationships().get(0);
        var restricted = access(Set.of("two"), Set.of("alice"));
        StepVerifier.create(
                        service.deleteRelationship("project", record.getId(), 0)
                                .contextWrite(restricted))
                .expectError()
                .verify();
        StepVerifier.create(
                        service.create("project", person("alice", "two")).contextWrite(restricted))
                .expectError()
                .verify();
        assertEquals(
                0,
                service.profiles("project", new QueryParamEntity())
                        .contextWrite(restricted)
                        .block(WAIT)
                        .getTotal());
        service.deleteRelationship("project", record.getId(), 0).contextWrite(all()).block(WAIT);
        assertEquals(
                0,
                service.history("project", null, new QueryParamEntity())
                        .contextWrite(restricted)
                        .block(WAIT)
                        .getTotal());
    }

    @Test
    void duplicateConcurrentCreateHasOneWinnerAndStaleDeleteIsRejected() {
        var results =
                Flux.merge(
                                service.create("project", person("alice", "one"))
                                        .contextWrite(all())
                                        .map(v -> "ok")
                                        .onErrorReturn("conflict"),
                                service.create("project", person("alice", "one"))
                                        .contextWrite(all())
                                        .map(v -> "ok")
                                        .onErrorReturn("conflict"))
                        .collectList()
                        .block(WAIT);
        assertEquals(1, results.stream().filter("ok"::equals).count());
        var current =
                service.query("project", "one", new QueryParamEntity())
                        .contextWrite(all())
                        .block(WAIT)
                        .getData()
                        .get(0);
        var record = current.getRelationships().get(0);
        StepVerifier.create(
                        service.deleteRelationship("project", record.getId(), 9)
                                .contextWrite(all()))
                .expectErrorMatches(CustomerPersistenceTest::conflict)
                .verify();
        assertFalse(relationships.findById(record.getId()).block(WAIT).getDeleted());
        users.createUpdate().set("owner", false).where("id", "bob").execute().block(WAIT);
        StepVerifier.create(service.create("project", person("bob", "two")).contextWrite(all()))
                .expectError()
                .verify();
    }

    @Test
    void companyRejectsPersonalNatureAndCertificates() {
        var company = person("company", "one");
        company.setCustomerType("company");
        StepVerifier.create(service.create("project", company).contextWrite(all()))
                .expectError()
                .verify();
        company.setNature(null);
        company.setCertificateNumber("forbidden");
        StepVerifier.create(service.create("project", company).contextWrite(all()))
                .expectError()
                .verify();
        company.setCertificateNumber(null);
        assertEquals(
                "company",
                service.create("project", company)
                        .contextWrite(all())
                        .block(WAIT)
                        .getCustomerType());
    }

    private void tenantNature() {
        dictionary
                .createItem(
                        "customer-nature",
                        new PropertyDictionaryService.ItemRequest("tenant", "租户", true, null, null))
                .contextWrite(all())
                .block(WAIT);
    }

    private static boolean conflict(Throwable error) {
        return error instanceof BusinessException business && business.getStatus() == 409;
    }

    private static CustomerRequests.Create person(String user, String space) {
        var request = new CustomerRequests.Create();
        request.setUserId(user);
        request.setCustomerType("person");
        request.setName("补充姓名");
        request.setTelephone("123");
        request.setSpaceId(space);
        request.setNature("owner");
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
        PropertyCustomerService.class,
        PropertyCustomerCommandSupport.class
    })
    static class Config {}
}
