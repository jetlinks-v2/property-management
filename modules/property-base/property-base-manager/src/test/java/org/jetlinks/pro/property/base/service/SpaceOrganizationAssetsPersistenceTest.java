package org.jetlinks.pro.property.base.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.simple.*;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.impl.*;
import org.jetlinks.pro.assets.impl.entity.DimensionAssetBindingEntity;
import org.jetlinks.pro.authorize.OrgDimensionType;
import org.jetlinks.pro.property.base.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.core.publisher.*;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.*;

/** 真实PG资产绑定与平台Holder验证组织归属，不把空间权限mock成永远通过。 */
@SpringBootTest(
        classes = SpaceOrganizationAssetsPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SpaceOrganizationAssetsPersistenceTest {
    static final Duration WAIT = Duration.ofSeconds(20);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        OrganizationPersistenceTest.database(registry);
    }

    @Autowired SpaceOrganizationAssets ownership;
    @Autowired AssetBindManager manager;
    @Autowired ReactiveRepository<PropertySpaceEntity, String> spaces;
    @Autowired ReactiveRepository<PropertySpaceBindingEntity, String> roots;
    @Autowired ReactiveRepository<DimensionAssetBindingEntity, String> assetRows;
    @Autowired ReactiveRepository<PropertyOrganizationEntity, String> organizations;
    @Autowired org.hswebframework.ezorm.rdb.operator.DatabaseOperator database;
    Map<String, PropertySpaceEntity> snapshot;

    @BeforeEach
    void setup() {
        assetRows
                .deleteById(assetRows.createQuery().fetch().map(DimensionAssetBindingEntity::getId))
                .block(WAIT);
        spaces.deleteById(spaces.createQuery().fetch().map(PropertySpaceEntity::getId)).block(WAIT);
        roots.deleteById(roots.createQuery().fetch().map(PropertySpaceBindingEntity::getId))
                .block(WAIT);
        snapshot =
                SpaceSnapshot.from(
                        List.of(
                                SpaceSnapshotTest.node("root-a", null, "项目甲"),
                                SpaceSnapshotTest.node("room", "root-a", "101"),
                                SpaceSnapshotTest.node("root-b", null, "项目乙"),
                                SpaceSnapshotTest.node("unbound", null, "未关联")));
        spaces.insertBatch(new ArrayList<>(snapshot.values())).block(WAIT);
        root("root-a", "project-a");
        root("root-b", "project-b");
    }

    void root(String id, String project) {
        var organization = new PropertyOrganizationEntity();
        organization.setId(project);
        organization.setName(project);
        organization.setVersion(0L);
        organization.setDeleted(false);
        organization.setOrganizationType(
                org.jetlinks.pro.property.base.organization.OrganizationType.project);
        organizations.save(organization).block(WAIT);
        var row = new PropertySpaceBindingEntity();
        row.setId(id);
        row.setOrganizationId(project);
        row.setVersion(0L);
        row.setDeleted(false);
        roots.insert(row).block(WAIT);
    }

    Set<String> visible(String project) {
        var holder =
                new DynamicCustomDimensionAssetsHolder(
                        mock(AssetManager.class),
                        manager,
                        "space",
                        type ->
                                Flux.just(
                                        SimpleDimension.of(
                                                project, project, OrgDimensionType.org, Map.of())));
        return holder.filter(
                        Flux.fromIterable(snapshot.values()),
                        ArchiveScope.SPACE,
                        PropertySpaceEntity::getId,
                        CrudAssetPermission.read)
                .map(PropertySpaceEntity::getId)
                .collectList()
                .map(HashSet::new)
                .block(WAIT);
    }

    void synchronize(Map<String, PropertySpaceEntity> next) {
        ownership.synchronize(snapshot, next).block(WAIT);
        snapshot = next;
    }

    @Test
    void upgradeSqlUsesFrameworkIdsAndIsSafeToRepeat() throws Exception {
        String sql =
                java.nio.file.Files.readString(
                        java.nio.file.Path.of("../sql/backfill-space-organization-assets.sql"));
        for (int i = 0; i < 2; i++)
            database.sql()
                    .reactive()
                    .update(org.hswebframework.ezorm.rdb.executor.SqlRequests.of(sql))
                    .block(WAIT);
        assertEquals(3, assetRows.createQuery().count().block(WAIT));
        assertEquals(Set.of("root-a", "room"), visible("project-a"));
        var row =
                assetRows
                        .findById(
                                DimensionAssetBindingEntity.generateId(
                                        "org", "project-a", "space", "room"))
                        .block(WAIT);
        assertNotNull(row);
        assertEquals(
                DimensionAssetBindingEntity.generateKey("org", "project-a"), row.getTargetKey());
        // 旧授权不被SQL覆写；后续业务归属维护仍经标准AssetBindManager执行。
        assetRows
                .createUpdate()
                .set("permission", CrudAssetPermission.read.getMask())
                .where("id", row.getId())
                .execute()
                .block(WAIT);
        database.sql()
                .reactive()
                .update(org.hswebframework.ezorm.rdb.executor.SqlRequests.of(sql))
                .block(WAIT);
        assertEquals(
                CrudAssetPermission.read.getMask(),
                assetRows.findById(row.getId()).block(WAIT).getPermission());
    }

    @Test
    void unchangedLegacyTreeIsRepairedIdempotentlyWithoutGivingOtherProjectsAccess() {
        assertTrue(visible("project-a").isEmpty());
        synchronize(snapshot);
        assertEquals(Set.of("root-a", "room"), visible("project-a"));
        assertEquals(Set.of("root-b"), visible("project-b"));
        assertTrue(visible("other-project").isEmpty());
        synchronize(snapshot);
        assertEquals(3, assetRows.createQuery().count().block(WAIT));
        var holder =
                new DynamicCustomDimensionAssetsHolder(
                        mock(AssetManager.class),
                        manager,
                        "space",
                        type ->
                                Flux.just(
                                        SimpleDimension.of(
                                                "project-a", "甲", OrgDimensionType.org, Map.of())));
        StepVerifier.create(
                        holder.assertPermission("space", List.of("room"), CrudAssetPermission.save))
                .verifyComplete();
        StepVerifier.create(
                        holder.assertPermission(
                                "space", List.of("root-b"), CrudAssetPermission.read))
                .expectError()
                .verify();
    }

    @Test
    void movingDeletingAndRestoringNodesRevokesOldOrganizationAndRebindsNewOne() {
        synchronize(snapshot);
        var moved =
                SpaceSnapshot.from(
                        List.of(
                                SpaceSnapshotTest.node("root-a", null, "项目甲"),
                                SpaceSnapshotTest.node("root-b", null, "项目乙"),
                                SpaceSnapshotTest.node("room", "root-b", "101")));
        synchronize(moved);
        assertEquals(Set.of("root-a"), visible("project-a"));
        assertEquals(Set.of("root-b", "room"), visible("project-b"));
        var deleted = new LinkedHashMap<>(snapshot);
        deleted.remove("room");
        synchronize(deleted);
        assertEquals(2, assetRows.createQuery().count().block(WAIT));
        synchronize(moved);
        assertEquals(Set.of("root-b", "room"), visible("project-b"));
    }

    @Test
    void unbindingAndRebindingRootMaintainsOnlyItsOwnOrganizationAssets() {
        synchronize(snapshot);
        var custom = new AssetBindRequest();
        custom.setAssetType("space");
        custom.setTargetType("user");
        custom.setTargetId("other-user");
        custom.setAssetIdList(List.of("room"));
        custom.setAllPermission(true);
        manager.bindAssets(Mono.just(custom)).block(WAIT);
        ownership.change("root-a", "project-a", null).block(WAIT);
        assertTrue(visible("project-a").isEmpty());
        assertEquals(Set.of("root-b"), visible("project-b"));
        assertEquals(
                1,
                manager.getBindings("user", List.of("other-user"), ArchiveScope.SPACE)
                        .count()
                        .block(WAIT));
        ownership.change("root-a", null, "project-a").block(WAIT);
        assertEquals(Set.of("root-a", "room"), visible("project-a"));
    }

    @Configuration
    @EnableEasyormRepository("org.jetlinks.pro.assets.impl.entity")
    @Import({OrganizationPersistenceTest.Config.class, SpaceOrganizationAssets.class})
    static class Config {
        @Bean
        AssetBindManager assets(ReactiveRepository<DimensionAssetBindingEntity, String> rows) {
            return new DimensionAssetBindManager(rows);
        }
    }
}
