package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.web.CarryForwardRunController;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.*;

/** 日志走真实PG与HTTP；覆盖项目归属、空间筛选、只读响应及原身份重试。 */
@SpringBootTest(
        classes = CarryForwardRunAccessPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CarryForwardRunAccessPersistenceTest extends CarryForwardRunPersistenceTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        HouseSettingPersistenceTest.database(r);
    }

    @Autowired CarryForwardRunAccessService access;

    @Autowired
    org.hswebframework.ezorm.rdb.mapping.ReactiveRepository<CarryForwardPlanLogEntity, String>
            planLogs;

    @Autowired org.hswebframework.ezorm.rdb.operator.DatabaseOperator database;

    @Autowired
    org.hswebframework.ezorm.rdb.mapping.ReactiveRepository<
                    org.jetlinks.pro.assets.impl.entity.DimensionAssetBindingEntity, String>
            assetBindings;

    String path(CarryForwardRunEntity run) {
        return "/property/finance/projects/"
                + project
                + "/carry-plans/"
                + plan.getId()
                + "/runs/"
                + run.getId();
    }

    WebTestClient client() {
        return WebTestClient.bindToController(new CarryForwardRunController(access))
                .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                .webFilter(
                        (exchange, chain) ->
                                chain.filter(exchange).contextWrite(fixture.fixture.allowed()))
                .build();
    }

    @Test
    void httpHistoryHidesInternalStateAndDeletedPlanStillHasHistory() {
        topup("150.00");
        var result = execute();
        plans.createUpdate().set("deleted", true).where("id", plan.getId()).execute().block(WAIT);
        var c = client();
        c.get()
                .uri(path(result))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(result.getId())
                .jsonPath("$.configJson")
                .doesNotExist()
                .jsonPath("$.tenantId")
                .doesNotExist()
                .jsonPath("$.leaseOwner")
                .doesNotExist()
                .jsonPath("$.leaseUntil")
                .doesNotExist()
                .jsonPath("$.cursorId")
                .doesNotExist();
        c.post()
                .uri(path(result).substring(0, path(result).lastIndexOf('/')) + "/_query")
                .bodyValue(Map.of("paging", false, "pageSize", 1))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1)
                .jsonPath("$.pageSize")
                .isEqualTo(1);
        c.put()
                .uri(path(result))
                .bodyValue(Map.of("status", "SUCCEEDED"))
                .exchange()
                .expectStatus()
                .isEqualTo(405);
    }

    @Test
    void workAndItemPagesAreSpaceScopedAndMoneyIsString() {
        topup("150.00");
        var result = execute();
        var hidden = new CarryForwardWorkEntity();
        hidden.setId(UUID.randomUUID().toString());
        StandardPersistenceTest.initialize(hidden);
        hidden.setProjectId(project);
        hidden.setRunId(result.getId());
        hidden.setReceivableId("secret");
        hidden.setSpaceId("secret-room");
        hidden.setFullName("不可见房屋");
        hidden.setStatus("FAILED");
        hidden.setAttempts(1);
        works.insert(hidden).block(WAIT);
        var q = new QueryParamEntity();
        q.toQuery().or("spaceId", "secret-room");
        assertEquals(
                0, authorized(access.works(project, plan.getId(), result.getId(), q)).getTotal());
        assertEquals(
                1,
                authorized(
                                access.works(
                                        project,
                                        plan.getId(),
                                        result.getId(),
                                        new QueryParamEntity()))
                        .getTotal());
        var work =
                works.createQuery()
                        .where("runId", result.getId())
                        .and("spaceId", "room")
                        .fetchOne()
                        .block(WAIT);
        client().post()
                .uri(path(result) + "/works/" + work.getId() + "/items/_query")
                .bodyValue(Map.of())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data[0].amount")
                .isEqualTo("90.00")
                .jsonPath("$.data[0].feeReduction")
                .isEqualTo("10.00")
                .jsonPath("$.data[0].lateFeeReduction")
                .isEqualTo("0.00")
                .jsonPath("$.data[0].requestFingerprint")
                .doesNotExist();
    }

    @Test
    void forgedProjectPlanOrRunCannotReadEachOthersData() {
        var result = execute();
        fixture.rejects(access.detail("other-project", plan.getId(), result.getId()), 404);
        fixture.rejects(access.detail(project, "other-plan", result.getId()), 404);
        fixture.rejects(
                access.works(project, plan.getId(), "other-run", new QueryParamEntity()), 404);
        var q = new QueryParamEntity();
        q.toQuery().or("projectId", project);
        fixture.rejects(access.query("other-project", plan.getId(), q), 404);
        org.reactivestreams.Publisher<?> unauthenticated =
                access.detail(project, plan.getId(), result.getId());
        reactor.test.StepVerifier.create(unauthenticated).expectError().verify(WAIT);
    }

    @Test
    void explicitRetryUsesSameRunAndAuditsRequesterButInvalidPlanCannotResetWork() {
        var result = authorized(runner.prepare(project, plan.getId(), now));
        var owner = store.claim(result.getId()).block(WAIT);
        var work = store.stagePage(result.getId(), owner, 10).block(WAIT).get(0);
        store.outcome(
                        result.getId(),
                        owner,
                        work,
                        "FAILED",
                        "error.property_finance_carry_run_failed")
                .block(WAIT);
        store.finish(result.getId(), owner, true, null).block(WAIT);
        plans.createUpdate().set("enabled", false).where("id", plan.getId()).execute().block(WAIT);
        client().post().uri(path(result) + "/_retry").exchange().expectStatus().isEqualTo(409);
        assertEquals("FAILED", works.findById(work.getId()).block(WAIT).getStatus());
        plans.createUpdate().set("enabled", true).where("id", plan.getId()).execute().block(WAIT);
        topup("150.00");
        client().post()
                .uri(path(result) + "/_retry")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(result.getId())
                .jsonPath("$.status")
                .isEqualTo("SUCCEEDED");
        money(
                "60.00",
                accounts.createQuery()
                        .where("projectId", project)
                        .fetchOne()
                        .block(WAIT)
                        .getBalance());
        client().post().uri(path(result) + "/_retry").exchange().expectStatus().isEqualTo(409);
        var audit =
                planLogs.createQuery()
                        .where("planId", plan.getId())
                        .and("operation", "RUN_RETRY")
                        .fetchOne()
                        .block(WAIT);
        assertEquals(
                result.getId(),
                com.alibaba.fastjson.JSON.parseObject(audit.getAfterJson()).getString("runId"));
        assertNotNull(audit.getOperatorId());
        assertEquals(
                1,
                planLogs.createQuery()
                        .where("planId", plan.getId())
                        .and("operation", "RUN_RETRY")
                        .count()
                        .block(WAIT));
    }

    @Test
    void actualAssetHolderRejectsHiddenWorkAndWholeRetryWithoutChangingAnyWork() {
        var result = authorized(runner.prepare(project, plan.getId(), now));
        var owner = store.claim(result.getId()).block(WAIT);
        var work = store.stagePage(result.getId(), owner, 10).block(WAIT).get(0);
        store.outcome(
                        result.getId(),
                        owner,
                        work,
                        "FAILED",
                        "error.property_finance_carry_run_failed")
                .block(WAIT);
        store.finish(result.getId(), owner, true, null).block(WAIT);
        var authority = new CarryForwardAuthorityTest();
        authority.holders();
        try {
            // 最小测试容器未装配SaaS AssetsConfiguration，显式注册平台正式SQL term及真实绑定实体。
            database.getMetadata()
                    .addFeature(
                            new org.jetlinks.pro.assets.impl.terms.DimensionAssetsTermBuilder());
            var reader = "reader-" + UUID.randomUUID();
            authority.rights.put(reader, Set.of("another-room"));
            var auth = authority.auth(reader);
            var holder = org.jetlinks.pro.assets.AssetsHolder.fromAuth(auth).block(WAIT);
            var context =
                    fixture.fixture
                            .allowed()
                            .put(org.hswebframework.web.authorization.Authentication.class, auth)
                            .put(org.jetlinks.pro.assets.AssetsHolder.class, holder);
            assertEquals(
                    0,
                    access.works(project, plan.getId(), result.getId(), new QueryParamEntity())
                            .contextWrite(context)
                            .block(WAIT)
                            .getTotal());
            reactor.test.StepVerifier.create(
                            access.work(project, plan.getId(), result.getId(), work.getId())
                                    .contextWrite(context))
                    .expectError()
                    .verify(WAIT);
            reactor.test.StepVerifier.create(
                            access.items(
                                            project,
                                            plan.getId(),
                                            result.getId(),
                                            work.getId(),
                                            new QueryParamEntity())
                                    .contextWrite(context))
                    .expectError()
                    .verify(WAIT);
            reactor.test.StepVerifier.create(
                            access.retry(project, plan.getId(), result.getId())
                                    .contextWrite(context))
                    .expectError()
                    .verify(WAIT);
            assertEquals("PARTIAL", runs.findById(result.getId()).block(WAIT).getStatus());
            assertEquals("FAILED", works.findById(work.getId()).block(WAIT).getStatus());
            assertEquals(
                    0, planLogs.createQuery().where("planId", plan.getId()).count().block(WAIT));
            authority.rights.put(reader, Set.of("room"));
            var binding = new org.jetlinks.pro.assets.impl.entity.DimensionAssetBindingEntity();
            binding.setAssetId("room");
            binding.setAssetType("space");
            binding.setTargetId(reader);
            binding.setTargetType("test");
            binding.setPermission(org.jetlinks.pro.assets.crud.CrudAssetPermission.all_mask);
            binding.setUpdateTime(System.currentTimeMillis());
            binding.generateTargetKey();
            binding.setId(binding.generateId());
            assetBindings.insert(binding).block(WAIT);
            assertEquals(
                    work.getId(),
                    access.work(project, plan.getId(), result.getId(), work.getId())
                            .contextWrite(context)
                            .block(WAIT)
                            .getId());
            assertEquals(
                    1,
                    access.works(project, plan.getId(), result.getId(), new QueryParamEntity())
                            .contextWrite(context)
                            .block(WAIT)
                            .getTotal());
        } finally {
            authority.close();
        }
    }

    @Test
    void retryAuditFailureRollsBackResetInsteadOfPublishingUnrecordedRetry() throws Exception {
        var result = authorized(runner.prepare(project, plan.getId(), now));
        var owner = store.claim(result.getId()).block(WAIT);
        var work = store.stagePage(result.getId(), owner, 10).block(WAIT).get(0);
        store.outcome(
                        result.getId(),
                        owner,
                        work,
                        "FAILED",
                        "error.property_finance_carry_run_failed")
                .block(WAIT);
        store.finish(result.getId(), owner, true, null).block(WAIT);
        var constraint = "retry_audit_" + UUID.randomUUID().toString().replace("-", "");
        assertEquals(
                0,
                HouseSettingPersistenceTest.DB
                        .execInContainer(
                                "psql",
                                "-U",
                                "postgres",
                                "-d",
                                "setting_test",
                                "-c",
                                "ALTER TABLE pm_fin_carry_plan_log ADD CONSTRAINT "
                                        + constraint
                                        + " CHECK (plan_id <> '"
                                        + plan.getId()
                                        + "')")
                        .getExitCode());
        try {
            reactor.test.StepVerifier.create(
                            access.retry(project, plan.getId(), result.getId())
                                    .contextWrite(fixture.fixture.allowed()))
                    .expectError()
                    .verify(WAIT);
            assertEquals("PARTIAL", runs.findById(result.getId()).block(WAIT).getStatus());
            assertEquals("FAILED", works.findById(work.getId()).block(WAIT).getStatus());
        } finally {
            assertEquals(
                    0,
                    HouseSettingPersistenceTest.DB
                            .execInContainer(
                                    "psql",
                                    "-U",
                                    "postgres",
                                    "-d",
                                    "setting_test",
                                    "-c",
                                    "ALTER TABLE pm_fin_carry_plan_log DROP CONSTRAINT "
                                            + constraint)
                            .getExitCode());
        }
    }

    @Configuration
    @org.hswebframework.web.crud.annotation.EnableEasyormRepository(
            "org.jetlinks.pro.assets.impl.entity")
    @Import({CarryForwardRunPersistenceTest.Config.class, CarryForwardRunAccessService.class})
    static class Config {}
}
