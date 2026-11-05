package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.simple.*;
import org.jetlinks.pro.property.finance.contract.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import reactor.core.publisher.*;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.*;

/** 原子增量、权限及CAS使用独立真实PG；外部空间来源使用受限公开快照。 */
@SpringBootTest(
        classes = ContractMirrorPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ContractMirrorPersistenceTest extends InvoiceCompanyPersistenceTest {
    static final Duration WAIT = Duration.ofSeconds(30);
    @Autowired ContractMirrorService mirror;
    @Autowired org.jetlinks.pro.property.finance.command.FinanceContractCommandSupport commands;
    @Autowired ReactiveRepository<ContractMirrorEntity, String> contracts;
    @Autowired ReactiveRepository<ContractSyncLogEntity, String> syncLogs;
    @Autowired ReactiveRepository<ContractSyncStateEntity, String> syncStates;
    @Autowired org.hswebframework.ezorm.rdb.operator.DatabaseOperator database;

    @Autowired
    ReactiveRepository<org.jetlinks.pro.assets.impl.entity.DimensionAssetBindingEntity, String>
            bindings;

    CarryForwardAuthorityTest assets;
    String project;
    String reader;
    Context permitted;

    @BeforeEach
    void mirrorSetup() {
        project = "contract-" + UUID.randomUUID();
        reader = "contract-reader-" + UUID.randomUUID();
        assets = new CarryForwardAuthorityTest();
        assets.holders();
        assets.rights.put(reader, Set.of("room", "room2"));
        database.getMetadata()
                .addFeature(new org.jetlinks.pro.assets.impl.terms.DimensionAssetsTermBuilder());
        for (String room : List.of("room", "room2")) {
            var binding = new org.jetlinks.pro.assets.impl.entity.DimensionAssetBindingEntity();
            binding.setAssetId(room);
            binding.setAssetType("space");
            binding.setTargetId(reader);
            binding.setTargetType("test");
            binding.setPermission(org.jetlinks.pro.assets.crud.CrudAssetPermission.all_mask);
            binding.setUpdateTime(System.currentTimeMillis());
            binding.generateTargetKey();
            binding.setId(binding.generateId());
            bindings.insert(binding).block(WAIT);
        }
        var authentication = assets.auth(reader);
        authentication.setPermissions(
                List.of(
                        SimplePermission.builder()
                                .id("property-finance-contracts")
                                .actions(Set.of("query", "sync"))
                                .build()));
        permitted = Context.of(Authentication.class, authentication);
    }

    @AfterEach
    void mirrorCleanup() {
        assets.close();
    }

    ContractMirrorInput item(String id, String code, String name, boolean deleted) {
        return new ContractMirrorInput(
                id,
                code,
                name,
                project,
                "room",
                "5G园区/1栋/101",
                "customer",
                "历史客户",
                "2024-01-01",
                "2024-12-31",
                "100.00",
                deleted);
    }

    ContractSyncResult apply(Long version, ContractMirrorInput... rows) {
        return mirror.apply(project, version, List.of(rows)).contextWrite(permitted).block(WAIT);
    }

    @Test
    void mirrorUpdatesExistingSourceRetainsAbsentAndTombstonesOnlyExplicitRows() {
        var first = apply(null, item("a", "C01", "合同一", false), item("b", "C02", "合同二", false));
        assertEquals(2, first.added());
        var updated = apply(first.version(), item("a", "C01", "新名称", false));
        assertEquals(1, updated.updated());
        assertEquals(
                2,
                mirror.query(project, null, 0, 12).contextWrite(permitted).block(WAIT).getTotal());
        var same = apply(updated.version(), item("a", "C01", "新名称", false));
        assertEquals(updated.version(), same.version());
        assertEquals(1, same.unchanged());
        var removed = apply(same.version(), item("a", "C01", "新名称", true));
        assertEquals(1, removed.deleted());
        assertEquals(
                1,
                mirror.query(project, null, 0, 12).contextWrite(permitted).block(WAIT).getTotal());
        assertEquals(1, apply(removed.version(), item("a", "C01", "恢复", false)).updated());
        assertEquals(4, syncLogs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void mirrorMalformedLastRowDuplicateCodeAndForeignProjectWriteNothing() {
        var wrong =
                new ContractMirrorInput(
                        "b",
                        "C02",
                        "外项目",
                        "forbidden",
                        "room",
                        "位置",
                        "customer",
                        null,
                        "2024-01-01",
                        "2024-12-31",
                        "1",
                        false);
        rejects(mirror.apply(project, null, List.of(item("a", "C01", "合法", false), wrong)), 400);
        rejects(
                mirror.apply(
                        project,
                        null,
                        List.of(item("a", "C01", "合法", false), item("b", "C01", "重复编码", false))),
                409);
        var bad =
                new ContractMirrorInput(
                        "b",
                        "C02",
                        "坏日期",
                        project,
                        "room",
                        "位置",
                        "customer",
                        null,
                        "2024-02-30",
                        "2024-12-31",
                        "1",
                        false);
        rejects(mirror.apply(project, null, List.of(item("a", "C01", "合法", false), bad)), 400);
        assertEquals(0, contracts.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, syncLogs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void mirrorSourceCodeCannotRemapAndStaleBatchCannotOverwriteCommittedChanges() {
        var initial = apply(null, item("a", "C01", "初始", false));
        rejects(
                mirror.apply(
                        project, initial.version(), List.of(item("a", "CHANGED", "改变编码", false))),
                409);
        var current = apply(initial.version(), item("a", "C01", "最新", false));
        rejects(
                mirror.apply(project, initial.version(), List.of(item("a", "C01", "旧请求", false))),
                409);
        var page = mirror.query(project, null, 0, 12).contextWrite(permitted).block(WAIT);
        assertEquals("最新", page.getData().get(0).name());
        assertEquals(
                current.version(), mirror.version(project).contextWrite(permitted).block(WAIT));
    }

    @Test
    void mirrorConcurrentBatchesHaveOneVisibleConflictAndNoSilentOverwrite() {
        var first = apply(null, item("a", "C01", "初始", false));
        var results =
                Flux.merge(
                                mirror.apply(
                                                project,
                                                first.version(),
                                                List.of(item("a", "C01", "甲", false)))
                                        .materialize(),
                                mirror.apply(
                                                project,
                                                first.version(),
                                                List.of(item("a", "C01", "乙", false)))
                                        .materialize())
                        .contextWrite(permitted)
                        .collectList()
                        .block(WAIT);
        assertEquals(1, results.stream().filter(Signal::isOnNext).count());
        assertEquals(
                1,
                results.stream()
                        .filter(
                                v ->
                                        v.isOnError()
                                                && TaxItemPersistenceTest.status(
                                                        v.getThrowable(), 409))
                        .count());
        assertEquals(2, syncLogs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void mirrorDeniedSpaceOrMissingButtonCannotApplyAndQueryIsFilteredBeforePagination() {
        var first = apply(null, item("a", "C01", "允许", false));
        var hidden =
                new ContractMirrorInput(
                        "b",
                        "C02",
                        "隐藏",
                        project,
                        "secret",
                        "秘密",
                        "customer",
                        null,
                        "2024-01-01",
                        "2024-12-31",
                        "1",
                        false);
        rejects(
                mirror.apply(
                        project, first.version(), List.of(item("a", "C01", "不应更新", false), hidden)),
                403);
        var auth = assets.auth(reader);
        reactor.test.StepVerifier.create(
                        mirror.apply(
                                        project,
                                        first.version(),
                                        List.of(item("a", "C01", "越权", false)))
                                .contextWrite(Context.of(Authentication.class, auth)))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        reactor.test.StepVerifier.create(mirror.version(project)).expectError().verify(WAIT);
        assets.rights.put(reader, Set.of());
        bindings.deleteById(
                        bindings.createQuery()
                                .where("targetId", reader)
                                .fetch()
                                .map(
                                        org.jetlinks.pro.assets.impl.entity
                                                        .DimensionAssetBindingEntity
                                                ::getId))
                .block(WAIT);
        assertEquals(
                0,
                mirror.query(project, null, 0, 1).contextWrite(permitted).block(WAIT).getTotal());
    }

    void rejects(Mono<?> action, int status) {
        reactor.test.StepVerifier.create(action.contextWrite(permitted))
                .expectErrorMatches(
                        e ->
                                TaxItemPersistenceTest.status(e, status)
                                        || status == 403
                                                && e
                                                        instanceof
                                                        org.hswebframework.web.authorization
                                                                .exception.AccessDenyException)
                .verify(WAIT);
    }

    @Test
    void mirrorFirstConcurrentBatchesHaveOneConflictAndCompleteState() {
        var result =
                Flux.merge(
                                mirror.apply(project, null, List.of(item("a", "C01", "甲", false)))
                                        .materialize(),
                                mirror.apply(project, null, List.of(item("a", "C01", "乙", false)))
                                        .materialize())
                        .contextWrite(permitted)
                        .collectList()
                        .block(WAIT);
        assertEquals(1, result.stream().filter(Signal::isOnNext).count());
        assertEquals(
                1,
                result.stream()
                        .filter(
                                s ->
                                        s.isOnError()
                                                && TaxItemPersistenceTest.status(
                                                        s.getThrowable(), 409))
                        .count());
        assertEquals(1, contracts.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(1, syncLogs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void mirrorAuditFailureRollsBackAllRowsStateAndTimestamp() {
        var first = apply(null, item("a", "C01", "原名", false));
        sql(
                "alter table pm_fin_contract_sync_log add constraint reject_contract_audit check"
                    + " (project_id <> '"
                        + project
                        + "' or version < 2)");
        try {
            reactor.test.StepVerifier.create(
                            mirror.apply(
                                            project,
                                            first.version(),
                                            List.of(
                                                    item("a", "C01", "不该保存", false),
                                                    item("b", "C02", "不该新建", false)))
                                    .contextWrite(permitted))
                    .expectError()
                    .verify(WAIT);
            assertEquals(
                    first.version(), mirror.version(project).contextWrite(permitted).block(WAIT));
            assertEquals(
                    1, contracts.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(
                    "原名",
                    mirror.query(project, null, 0, 12)
                            .contextWrite(permitted)
                            .block(WAIT)
                            .getData()
                            .get(0)
                            .name());
            assertEquals(1, syncLogs.createQuery().where("projectId", project).count().block(WAIT));
        } finally {
            sql("alter table pm_fin_contract_sync_log drop constraint reject_contract_audit");
        }
        assertEquals(1, apply(first.version(), item("b", "C02", "可重试", false)).added());
    }

    @Test
    void mirrorRestorationClearsDeletedTimeAndKeepsStableIdentity() {
        var first = apply(null, item("a", "C01", "原名", false));
        var original = contracts.createQuery().where("projectId", project).fetchOne().block(WAIT);
        var deleted = apply(first.version(), item("a", "C01", "原名", true));
        assertNotNull(contracts.findById(original.getId()).block(WAIT).getDeletedTime());
        rejects(
                mirror.apply(project, deleted.version(), List.of(item("b", "C01", "占用旧编码", false))),
                409);
        apply(deleted.version(), item("a", "C01", "恢复", false));
        var restored = contracts.findById(original.getId()).block(WAIT);
        assertFalse(restored.getDeleted());
        assertNull(restored.getDeletedTime());
        assertEquals(original.getCreateTime(), restored.getCreateTime());
    }

    @Test
    void mirrorPaginationFiltersHiddenSpaceBeforeCountAndPreservesCustomerFallback() {
        var second =
                new ContractMirrorInput(
                        "b",
                        "C02",
                        "另一房屋",
                        project,
                        "room2",
                        null,
                        "legacy-ids",
                        null,
                        "2024-01-01",
                        "2024-12-31",
                        "0",
                        false);
        apply(null, item("a", "C01", "可见", false), second);
        assertEquals(
                "legacy-ids",
                mirror.query(project, "另一房屋", 0, 12)
                        .contextWrite(permitted)
                        .block(WAIT)
                        .getData()
                        .get(0)
                        .customerName());
        bindings.deleteById(
                        bindings.createQuery()
                                .where("targetId", reader)
                                .and("assetId", "room2")
                                .fetch()
                                .map(
                                        org.jetlinks.pro.assets.impl.entity
                                                        .DimensionAssetBindingEntity
                                                ::getId))
                .block(WAIT);
        var page = mirror.query(project, null, 0, 1).contextWrite(permitted).block(WAIT);
        assertEquals(1, page.getTotal());
        assertEquals("C01", page.getData().get(0).code());
        assertEquals(
                0,
                mirror.query(project, "另一房屋", 0, 1).contextWrite(permitted).block(WAIT).getTotal());
        rejects(mirror.query(project, null, 0, 201), 400);
    }

    void sql(String sql) {
        database.sql()
                .reactive()
                .execute(org.hswebframework.ezorm.rdb.executor.SqlRequests.of(sql))
                .block(WAIT);
    }

    @Test
    void mirrorPublicCommandKeepsCallerPermissionsAndAtomicValidation() {
        var command = new org.jetlinks.pro.property.finance.command.ApplyFinanceContractsCommand();
        command.setProjectId(project);
        command.setContracts(List.of(item("a", "C01", "命令创建", false)));
        assertEquals(
                1, commands.applyContracts(command).contextWrite(permitted).block(WAIT).added());
        var noButton = reactor.util.context.Context.of(Authentication.class, assets.auth(reader));
        reactor.test.StepVerifier.create(commands.applyContracts(command).contextWrite(noButton))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        assertEquals(1, contracts.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Configuration
    @Import({
        InvoiceCompanyPersistenceTest.Config.class,
        ContractMirrorService.class,
        DocumentQueryScope.class,
        org.jetlinks.pro.property.finance.command.FinanceContractCommandSupport.class
    })
    @org.hswebframework.web.crud.annotation.EnableEasyormRepository(
            "org.jetlinks.pro.assets.impl.entity")
    @org.springframework.boot.autoconfigure.ImportAutoConfiguration(
            org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class)
    static class Config {
        @Bean
        ContractSpaceSource spaces() {
            return new ContractSpaceSource() {
                @Override
                public Mono<List<org.jetlinks.pro.property.base.archive.PropertySpaceInfo>> spaces(
                        String project) {
                    return Mono.just(
                            List.of(
                                    new org.jetlinks.pro.property.base.archive.PropertySpaceInfo(
                                            "room",
                                            null,
                                            null,
                                            "101",
                                            "5G园区/1栋/101",
                                            null,
                                            null,
                                            true),
                                    new org.jetlinks.pro.property.base.archive.PropertySpaceInfo(
                                            "room2",
                                            null,
                                            null,
                                            "102",
                                            "5G园区/1栋/102",
                                            null,
                                            null,
                                            true)));
                }
            };
        }
    }
}
