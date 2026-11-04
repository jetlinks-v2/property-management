package org.jetlinks.pro.property.base.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.property.base.entity.*;
import org.jetlinks.pro.property.base.user.UserIdentity;
import org.jetlinks.pro.property.base.user.UserOrganizationTerm;
import org.jetlinks.sdk.server.auth.UserDetail;
import org.jetlinks.sdk.server.auth.OrganizationInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实 PostgreSQL/事务/ORM 验证镜像与身份隔离；来源仅替换命令边界。 */
@SpringBootTest(
        classes = UserPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class UserPersistenceTest {
    static final Duration WAIT = Duration.ofSeconds(20);

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        OrganizationPersistenceTest.database(registry);
    }

    @Autowired ReactiveRepository<PropertyUserEntity, String> users;
    @Autowired ReactiveRepository<PropertyUserOrganizationEntity, String> orgs;
    @Autowired ReactiveRepository<UserSyncStateEntity, String> states;
    @Autowired ReactiveRepository<PropertyOrganizationEntity, String> tree;
    @Autowired PropertyUserService service;
    @Autowired UserSyncService sync;
    @Autowired UserSyncWriter writer;
    @Autowired SaasUserSource source;

    @BeforeEach
    void resetData() {
        users.deleteById(users.createQuery().fetch().map(PropertyUserEntity::getId)).block(WAIT);
        orgs.deleteById(orgs.createQuery().fetch().map(PropertyUserOrganizationEntity::getId))
                .block(WAIT);
        states.deleteById(UserSyncService.STATE_ID).block(WAIT);
        tree.deleteById(tree.createQuery().fetch().map(PropertyOrganizationEntity::getId))
                .block(WAIT);
        reset(source);
        when(source.find(anyString())).thenReturn(Mono.just(Optional.empty()));
    }

    @Test
    void identityAuditStoresDisplayNameAndFallsBackToUsername() {
        snapshot(user("a"));
        sync.synchronize().block(WAIT);
        var context = access(Set.of("a"));
        var actor = (SimpleUser) context.get(Authentication.class).getUser();
        actor.setUsername("pm_account");
        actor.setName("超级管理员");

        var saved =
                service.updateIdentities("a", Set.of(UserIdentity.owner), 0)
                        .contextWrite(context)
                        .block(WAIT);
        assertEquals("超级管理员", saved.getModifierName());
        assertEquals("超级管理员", users.findById("a").block(WAIT).getModifierName());

        actor.setName(" ");
        saved = service.updateIdentities("a", Set.of(), 1).contextWrite(context).block(WAIT);
        assertEquals("pm_account", saved.getModifierName());
    }

    @Test
    void legacyAuditNamesResolveInDetailAndPageWithoutChangingStoredSnapshots() {
        var actor = user("actor");
        actor.setName("超级管理员");
        snapshot(user("a"), user("b"), user("c"), user("d"), actor);
        sync.synchronize().block(WAIT);
        for (String id : List.of("a", "b", "c")) {
            users.createUpdate()
                    .set("modifierId", "actor")
                    .set("modifierName", id.equals("a") ? "account-actor" : "历史姓名")
                    .where("id", id)
                    .execute()
                    .block(WAIT);
        }
        users.createUpdate().setNull("modifierName").where("id", "c").execute().block(WAIT);
        users.createUpdate()
                .set("modifierId", "missing")
                .set("modifierName", "原账号")
                .where("id", "d")
                .execute()
                .block(WAIT);

        // 只允许读取目标用户；审计姓名补全不能暴露操作人的账号详情或扩大列表范围。
        var context = access(Set.of("a", "b", "c", "d"));
        assertEquals(
                "超级管理员", service.detail("a").contextWrite(context).block(WAIT).getModifierName());
        var page = service.query(new QueryParamEntity()).contextWrite(context).block(WAIT);
        assertEquals(4, page.getTotal());
        var names =
                page.getData().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        PropertyUserEntity::getId,
                                        PropertyUserEntity::getModifierName));
        assertEquals("超级管理员", names.get("a"));
        assertEquals("历史姓名", names.get("b"));
        assertEquals("超级管理员", names.get("c"));
        assertEquals("原账号", names.get("d"));
        assertEquals("account-actor", users.findById("a").block(WAIT).getModifierName());
        assertNull(users.findById("c").block(WAIT).getModifierName());
    }

    @Test
    void dualIdentityClearAndSourceRenameNeverOverwriteLocalVersion() {
        var a = user("a");
        a.setTelephone("123");
        a.setEmail("a@example.com");
        snapshot(a, user("b"));
        sync.synchronize().block(WAIT);
        assertEquals(0, sync.synchronize().block(WAIT).updated());
        var saved =
                service.updateIdentities("a", Set.of(UserIdentity.owner, UserIdentity.employee), 0)
                        .contextWrite(access(Set.of("a", "b")))
                        .block(WAIT);
        assertEquals(2, saved.getIdentities().size());
        assertEquals("tester", saved.getModifierId());
        a = user("a");
        a.setName("修改姓名");
        snapshot(a, user("b"));
        sync.synchronize().block(WAIT);
        var updated = service.detail("a").contextWrite(access(Set.of("a"))).block(WAIT);
        assertEquals("修改姓名", updated.getName());
        assertNull(updated.getTelephone());
        assertNull(updated.getEmail());
        assertEquals(2, updated.getIdentities().size());
        assertEquals(1L, updated.getVersion());
        var counts =
                service.counts(new QueryParamEntity())
                        .contextWrite(access(Set.of("a", "b")))
                        .block(WAIT);
        assertEquals(new PropertyUserService.Counts(2, 1, 1, 1), counts);
        saved =
                service.updateIdentities("a", Set.of(), 1)
                        .contextWrite(access(Set.of("a")))
                        .block(WAIT);
        assertTrue(saved.getIdentities().isEmpty());
        assertEquals(2L, saved.getVersion());
    }

    @Test
    void casHasOneWinnerAndDisabledUsersCannotBeMarked() {
        snapshot(user("a"));
        sync.synchronize().block(WAIT);
        var results =
                Flux.merge(
                                service.updateIdentities("a", Set.of(UserIdentity.owner), 0)
                                        .materialize(),
                                service.updateIdentities("a", Set.of(UserIdentity.employee), 0)
                                        .materialize())
                        .contextWrite(access(Set.of("a")))
                        .collectList()
                        .block(WAIT);
        assertEquals(1, results.stream().filter(value -> value.isOnNext()).count());
        assertEquals(
                1,
                results.stream()
                        .filter(
                                value ->
                                        value.getThrowable() instanceof BusinessException e
                                                && e.getStatus() == 409)
                        .count());
        var disabled = user("a");
        disabled.setStatus((byte) 0);
        snapshot(disabled);
        sync.synchronize().block(WAIT);
        var before = users.findById("a").block(WAIT).getIdentities();
        StepVerifier.create(
                        service.updateIdentities("a", Set.of(), 1)
                                .contextWrite(access(Set.of("a"))))
                .expectErrorMatches(
                        error ->
                                error instanceof BusinessException e
                                        && e.getMessage().contains("disabled"))
                .verify(WAIT);
        assertEquals(before, users.findById("a").block(WAIT).getIdentities());
    }

    @Test
    void deletionNeedsExplicitRecheckAndRestorationPreservesIdentity() {
        snapshot(user("a"), user("b"));
        sync.synchronize().block(WAIT);
        service.updateIdentities("b", Set.of(UserIdentity.owner), 0)
                .contextWrite(access(Set.of("b")))
                .block(WAIT);
        snapshot(user("a"));
        when(source.find("b")).thenReturn(Mono.just(Optional.of(user("b"))));
        assertEquals(0, sync.synchronize().block(WAIT).deleted());
        assertFalse(users.findById("b").block(WAIT).getDeleted());
        when(source.find("b")).thenReturn(Mono.just(Optional.empty()));
        assertEquals(1, sync.synchronize().block(WAIT).deleted());
        assertTrue(users.findById("b").block(WAIT).getDeleted());
        snapshot(user("a"), user("b"), user("new-b"));
        sync.synchronize().block(WAIT);
        assertEquals(List.of(UserIdentity.owner), users.findById("b").block(WAIT).getIdentities());
        assertTrue(users.findById("new-b").block(WAIT).getIdentities().isEmpty());
    }

    @Test
    void failedRecheckAndEmptySourceRetainSnapshotAndSuccessTime() {
        snapshot(user("a"), user("b"));
        sync.synchronize().block(WAIT);
        Long previous = sync.status().block(WAIT).lastSuccessTime();
        snapshot(user("a"));
        when(source.find("b")).thenReturn(Mono.error(new IllegalStateException("timeout")));
        StepVerifier.create(sync.synchronize()).expectError().verify(WAIT);
        assertFalse(users.findById("b").block(WAIT).getDeleted());
        assertEquals(previous, sync.status().block(WAIT).lastSuccessTime());
        snapshot();
        StepVerifier.create(sync.synchronize()).expectError().verify(WAIT);
        assertEquals(2, users.createQuery().where("deleted", false).count().block(WAIT));
        assertFalse(sync.status().block(WAIT).running());
        assertEquals("failed", sync.status().block(WAIT).status());
    }

    @Test
    void transactionRollsBackWholeRoundAndRejectsExpiredLease() {
        String task = sync.acquire().block(WAIT);
        // 超长路径仅在写入关联表时触发 PostgreSQL 错误，确保前面已写入的用户也回滚。
        var nodes = new ArrayList<PropertyOrganizationEntity>();
        for (int i = 0; i < 40; i++) {
            var node = new PropertyOrganizationEntity();
            node.setId("org-" + i);
            node.setName("长".repeat(60));
            node.setParentId(i == 0 ? null : "org-" + (i - 1));
            node.setVersion(0L);
            node.setDeleted(false);
            nodes.add(node);
        }
        tree.insertBatch(nodes).block(WAIT);
        var invalid = user("b");
        var org = new OrganizationInfo();
        org.setId("org-39");
        org.setName("部门");
        invalid.setOrgList(List.of(org));
        var snapshot = new LinkedHashMap<String, UserDetail>();
        snapshot.put("a", user("a"));
        snapshot.put("b", invalid);
        StepVerifier.create(writer.apply(task, snapshot, Set.of())).expectError().verify(WAIT);
        assertEquals(0, users.createQuery().count().block(WAIT));
        states.createUpdate()
                .set("leaseUntil", 0L)
                .where("id", UserSyncService.STATE_ID)
                .execute()
                .block(WAIT);
        StepVerifier.create(writer.apply(task, Map.of("a", user("a")), Set.of()))
                .expectErrorMatches(
                        error -> error instanceof BusinessException e && e.getStatus() == 409)
                .verify(WAIT);
        assertEquals(0, users.createQuery().count().block(WAIT));
    }

    @Test
    void permissionsApplyBeforePagingAndCountsAndRejectForgedDetailOrSave() {
        snapshot(user("a"), user("b"), user("c"));
        sync.synchronize().block(WAIT);
        var query = new QueryParamEntity();
        query.setPageSize(1);
        query.orderBy("id");
        var page = service.query(query).contextWrite(access(Set.of("b", "c"))).block(WAIT);
        assertEquals(2, page.getTotal());
        assertEquals("b", page.getData().get(0).getId());
        assertEquals(
                2, service.counts(query).contextWrite(access(Set.of("b", "c"))).block(WAIT).all());
        assertEquals(0, service.query(query).contextWrite(access(Set.of())).block(WAIT).getTotal());
        StepVerifier.create(service.detail("a").contextWrite(access(Set.of("b"))))
                .expectError(AccessDenyException.class)
                .verify(WAIT);
        StepVerifier.create(
                        service.updateIdentities("a", Set.of(UserIdentity.owner), 0)
                                .contextWrite(access(Set.of("b"))))
                .expectError(AccessDenyException.class)
                .verify(WAIT);
        StepVerifier.create(service.query(query))
                .expectError(
                        org.hswebframework.web.authorization.exception.UnAuthorizedException.class)
                .verify(WAIT);
    }

    @Test
    void directOrganizationExistsFilterIgnoresEndedRelations() {
        var a = user("a");
        var org = new OrganizationInfo();
        org.setId("park");
        org.setName("园区");
        a.setOrgList(List.of(org));
        snapshot(a, user("b"));
        sync.synchronize().block(WAIT);
        var query = new QueryParamEntity();
        query.and("id", "property-user-org", Map.of("orgId$in", List.of("park")));
        var page = service.query(query).contextWrite(access(Set.of("a", "b"))).block(WAIT);
        assertEquals(1, page.getTotal());
        assertEquals("a", page.getData().get(0).getId());
        assertEquals("park", page.getData().get(0).getOrgList().get(0).getOrgId());
        snapshot(user("a"), user("b"));
        sync.synchronize().block(WAIT);
        assertEquals(
                0,
                service.query(query).contextWrite(access(Set.of("a", "b"))).block(WAIT).getTotal());
        assertEquals(1, orgs.createQuery().where("deleted", true).count().block(WAIT));
    }

    @Test
    void sourceSyncAndIdentityEditDoNotOverwriteEachOther() throws InterruptedException {
        snapshot(user("a"));
        sync.synchronize().block(WAIT);
        var gate = Sinks.<Map<String, UserDetail>>one();
        var started = new java.util.concurrent.CountDownLatch(1);
        when(source.fetchAll())
                .thenReturn(gate.asMono().doOnSubscribe(ignored -> started.countDown()));
        var running = sync.synchronize().toFuture();
        assertTrue(started.await(20, java.util.concurrent.TimeUnit.SECONDS));
        service.updateIdentities("a", Set.of(UserIdentity.owner), 0)
                .contextWrite(access(Set.of("a")))
                .block(WAIT);
        var renamed = user("a");
        renamed.setName("来源新姓名");
        gate.tryEmitValue(new LinkedHashMap<>(Map.of("a", renamed)));
        running.join();
        var row = users.findById("a").block(WAIT);
        assertEquals("来源新姓名", row.getName());
        assertEquals(List.of(UserIdentity.owner), row.getIdentities());
        assertEquals(1L, row.getVersion());
    }

    @Test
    void concurrentSyncHasOneOwnerAndIgnoresCallerContext() throws InterruptedException {
        var gate = Sinks.<Map<String, UserDetail>>one();
        var started = new java.util.concurrent.CountDownLatch(1);
        when(source.fetchAll())
                .thenReturn(
                        Mono.deferContextual(
                                context -> {
                                    assertFalse(context.hasKey("caller"));
                                    started.countDown();
                                    return gate.asMono();
                                }));
        var first = sync.synchronize().contextWrite(Context.of("caller", "restricted")).toFuture();
        assertTrue(started.await(20, java.util.concurrent.TimeUnit.SECONDS));
        StepVerifier.create(sync.synchronize())
                .expectErrorMatches(
                        error -> error instanceof BusinessException e && e.getStatus() == 409)
                .verify(WAIT);
        gate.tryEmitValue(new LinkedHashMap<>(Map.of("a", user("a"))));
        first.join();
        assertFalse(sync.status().block(WAIT).running());
    }

    static UserDetail user(String id) {
        var user = new UserDetail();
        user.setId(id);
        user.setUsername("account-" + id);
        user.setName("同名用户");
        user.setType("user");
        user.setStatus((byte) 1);
        return user;
    }

    void snapshot(UserDetail... values) {
        when(source.fetchAll())
                .thenAnswer(
                        call -> {
                            var map = new LinkedHashMap<String, UserDetail>();
                            Arrays.stream(values).forEach(user -> map.put(user.getId(), user));
                            return Mono.just(map);
                        });
    }

    Context access(Set<String> allowed) {
        var holder = mock(AssetsHolder.class);
        when(holder.filter(any(Flux.class), any(org.jetlinks.pro.assets.AssetType.class), any(), any(org.jetlinks.pro.assets.AssetPermission[].class)))
            .thenAnswer(call -> call.<Flux<PropertyUserEntity>>getArgument(0)
                .filter(row -> allowed.contains(row.getId())));
        doAnswer(call -> allowed.containsAll(call.getArgument(1))
                ? Mono.empty() : Mono.error(new AccessDenyException.NoStackTrace()))
            .when(holder).assertPermission(eq("user"), anyCollection(), any());
        var user = new SimpleUser();
        user.setId("tester");
        user.setUsername("测试人员");
        var auth = new SimpleAuthentication();
        auth.setUser(user);
        return Context.of(AssetsHolder.class, holder, Authentication.class, auth);
    }

    @Import({
        OrganizationPersistenceTest.Config.class,
        PropertyUserService.class,
        PropertyUserScope.class,
        UserSyncWriter.class,
        UserSyncService.class,
        UserOrganizationTerm.class
    })
    static class Config {
        @Bean
        SaasUserSource userSource() {
            return mock(SaasUserSource.class);
        }
    }
}
