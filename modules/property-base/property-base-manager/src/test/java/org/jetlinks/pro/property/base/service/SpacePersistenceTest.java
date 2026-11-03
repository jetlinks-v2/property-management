package org.jetlinks.pro.property.base.service;

import static org.jetlinks.pro.property.base.service.SpaceSnapshotTest.node;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.base.entity.PropertySpaceEntity;
import org.jetlinks.pro.property.base.entity.SpaceSyncStateEntity;
import org.jetlinks.pro.property.base.space.SaasSpaceInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 复用组织测试的 PostgreSQL 装配，验证真实事务、空源保护和多实例租约。 */
@SpringBootTest(
        classes = {OrganizationPersistenceTest.Config.class, SpacePersistenceTest.Config.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SpacePersistenceTest {
    static final Duration TIMEOUT = Duration.ofSeconds(15);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        OrganizationPersistenceTest.database(registry);
    }

    @Autowired ReactiveRepository<PropertySpaceEntity, String> spaces;
    @Autowired ReactiveRepository<SpaceSyncStateEntity, String> states;
    @Autowired SpaceSyncWriter writer;
    @Autowired SpaceSyncService sync;
    @Autowired SaasSpaceSource source;

    @BeforeEach
    void resetData() {
        spaces.deleteById(spaces.createQuery().fetch().map(PropertySpaceEntity::getId))
                .block(TIMEOUT);
        states.deleteById(SpaceSyncService.STATE_ID).block(TIMEOUT);
        reset(source);
    }

    @Test
    void renameMoveDeleteRestoreRecomputesPathsAndRetainsMirrorRows() {
        snapshot(node("a", null, "一栋"), node("b", null, "二栋"), node("room", "a", "101"));
        assertEquals(3, sync.synchronize().block(TIMEOUT).added());
        assertEquals(0, sync.synchronize().block(TIMEOUT).updated());
        assertFalse(spaces.findById("a").block(TIMEOUT).getLeaf());
        assertTrue(spaces.findById("b").block(TIMEOUT).getLeaf());
        snapshot(node("a", null, "新一栋"), node("b", null, "二栋"), node("room", "a", "101"));
        assertEquals(2, sync.synchronize().block(TIMEOUT).updated());
        assertEquals("新一栋/101", spaces.findById("room").block(TIMEOUT).getFullName());
        snapshot(node("a", null, "新一栋"), node("b", null, "二栋"), node("room", "b", "201"));
        sync.synchronize().block(TIMEOUT);
        var row = spaces.findById("room").block(TIMEOUT);
        assertEquals("b", row.getRootId());
        assertEquals("二栋/201", row.getFullName());
        assertTrue(spaces.findById("a").block(TIMEOUT).getLeaf());
        assertFalse(spaces.findById("b").block(TIMEOUT).getLeaf());
        snapshot(node("a", null, "新一栋"), node("b", null, "二栋"));
        assertEquals(1, sync.synchronize().block(TIMEOUT).deleted());
        assertEquals(3, spaces.createQuery().count().block(TIMEOUT));
        assertTrue(spaces.findById("room").block(TIMEOUT).getDeleted());
        assertTrue(spaces.findById("b").block(TIMEOUT).getLeaf());
        snapshot(node("a", null, "新一栋"), node("b", null, "二栋"), node("room", null, "恢复独栋"));
        sync.synchronize().block(TIMEOUT);
        row = spaces.findById("room").block(TIMEOUT);
        assertFalse(row.getDeleted());
        assertNull(row.getParentId());
        assertEquals("room", row.getRootId());
        assertEquals("恢复独栋", row.getFullName());
        assertTrue(row.getLeaf());
        assertNotNull(row.getSourceUpdateTime());
    }

    @Test
    void sourceTypeAndAnnotatedLocationPersistRenameAndClearWithoutChangingLeafRule() {
        var building = node("building", null, "1栋");
        building.setSpaceTypeId("building-type");
        building.setSpaceTypeName("楼栋");
        var room = node("room", "building", "101");
        room.setSpaceTypeId("room-type");
        room.setSpaceTypeName("房间");
        snapshot(building, room);
        sync.synchronize().block(TIMEOUT);
        var saved = spaces.findById("room").block(TIMEOUT);
        assertEquals("room-type", saved.getSpaceTypeId());
        assertEquals("楼栋：1栋 / 房间：101", saved.getSpaceLocation());
        building.setSpaceTypeName("建筑");
        room.setSpaceTypeId(null);
        room.setSpaceTypeName(null);
        snapshot(building, room);
        sync.synchronize().block(TIMEOUT);
        saved = spaces.findById("room").block(TIMEOUT);
        assertNull(saved.getSpaceTypeId());
        assertNull(saved.getSpaceTypeName());
        assertEquals("建筑：1栋 / 101", saved.getSpaceLocation());
        assertTrue(saved.getLeaf());
    }

    @Test
    void removedSourceSortIndexIsPersistedAsNull() {
        var root = node("a", null, "独栋");
        root.setSortIndex(10L);
        snapshot(root);
        sync.synchronize().block(TIMEOUT);
        snapshot(node("a", null, "独栋"));
        assertEquals(1, sync.synchronize().block(TIMEOUT).updated());
        assertNull(spaces.findById("a").block(TIMEOUT).getSortIndex());
    }

    @Test
    void malformedEmptyAndFailedSourcesRetainDataAndReleaseLease() {
        snapshot(node("a", null, "已有空间"));
        sync.synchronize().block(TIMEOUT);
        for (Mono<List<SaasSpaceInfo>> input :
                List.of(
                        Mono.just(List.<SaasSpaceInfo>of()),
                        Mono.just(List.of(node("a", "missing", "错误空间"))),
                        Mono.just(List.of(node("a", null, "甲"), node("a", null, "乙"))),
                        Mono.<List<SaasSpaceInfo>>error(
                                new IllegalStateException("upstream failed")))) {
            when(source.fetchAll()).thenReturn(input);
            StepVerifier.create(sync.synchronize()).expectError().verify(TIMEOUT);
            var row = spaces.findById("a").block(TIMEOUT);
            assertEquals("已有空间", row.getName());
            assertFalse(row.getDeleted());
            var status = sync.status().block(TIMEOUT);
            assertFalse(status.running());
            assertNotNull(status.lastSuccessTime());
            assertNotNull(status.lastFailureTime());
            assertEquals("error.property_space_sync_failed", status.lastError());
        }
        snapshot(node("a", null, "恢复"));
        sync.synchronize().block(TIMEOUT);
        assertNull(sync.status().block(TIMEOUT).lastError());
    }

    @Test
    void transactionRollsBackWholeRoundAndExpiredOwnerCannotCommit() {
        var lease = new SpaceSyncStateEntity();
        lease.setId(SpaceSyncService.STATE_ID);
        lease.setLeaseOwner("owner");
        lease.setLeaseUntil(System.currentTimeMillis() + 120_000);
        states.insert(lease).block(TIMEOUT);
        var valid = SpaceSnapshot.from(List.of(node("valid", null, "有效"))).get("valid");
        var invalid = SpaceSnapshot.from(List.of(node("invalid", null, "有效"))).get("invalid");
        invalid.setName("x".repeat(129));
        StepVerifier.create(writer.apply("owner", Map.of("valid", valid, "invalid", invalid)))
                .expectError()
                .verify(TIMEOUT);
        assertEquals(0, spaces.createQuery().count().block(TIMEOUT));
        assertNull(states.findById(lease.getId()).block(TIMEOUT).getLastSuccessTime());
        states.createUpdate()
                .set(SpaceSyncStateEntity::getLeaseUntil, 0L)
                .where(SpaceSyncStateEntity::getId, lease.getId())
                .execute()
                .block(TIMEOUT);
        StepVerifier.create(writer.apply("owner", Map.of("valid", valid)))
                .expectErrorMatches(
                        error -> error instanceof BusinessException e && e.getStatus() == 409)
                .verify(TIMEOUT);
        assertEquals(0, spaces.createQuery().count().block(TIMEOUT));
    }

    @Test
    void concurrentSyncHasOneOwnerAndSystemSnapshotDoesNotInheritCallerContext()
            throws InterruptedException {
        var gate = Sinks.<List<SaasSpaceInfo>>one();
        var subscribed = new CountDownLatch(1);
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
        try {
            assertTrue(subscribed.await(15, TimeUnit.SECONDS));
            assertTrue(sync.status().block(TIMEOUT).running());
            StepVerifier.create(sync.synchronize())
                    .expectErrorMatches(
                            error -> error instanceof BusinessException e && e.getStatus() == 409)
                    .verify(TIMEOUT);
            gate.tryEmitValue(List.of(node("a", null, "独栋")));
            assertEquals(1, first.join().added());
            assertFalse(sync.status().block(TIMEOUT).running());
        } finally {
            first.cancel(true);
        }
    }

    private void snapshot(SaasSpaceInfo... nodes) {
        when(source.fetchAll()).thenReturn(Mono.just(List.of(nodes)));
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class Config {
        @Bean
        SaasSpaceSource spaceSource() {
            return mock(SaasSpaceSource.class);
        }

        @Bean
        SpaceSyncWriter spaceWriter(
                ReactiveRepository<PropertySpaceEntity, String> spaces,
                ReactiveRepository<SpaceSyncStateEntity, String> states) {
            return new SpaceSyncWriter(spaces, states);
        }

        @Bean
        SpaceSyncService spaceSync(
                ReactiveRepository<SpaceSyncStateEntity, String> states,
                SaasSpaceSource source,
                SpaceSyncWriter writer) {
            return new SpaceSyncService(states, source, writer);
        }
    }
}
