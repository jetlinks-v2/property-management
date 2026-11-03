package org.jetlinks.pro.property.base.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.hswebframework.web.crud.configuration.EasyormConfiguration;
import org.hswebframework.web.crud.configuration.EntityFactoryConfiguration;
import org.hswebframework.web.crud.configuration.R2dbcSqlExecutorConfiguration;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.command.register.CommandServiceEndpointRegister;
import org.jetlinks.pro.property.base.command.PropertyOrganizationCommandSupport;
import org.jetlinks.pro.property.base.command.QueryPropertyOrganizationTreeCommand;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.base.entity.OrganizationSyncStateEntity;
import org.jetlinks.pro.property.base.entity.PropertyOrganizationEntity;
import org.jetlinks.pro.property.base.organization.OrganizationType;
import org.jetlinks.sdk.server.auth.OrganizationInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.GenericContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用独立 PostgreSQL 容器验证真实事务、CAS 和权限边界，不触碰本机业务数据库。 */
@SpringBootTest(
        classes = OrganizationPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrganizationPersistenceTest {
    static final Duration TIMEOUT = Duration.ofSeconds(15);
    static final GenericContainer<?> POSTGRES =
            new GenericContainer<>("postgres:16-alpine")
                    .withEnv("POSTGRES_PASSWORD", "test-password")
                    .withEnv("POSTGRES_DB", "organization_test")
                    .withExposedPorts(5432);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add(
                "spring.r2dbc.url",
                () ->
                        "r2dbc:postgresql://127.0.0.1:"
                                + POSTGRES.getMappedPort(5432)
                                + "/organization_test");
        registry.add("spring.r2dbc.username", () -> "postgres");
        registry.add("spring.r2dbc.password", () -> "test-password");
        registry.add("easyorm.dialect", () -> "postgres");
        registry.add("easyorm.default-schema", () -> "public");
    }

    @Autowired ReactiveRepository<PropertyOrganizationEntity, String> organizations;
    @Autowired ReactiveRepository<OrganizationSyncStateEntity, String> states;
    @Autowired OrganizationSyncWriter writer;
    @Autowired OrganizationSyncService sync;
    @Autowired PropertyOrganizationService service;
    @Autowired SaasOrganizationSource source;

    @BeforeEach
    void resetData() {
        organizations
                .deleteById(
                        organizations.createQuery().fetch().map(PropertyOrganizationEntity::getId))
                .block(TIMEOUT);
        states.deleteById(OrganizationSyncService.STATE_ID).block(TIMEOUT);
        reset(source);
    }

    @Test
    void repeatRenameMoveDeleteRestoreNeverOverwriteType() {
        snapshot(org("parent", null, "公司"), org("room", "parent", "园区"));
        assertEquals(2, sync.synchronize().block(TIMEOUT).added());
        service.updateType("room", OrganizationType.project, 0)
                .contextWrite(access(Set.of("room")))
                .block(TIMEOUT);
        assertEquals(0, sync.synchronize().block(TIMEOUT).updated());
        snapshot(org("parent", null, "公司"), org("room", null, "新园区"));
        assertEquals(1, sync.synchronize().block(TIMEOUT).updated());
        var row = organizations.findById("room").block(TIMEOUT);
        assertEquals("新园区", row.getName());
        assertNull(row.getParentId());
        assertEquals(OrganizationType.project, row.getOrganizationType());
        assertEquals(1L, row.getVersion());
        snapshot(org("parent", null, "公司"));
        assertEquals(1, sync.synchronize().block(TIMEOUT).deleted());
        assertTrue(organizations.findById("room").block(TIMEOUT).getDeleted());
        StepVerifier.create(service.detail("room").contextWrite(access(Set.of("room"))))
                .expectErrorMatches(
                        error -> error instanceof BusinessException e && e.getStatus() == 404)
                .verify(TIMEOUT);
        snapshot(org("parent", null, "公司"), org("room", "parent", "恢复园区"));
        sync.synchronize().block(TIMEOUT);
        row = organizations.findById("room").block(TIMEOUT);
        assertFalse(row.getDeleted());
        assertEquals(OrganizationType.project, row.getOrganizationType());
        assertEquals(1L, row.getVersion());
    }

    @Test
    void malformedEmptyAndFailedSourcesRetainPreviousTreeAndReleaseLease() {
        snapshot(org("a", null, "已有组织"));
        sync.synchronize().block(TIMEOUT);
        for (Mono<List<OrganizationInfo>> input :
                List.of(
                        Mono.just(List.<OrganizationInfo>of()),
                        Mono.just(List.of(org("a", null, "甲"), org("a", null, "乙"))),
                        Mono.<List<OrganizationInfo>>error(
                                new IllegalStateException("upstream failed")))) {
            when(source.fetchAll()).thenReturn(input);
            StepVerifier.create(sync.synchronize()).expectError().verify(TIMEOUT);
            assertEquals("已有组织", organizations.findById("a").block(TIMEOUT).getName());
            var status = sync.status().block(TIMEOUT);
            assertFalse(status.running());
            assertNotNull(status.lastFailureTime());
        }
        snapshot(org("a", null, "恢复可用"));
        sync.synchronize().block(TIMEOUT);
        assertEquals("恢复可用", organizations.findById("a").block(TIMEOUT).getName());
    }

    @Test
    void writerRollsBackWholeRoundAndExpiredOwnerCannotCommit() {
        var lease = lease("owner", System.currentTimeMillis() + 60_000);
        states.insert(lease).block(TIMEOUT);
        var first = OrganizationSnapshot.from(List.of(org("first", null, "有效新增"))).get("first");
        var invalid = OrganizationSnapshot.from(List.of(org("invalid", null, "有效"))).get("invalid");
        invalid.setName("x".repeat(100));
        StepVerifier.create(writer.apply("owner", Map.of("first", first, "invalid", invalid)))
                .expectError()
                .verify(TIMEOUT);
        assertEquals(0, organizations.createQuery().count().block(TIMEOUT));
        assertNull(states.findById(lease.getId()).block(TIMEOUT).getLastSuccessTime());
        states.createUpdate()
                .set(OrganizationSyncStateEntity::getLeaseUntil, 0L)
                .where("id", lease.getId())
                .execute()
                .block(TIMEOUT);
        StepVerifier.create(writer.apply("owner", Map.of("first", first)))
                .expectErrorMatches(
                        error -> error instanceof BusinessException e && e.getStatus() == 409)
                .verify(TIMEOUT);
        assertEquals(0, organizations.createQuery().count().block(TIMEOUT));
    }

    @Test
    void parallelSynchronizationHasOneOwnerAndDoesNotInheritCallerContext()
            throws InterruptedException {
        var gate = Sinks.<List<OrganizationInfo>>one();
        var subscribed = new java.util.concurrent.CountDownLatch(1);
        when(source.fetchAll())
                .thenReturn(
                        Mono.deferContextual(
                                context -> {
                                    assertFalse(context.hasKey("caller-project"));
                                    return gate.asMono()
                                            .doOnSubscribe(ignored -> subscribed.countDown());
                                }));
        var first =
                sync.synchronize()
                        .contextWrite(Context.of("caller-project", "restricted"))
                        .toFuture();
        // 等待来源订阅表明第一个调用已取得租约，再验证第二个调用明确冲突。
        assertTrue(subscribed.await(15, java.util.concurrent.TimeUnit.SECONDS));
        StepVerifier.create(sync.synchronize())
                .expectErrorMatches(
                        error -> error instanceof BusinessException e && e.getStatus() == 409)
                .verify(TIMEOUT);
        gate.tryEmitValue(List.of(org("a", null, "组织")));
        assertEquals(1, first.join().added());
        assertFalse(sync.status().block(TIMEOUT).running());
    }

    @Test
    void typeCasHasOneWinnerAndClearingPersistsRealNullWithAudit() {
        snapshot(org("a", null, "组织"));
        sync.synchronize().block(TIMEOUT);
        var results =
                Flux.merge(
                                service.updateType("a", OrganizationType.project, 0).materialize(),
                                service.updateType("a", OrganizationType.external, 0).materialize())
                        .contextWrite(access(Set.of("a")))
                        .collectList()
                        .block(TIMEOUT);
        assertEquals(1, results.stream().filter(signal -> signal.isOnNext()).count());
        assertEquals(
                1,
                results.stream()
                        .filter(
                                signal ->
                                        signal.getThrowable() instanceof BusinessException e
                                                && e.getStatus() == 409)
                        .count());
        service.updateType("a", null, 1).contextWrite(access(Set.of("a"))).block(TIMEOUT);
        var row = organizations.findById("a").block(TIMEOUT);
        assertNull(row.getOrganizationType());
        assertEquals(2L, row.getVersion());
        assertEquals("tester", row.getModifierId());
        assertNotNull(row.getModifyTime());
    }

    @Test
    void assetScopeHidesParentsAndRejectsDetailAndMutationOutsideScope() {
        snapshot(org("hidden", null, "不可见公司"), org("visible", "hidden", "可见项目"));
        sync.synchronize().block(TIMEOUT);
        var tree = service.tree().contextWrite(access(Set.of("visible"))).block(TIMEOUT);
        assertEquals(1, tree.size());
        assertEquals("visible", tree.get(0).getId());
        assertTrue(tree.get(0).getChildren().isEmpty());
        StepVerifier.create(service.detail("hidden").contextWrite(access(Set.of("visible"))))
                .expectError(AccessDenyException.class)
                .verify(TIMEOUT);
        StepVerifier.create(
                        service.updateType("hidden", OrganizationType.project, 0)
                                .contextWrite(access(Set.of("visible"))))
                .expectError(AccessDenyException.class)
                .verify(TIMEOUT);
        StepVerifier.create(service.tree())
                .expectError(
                        org.hswebframework.web.authorization.exception.UnAuthorizedException.class)
                .verify(TIMEOUT);
        assertNull(organizations.findById("hidden").block(TIMEOUT).getOrganizationType());
    }

    @Test
    void authenticatedUnrestrictedPlatformContextMayHaveNoOrdinaryHolder() {
        snapshot(org("a", null, "平台组织"));
        sync.synchronize().block(TIMEOUT);
        var auth = new SimpleAuthentication();
        var user = new SimpleUser();
        user.setId("platform-admin");
        auth.setUser(user);
        StepVerifier.create(service.tree().contextWrite(Context.of(Authentication.class, auth)))
                .assertNext(tree -> assertEquals("a", tree.get(0).getId()))
                .verifyComplete();
    }

    @Test
    void organizationCommandReturnsFullHierarchyAndCurrentTypes() {
        snapshot(
                org("company", null, "重庆测试公司"),
                org("branch", "company", "茶园分公司"),
                org("park", "branch", "5G园区"),
                org("vendor", "company", "维保单位"));
        sync.synchronize().block(TIMEOUT);
        var allowed = access(Set.of("company", "branch", "park", "vendor"));
        service.updateType("park", OrganizationType.project, 0)
                .contextWrite(allowed)
                .block(TIMEOUT);
        service.updateType("vendor", OrganizationType.external, 0)
                .contextWrite(allowed)
                .block(TIMEOUT);

        StepVerifier.create(organizationTreeCommand().contextWrite(allowed))
                .assertNext(
                        company -> {
                            assertEquals("company", company.getId());
                            assertNull(company.getOrganizationType());
                            assertEquals(2, company.getChildren().size());
                            var branch = company.getChildren().get(0);
                            assertEquals("branch", branch.getId());
                            assertNull(branch.getOrganizationType());
                            var park = branch.getChildren().get(0);
                            assertEquals("5G园区", park.getName());
                            assertEquals("branch", park.getParentId());
                            assertEquals(OrganizationType.project, park.getOrganizationType());
                            assertEquals(
                                    OrganizationType.external,
                                    company.getChildren().get(1).getOrganizationType());
                        })
                .verifyComplete();

        service.updateType("park", null, 1).contextWrite(allowed).block(TIMEOUT);
        snapshot(
                org("company", null, "重庆测试公司"),
                org("branch", "company", "茶园分公司"),
                org("park", "branch", "5G园区"));
        sync.synchronize().block(TIMEOUT);
        StepVerifier.create(organizationTreeCommand().contextWrite(allowed))
                .assertNext(
                        company -> {
                            assertEquals(1, company.getChildren().size());
                            assertNull(
                                    company.getChildren()
                                            .get(0)
                                            .getChildren()
                                            .get(0)
                                            .getOrganizationType());
                        })
                .verifyComplete();
    }

    @Test
    void organizationCommandPreservesCallerAssetScopeAndRejectsAnonymous() {
        snapshot(org("company", null, "不可见公司"), org("park", "company", "可见项目"));
        sync.synchronize().block(TIMEOUT);
        StepVerifier.create(organizationTreeCommand().contextWrite(access(Set.of("park"))))
                .assertNext(
                        park -> {
                            assertEquals("park", park.getId());
                            assertEquals("可见项目", park.getName());
                            assertTrue(park.getChildren().isEmpty());
                        })
                .verifyComplete();
        StepVerifier.create(organizationTreeCommand().contextWrite(access(Set.of())))
                .verifyComplete();
        StepVerifier.create(organizationTreeCommand())
                .expectError(
                        org.hswebframework.web.authorization.exception.UnAuthorizedException.class)
                .verify(TIMEOUT);
    }

    @Test
    void organizationCommandRestoresTypedNestedResponse() {
        var command = new QueryPropertyOrganizationTreeCommand();
        var result =
                (PropertyOrganizationInfo)
                        command.createResponseData(
                                Map.of(
                                        "id",
                                        "company",
                                        "name",
                                        "重庆测试公司",
                                        "children",
                                        List.of(
                                                Map.of(
                                                        "id",
                                                        "park",
                                                        "name",
                                                        "5G园区",
                                                        "organizationType",
                                                        "project",
                                                        "children",
                                                        List.of()))));
        assertEquals("company", result.getId());
        assertNull(result.getOrganizationType());
        assertEquals("park", result.getChildren().get(0).getId());
        assertEquals(OrganizationType.project, result.getChildren().get(0).getOrganizationType());
    }

    private Flux<PropertyOrganizationInfo> organizationTreeCommand() {
        return CommandSupportManagerProviders.getCommandSupport(
                        QueryPropertyOrganizationTreeCommand.SERVICE_ID)
                .switchIfEmpty(
                        Mono.error(
                                new IllegalStateException("Organization command not registered")))
                .flatMapMany(
                        support -> support.execute(new QueryPropertyOrganizationTreeCommand()));
    }

    private void snapshot(OrganizationInfo... nodes) {
        when(source.fetchAll()).thenReturn(Mono.just(List.of(nodes)));
    }

    private static OrganizationInfo org(String id, String parent, String name) {
        var node = new OrganizationInfo();
        node.setId(id);
        node.setParentId(parent);
        node.setName(name);
        return node;
    }

    private static OrganizationSyncStateEntity lease(String owner, long until) {
        var lease = new OrganizationSyncStateEntity();
        lease.setId(OrganizationSyncService.STATE_ID);
        lease.setLeaseOwner(owner);
        lease.setLeaseUntil(until);
        return lease;
    }

    @SuppressWarnings("unchecked")
    private Context access(Set<String> allowed) {
        // 保留真实静态 Holder 解析与服务检查，仅替换外部资产授权结果。
        AssetsHolder holder = mock(AssetsHolder.class);
        when(holder.assertPermission(anyString(), anyCollection(), any()))
                .thenAnswer(
                        call -> {
                            var ids = (java.util.Collection<?>) call.getArgument(1);
                            return allowed.containsAll(ids)
                                    ? Mono.empty()
                                    : Mono.error(new AccessDenyException.NoStackTrace());
                        });
        when(holder.filter(any(Flux.class), any(), any(java.util.function.Function.class), any()))
                .thenAnswer(
                        call ->
                                ((Flux<PropertyOrganizationEntity>) call.getArgument(0))
                                        .filter(node -> allowed.contains(node.getId())));
        var user = new SimpleUser();
        user.setId("tester");
        user.setUsername("测试人员");
        var auth = new SimpleAuthentication();
        auth.setUser(user);
        return Context.of(AssetsHolder.class, holder, Authentication.class, auth);
    }

    @SpringBootConfiguration
    @Import({PropertyOrganizationCommandSupport.class, CommandServiceEndpointRegister.class})
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableEasyormRepository("org.jetlinks.pro.property.base.entity")
    @ImportAutoConfiguration({
        EasyormConfiguration.class,
        R2dbcSqlExecutorConfiguration.class,
        R2dbcAutoConfiguration.class,
        R2dbcTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        EntityFactoryConfiguration.class
    })
    static class Config {
        @Bean
        SaasOrganizationSource source() {
            return mock(SaasOrganizationSource.class);
        }

        @Bean
        OrganizationSyncWriter writer(
                ReactiveRepository<PropertyOrganizationEntity, String> orgs,
                ReactiveRepository<OrganizationSyncStateEntity, String> states) {
            return new OrganizationSyncWriter(orgs, states);
        }

        @Bean
        OrganizationSyncService sync(
                ReactiveRepository<OrganizationSyncStateEntity, String> states,
                SaasOrganizationSource source,
                OrganizationSyncWriter writer) {
            return new OrganizationSyncService(states, source, writer);
        }

        @Bean
        PropertyOrganizationService service(
                ReactiveRepository<PropertyOrganizationEntity, String> orgs,
                ReactiveRepository<org.jetlinks.pro.property.base.entity.PropertySpaceBindingEntity, String> bindings) {
            return new PropertyOrganizationService(orgs, bindings);
        }
    }
}
