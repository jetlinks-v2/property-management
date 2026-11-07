package org.jetlinks.pro.property.base.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.simple.*;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.property.base.command.*;
import org.jetlinks.pro.property.base.entity.*;
import org.jetlinks.pro.property.base.organization.OrganizationType;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.math.BigDecimal;
import java.util.*;

/** 真PG、真TenantAssetsHolder，不需要员工空间权限；本人关系不能越过租户。 */
@SpringBootTest(
        classes = OwnerHousePersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OwnerHousePersistenceTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        OrganizationPersistenceTest.database(r);
    }

    @Autowired PropertyOwnerHouseCommandSupport support;
    @Autowired AssetManager assets;
    @Autowired ReactiveRepository<PropertyUserEntity, String> users;
    @Autowired ReactiveRepository<PropertyCustomerEntity, String> customers;
    @Autowired ReactiveRepository<PropertyCustomerRelationshipEntity, String> links;
    @Autowired ReactiveRepository<PropertyOrganizationEntity, String> projects;
    @Autowired ReactiveRepository<PropertyHouseEntity, String> houses;
    @Autowired ReactiveRepository<PropertySpaceEntity, String> spaces;
    @Autowired ReactiveRepository<PropertySpaceBindingEntity, String> bindings;

    @BeforeEach
    void setup() {
        links.deleteById(links.createQuery().fetch().map(PropertyCustomerRelationshipEntity::getId))
                .block();
        customers
                .deleteById(customers.createQuery().fetch().map(PropertyCustomerEntity::getId))
                .block();
        houses.deleteById(houses.createQuery().fetch().map(PropertyHouseEntity::getId)).block();
        spaces.deleteById(spaces.createQuery().fetch().map(PropertySpaceEntity::getId)).block();
        bindings.deleteById(bindings.createQuery().fetch().map(PropertySpaceBindingEntity::getId))
                .block();
        projects.deleteById(projects.createQuery().fetch().map(PropertyOrganizationEntity::getId))
                .block();
        users.deleteById(users.createQuery().fetch().map(PropertyUserEntity::getId)).block();
        var user = new PropertyUserEntity();
        user.setId("owner");
        user.setUsername("owner");
        user.setName("住户");
        user.setStatus((byte) 1);
        user.setAccountType("user");
        user.setDeleted(false);
        user.setVersion(0L);
        users.save(user).block();
        var project = new PropertyOrganizationEntity();
        project.setId("project");
        project.setName("园区");
        project.setOrganizationType(OrganizationType.project);
        project.setDeleted(false);
        project.setVersion(0L);
        projects.save(project).block();
        var customer = new PropertyCustomerEntity();
        customer.setId("customer");
        customer.setProjectId("project");
        customer.setUserId("owner");
        customer.setCustomerType("person");
        customer.setName("住户");
        customer.setTelephone("10000000000");
        customer.setDeleted(false);
        customer.setVersion(0L);
        customers.save(customer).block();
        var root = new PropertySpaceEntity();
        root.setId("root");
        root.setRootId("root");
        root.setName("园区");
        root.setFullName("园区");
        root.setLeaf(false);
        root.setDeleted(false);
        spaces.save(root).block();
        var room = new PropertySpaceEntity();
        room.setId("room");
        room.setParentId("root");
        room.setRootId("root");
        room.setName("商铺");
        room.setFullName("园区/商铺");
        room.setLeaf(false);
        room.setDeleted(false);
        spaces.save(room).block();
        var binding = new PropertySpaceBindingEntity();
        binding.setId("root");
        binding.setOrganizationId("project");
        binding.setVersion(0L);
        binding.setDeleted(false);
        bindings.save(binding).block();
        var house = new PropertyHouseEntity();
        house.setId("room");
        house.setProjectId("project");
        house.setRootId("root");
        house.setHouseType("commercial");
        house.setHouseState("occupied");
        house.setBillingArea(new BigDecimal("100"));
        house.setDeleted(false);
        house.setVersion(0L);
        houses.save(house).block();
        var link = new PropertyCustomerRelationshipEntity();
        link.setId("link");
        link.setProjectId("project");
        link.setSpaceId("room");
        link.setCustomerId("customer");
        link.setStartTime(1L);
        link.setVersion(0L);
        link.setDeleted(false);
        links.save(link).block();
        reset(assets);
        when(assets.getAssets(any(AssetType.class), anyCollection()))
                .thenAnswer(
                        call -> {
                            Collection<?> ids = call.getArgument(1);
                            return Flux.fromIterable(ids)
                                    .filter(id -> id.equals("project"))
                                    .map(
                                            id ->
                                                    new DefaultAsset(
                                                                    "project",
                                                                    "园区",
                                                                    ArchiveScope.ORGANIZATION)
                                                            .withOption("tenantId", "tenant-a"));
                        });
    }

    Context auth(String id) {
        var u = new SimpleUser();
        u.setId(id);
        var a = new SimpleAuthentication();
        a.setUser(u);
        return Context.of(Authentication.class, a);
    }

    <T> T read(Mono<T> source, String user, String tenant) {
        return source.contextWrite(auth(user))
                .contextWrite(TenantHolder.makeCurrent(tenant))
                .block(OrganizationPersistenceTest.TIMEOUT);
    }

    @Test
    void ownNonLeafRoomHasOnlyOwnCustomerAndAncestors() {
        var result =
                read(support.current(new QueryCurrentPropertyHousesCommand()), "owner", "tenant-a");
        assertEquals(1, result.size());
        assertEquals("room", result.get(0).context().house().spaceId());
        assertEquals(
                List.of("root", "room"),
                result.get(0).ancestors().stream().map(s -> s.id()).toList());
        assertEquals(
                List.of("owner"),
                result.get(0).context().customers().stream().map(c -> c.userId()).toList());
    }

    @Test
    void foreignTenantAndOtherUserReturnNoRoom() {
        assertTrue(
                read(support.current(new QueryCurrentPropertyHousesCommand()), "owner", "tenant-b")
                        .isEmpty());
        assertTrue(
                read(
                                support.current(new QueryCurrentPropertyHousesCommand()),
                                "stranger",
                                "tenant-a")
                        .isEmpty());
    }

    @Test
    void endedDeletedFutureAndUnboundRelationshipsLoseAccess() {
        links.createUpdate().set("endTime", 2L).where("id", "link").execute().block();
        assertTrue(
                read(support.current(new QueryCurrentPropertyHousesCommand()), "owner", "tenant-a")
                        .isEmpty());
        links.createUpdate()
                .set("endTime", null)
                .set("startTime", Long.MAX_VALUE)
                .where("id", "link")
                .execute()
                .block();
        assertTrue(
                read(support.current(new QueryCurrentPropertyHousesCommand()), "owner", "tenant-a")
                        .isEmpty());
        links.createUpdate().set("startTime", 1L).where("id", "link").execute().block();
        bindings.createUpdate().set("deleted", true).where("id", "root").execute().block();
        assertTrue(
                read(support.current(new QueryCurrentPropertyHousesCommand()), "owner", "tenant-a")
                        .isEmpty());
    }

    @Test
    void anonymousCannotUseCommand() {
        StepVerifier.create(support.current(new QueryCurrentPropertyHousesCommand()))
                .expectError()
                .verify();
    }

    @Configuration
    @Import({OrganizationPersistenceTest.Config.class, PropertyOwnerHouseCommandSupport.class})
    static class Config {
        @Bean
        AssetManager assets() {
            return mock(AssetManager.class);
        }
    }
}
