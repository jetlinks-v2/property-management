package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.finance.carry.CarryForwardPlanInput;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.core.publisher.*;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.*;

/** 真实PG验证项目计划、科目占用、并发版本、逻辑删除和审计原子性。 */
@SpringBootTest(
        classes = CarryForwardPlanPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CarryForwardPlanPersistenceTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        HouseSettingPersistenceTest.database(r);
    }

    @Autowired CarryForwardPlanService service;
    @Autowired HouseArchiveSource archive;
    @Autowired ReactiveRepository<StandardEntity, String> standards;
    @Autowired ReactiveRepository<ProjectSubjectEntity, String> issued;
    @Autowired ReactiveRepository<SubjectEntity, String> subjects;
    @Autowired ReactiveRepository<CarryForwardPlanEntity, String> plans;
    @Autowired ReactiveRepository<CarryForwardSubjectEntity, String> claims;
    @Autowired ReactiveRepository<CarryForwardPlanLogEntity, String> logs;
    @Autowired ProjectSubjectService projectSubjects;
    @Autowired SubjectService subjectService;
    HouseSettingPersistenceTest fixture;

    @BeforeEach
    void setup() {
        fixture = new HouseSettingPersistenceTest();
        fixture.archive = archive;
        fixture.standards = standards;
        fixture.subjects = issued;
        fixture.subjectRows = subjects;
        fixture.setup();
    }

    CarryForwardPlanInput input() {
        var i = new CarryForwardPlanInput();
        i.setName("物业费自动结转");
        i.setSubjectIds(List.of(fixture.subjectId));
        i.setPeriodStart("2024-01-01");
        i.setPeriodEnd("2024-12-31");
        i.setFrequency("MONTHLY");
        i.setExecuteDay("31");
        i.setExecuteAt("13:00:00");
        i.setZone("Asia/Shanghai");
        i.setRemissionMode("FIXED");
        i.setRemissionValue(new BigDecimal("10.00"));
        return i;
    }

    <T> T run(Mono<T> action) {
        return action.contextWrite(fixture.allowed()).block(WAIT);
    }

    CarryForwardPlanService.Detail create() {
        return run(service.create(fixture.project, input()));
    }

    void reject(Mono<?> action, int status) {
        StepVerifier.create(action.contextWrite(fixture.allowed()))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, status))
                .verify(WAIT);
    }

    @Test
    void optionsOnlyExposeIssuedProjectSubjectsAndRetainDisabledNamesForReadOnlyDetails() {
        assertEquals(
                List.of(fixture.subjectId),
                run(service.options(fixture.project)).stream()
                        .map(CarryForwardPlanService.SubjectOption::id)
                        .toList());
        subjects.createUpdate()
                .set("enabled", false)
                .where("id", fixture.subjectId)
                .execute()
                .block(WAIT);
        assertFalse(run(service.options(fixture.project)).get(0).enabled());
        assertTrue(run(service.options("other-project")).isEmpty());
        issued.createUpdate()
                .set("deleted", true)
                .where("projectId", fixture.project)
                .execute()
                .block(WAIT);
        assertTrue(run(service.options(fixture.project)).isEmpty());
    }

    @Test
    void createAndReadReturnBusinessConfigurationAndClaimIssuedSubjects() {
        var d = create();
        assertEquals(fixture.project, d.plan().getProjectId());
        assertTrue(d.plan().getEnabled());
        assertEquals("31", d.input().getExecuteDay());
        assertEquals(new BigDecimal("10.00"), d.input().getRemissionValue());
        assertEquals(
                1,
                claims.createQuery()
                        .where("planId", d.plan().getId())
                        .and("deleted", false)
                        .count()
                        .block(WAIT));
        var detail = run(service.detail(fixture.project, d.plan().getId()));
        assertEquals(d.plan().getVersion(), detail.plan().getVersion());
        assertEquals(List.of(fixture.subjectId), detail.input().getSubjectIds());
        assertNotNull(plans.findById(d.plan().getId()).block(WAIT).getExecutorSnapshot());
        assertEquals(
                1,
                run(service.history(fixture.project, d.plan().getId(), new QueryParamEntity()))
                        .getTotal());
        var audit = logs.createQuery().where("planId", d.plan().getId()).fetchOne().block(WAIT);
        assertEquals(
                "物业费自动结转",
                com.alibaba
                        .fastjson
                        .JSON
                        .parseObject(audit.getAfterJson())
                        .getJSONObject("input")
                        .getString("name"));
    }

    @Test
    void concurrentPlansForSameProjectSubjectCannotSilentlyStealOrSkipSubjects() {
        var outcomes =
                Flux.merge(
                                service.create(fixture.project, input())
                                        .map(x -> "OK")
                                        .onErrorResume(e -> Mono.just("FAIL")),
                                service.create(fixture.project, input())
                                        .map(x -> "OK")
                                        .onErrorResume(e -> Mono.just("FAIL")))
                        .contextWrite(fixture.allowed())
                        .collectList()
                        .block(WAIT);
        assertEquals(1, outcomes.stream().filter("OK"::equals).count());
        assertEquals(
                1,
                claims.createQuery()
                        .where("projectId", fixture.project)
                        .and("deleted", false)
                        .count()
                        .block(WAIT));
        reject(service.create(fixture.project, input()), 409);
        assertEquals(
                1, plans.createQuery().where("projectId", fixture.project).count().block(WAIT));
    }

    @Test
    void disabledPlanKeepsItsSubjectUntilLogicalDeletionAndHistorySurvives() {
        var d = create();
        var id = d.plan().getId();
        var disabled = run(service.state(fixture.project, id, d.plan().getVersion(), false));
        assertFalse(disabled.plan().getEnabled());
        assertFalse(disabled.input().getEnabled());
        reject(service.create(fixture.project, input()), 409);
        run(service.delete(fixture.project, id, disabled.plan().getVersion()));
        reject(service.detail(fixture.project, id), 404);
        assertTrue(plans.findById(id).block(WAIT).getDeleted());
        assertEquals(
                3, run(service.history(fixture.project, id, new QueryParamEntity())).getTotal());
        assertEquals(
                0,
                claims.createQuery().where("planId", id).and("deleted", false).count().block(WAIT));
        assertNotEquals(id, create().plan().getId());
    }

    @Test
    void staleEditOrDeleteCannotOverwriteCurrentPlanAndForgedQueryCannotEscapeProject() {
        var d = create();
        var i = input();
        i.setVersion(d.plan().getVersion());
        i.setName("新名称");
        var edited = run(service.update(fixture.project, d.plan().getId(), i));
        assertEquals("新名称", edited.input().getName());
        reject(service.update(fixture.project, d.plan().getId(), i), 409);
        reject(service.delete(fixture.project, d.plan().getId(), d.plan().getVersion()), 409);
        reject(service.detail("other-project", d.plan().getId()), 404);
        var q = new QueryParamEntity();
        q.toQuery().or("projectId", fixture.project);
        assertEquals(0, run(service.query("other-project", q)).getTotal());
        assertEquals(1, run(service.query(fixture.project, new QueryParamEntity())).getTotal());
        assertEquals(2, logs.createQuery().where("planId", d.plan().getId()).count().block(WAIT));
    }

    @Test
    void missingDisabledOrUnissuedSubjectRejectsWholePlan() {
        var i = input();
        i.setSubjectIds(List.of(fixture.subjectId, "missing"));
        reject(service.create(fixture.project, i), 409);
        subjects.createUpdate()
                .set("enabled", false)
                .where("id", fixture.subjectId)
                .execute()
                .block(WAIT);
        reject(service.create(fixture.project, input()), 409);
        subjects.createUpdate()
                .set("enabled", true)
                .where("id", fixture.subjectId)
                .execute()
                .block(WAIT);
        issued.createUpdate()
                .set("deleted", true)
                .where("projectId", fixture.project)
                .execute()
                .block(WAIT);
        reject(service.create(fixture.project, input()), 409);
        assertEquals(
                0, plans.createQuery().where("projectId", fixture.project).count().block(WAIT));
        assertEquals(
                0, claims.createQuery().where("projectId", fixture.project).count().block(WAIT));
    }

    @Test
    void changingSubjectsReleasesOldClaimAndConflictRollsBackConfigAndClaimChanges() {
        var d = create();
        var other = new HouseSettingPersistenceTest();
        other.archive = archive;
        other.standards = standards;
        other.subjects = issued;
        other.subjectRows = subjects;
        other.setup();
        var relation = new ProjectSubjectEntity();
        relation.setId(UUID.randomUUID().toString());
        StandardPersistenceTest.initialize(relation);
        relation.setProjectId(fixture.project);
        relation.setSubjectId(other.subjectId);
        issued.insert(relation).block(WAIT);
        var i = input();
        i.setVersion(d.plan().getVersion());
        i.setSubjectIds(List.of(other.subjectId));
        var edited = run(service.update(fixture.project, d.plan().getId(), i));
        create(); // 原科目已释放。
        i.setVersion(edited.plan().getVersion());
        i.setSubjectIds(List.of(fixture.subjectId));
        reject(service.update(fixture.project, d.plan().getId(), i), 409);
        assertEquals(
                List.of(other.subjectId),
                run(service.detail(fixture.project, d.plan().getId())).input().getSubjectIds());
        assertEquals(
                1,
                claims.createQuery()
                        .where("planId", d.plan().getId())
                        .and("deleted", false)
                        .count()
                        .block(WAIT));
    }

    @Test
    void auditFailureRollsBackPlanAndClaims() throws Exception {
        var constraint = "carry_plan_" + UUID.randomUUID().toString().replace("-", "");
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
                                        + " CHECK (project_id <> '"
                                        + fixture.project
                                        + "')")
                        .getExitCode());
        try {
            StepVerifier.create(
                            service.create(fixture.project, input())
                                    .contextWrite(fixture.allowed()))
                    .expectErrorMatches(e -> causedBy(e, constraint))
                    .verify(WAIT);
            assertEquals(
                    0, plans.createQuery().where("projectId", fixture.project).count().block(WAIT));
            assertEquals(
                    0,
                    claims.createQuery().where("projectId", fixture.project).count().block(WAIT));
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

    boolean causedBy(Throwable e, String text) {
        for (var cause = e; cause != null; cause = cause.getCause())
            if (Objects.toString(cause.getMessage(), "").contains(text)) return true;
        return false;
    }

    @Test
    void issuedSubjectCannotBeWithdrawnWhileEvenDisabledPlanOwnsIt() {
        var d = create();
        standards
                .createUpdate()
                .set("deleted", true)
                .where("projectId", fixture.project)
                .execute()
                .block(WAIT);
        var disabled =
                run(service.state(fixture.project, d.plan().getId(), d.plan().getVersion(), false));
        var relation = issued.findById(fixture.relationId).block(WAIT);
        reject(
                projectSubjects.delete(fixture.project, relation.getId(), relation.getVersion()),
                409);
        run(service.delete(fixture.project, d.plan().getId(), disabled.plan().getVersion()));
        run(projectSubjects.delete(fixture.project, relation.getId(), relation.getVersion()));
        assertTrue(issued.findById(relation.getId()).block(WAIT).getDeleted());
    }

    @Test
    void httpReturnsConfigurationWithoutInternalTenantOrRawJsonAndKeepsDeletionLogical() {
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web
                                        .CarryForwardPlanController(service))
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange).contextWrite(fixture.allowed()))
                        .build();
        var d = create();
        var path =
                "/property/finance/projects/"
                        + fixture.project
                        + "/carry-plans/"
                        + d.plan().getId();
        client.get()
                .uri(path)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.input.name")
                .isEqualTo("物业费自动结转")
                .jsonPath("$.input.executeDay")
                .isEqualTo("31")
                .jsonPath("$.input.remissionValue")
                .isEqualTo("10.00")
                .jsonPath("$.plan.configJson")
                .doesNotExist()
                .jsonPath("$.plan.tenantId")
                .doesNotExist()
                .jsonPath("$.plan.executorSnapshot")
                .doesNotExist();
        client.put()
                .uri(path + "/_state")
                .bodyValue(Map.of("version", d.plan().getVersion(), "enabled", false))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.input.enabled")
                .isEqualTo(false);
        client.delete()
                .uri(path + "?version=" + (d.plan().getVersion() + 1))
                .exchange()
                .expectStatus()
                .isOk();
        assertTrue(plans.findById(d.plan().getId()).block(WAIT).getDeleted());
    }

    @Test
    void noAuthenticationAndDeniedProjectCannotCreateOrReadConfiguration() {
        // 通用持久化fixture隔离了项目命令，拒绝路径改用真实FinanceScope验证实际资产边界。
        var secured =
                new CarryForwardPlanService(
                        new FinanceScope(), subjectService, issued, plans, claims, logs, subjects);
        StepVerifier.create(secured.create(fixture.project, input())).expectError().verify(WAIT);
        var holder = org.mockito.Mockito.mock(org.jetlinks.pro.assets.AssetsHolder.class);
        org.mockito.Mockito.when(
                        holder.assertPermission(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyCollection(),
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.error(FinanceScope.error("permission_denied", 403)));
        StepVerifier.create(
                        secured.create(fixture.project, input())
                                .contextWrite(
                                        fixture.allowed()
                                                .put(
                                                        org.jetlinks.pro.assets.AssetsHolder.class,
                                                        holder)))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        assertEquals(
                0, plans.createQuery().where("projectId", fixture.project).count().block(WAIT));
    }

    @Configuration
    @Import({HouseSettingPersistenceTest.Config.class, CarryForwardPlanService.class})
    static class Config {}
}
