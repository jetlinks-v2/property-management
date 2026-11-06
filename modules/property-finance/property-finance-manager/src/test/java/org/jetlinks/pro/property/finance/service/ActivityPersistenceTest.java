package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.base.archive.PropertySpaceInfo;
import org.jetlinks.pro.property.base.dictionary.PropertyDictionaryOption;
import org.jetlinks.pro.property.finance.activity.ActivityInput;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.core.publisher.*;
import reactor.test.StepVerifier;

import java.util.*;

/** 真PG验证活动按钮审批、版本、空间权限、逻辑删除、引用和审计的原子性。 */
@SpringBootTest(
        classes = ActivityPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ActivityPersistenceTest {
    static final java.time.Duration WAIT = HouseSettingPersistenceTest.WAIT;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        HouseSettingPersistenceTest.database(r);
    }

    @Autowired ActivityService service;
    @Autowired ActivitySource source;
    @Autowired HouseArchiveSource archive;
    @Autowired ReactiveRepository<StandardEntity, String> standards;
    @Autowired ReactiveRepository<ProjectSubjectEntity, String> issued;
    @Autowired ReactiveRepository<SubjectEntity, String> subjects;
    @Autowired ReactiveRepository<ActivityEntity, String> activities;
    @Autowired ReactiveRepository<ActivityLogEntity, String> logs;
    @Autowired StandardService standardService;
    HouseSettingPersistenceTest fixture;

    @BeforeEach
    void setup() {
        fixture = new HouseSettingPersistenceTest();
        fixture.archive = archive;
        fixture.standards = standards;
        fixture.subjects = issued;
        fixture.subjectRows = subjects;
        fixture.setup();
        reset(source);
        when(source.spaces(fixture.project))
                .thenReturn(
                        Mono.just(
                                List.of(
                                        new PropertySpaceInfo(
                                                "root",
                                                null,
                                                "root",
                                                "1栋",
                                                "1栋",
                                                "building",
                                                "楼栋",
                                                true))));
        when(source.types())
                .thenReturn(Mono.just(List.of(new PropertyDictionaryOption("prepay", "充值赠送"))));
    }

    ActivityInput input() {
        var i = new ActivityInput();
        i.setName("新年充值赠送");
        i.setActivityType("prepay");
        i.setSubjectId(fixture.subjectId);
        i.setStandardId(fixture.standardId);
        i.setStartDate("2026-01-01");
        i.setEndDate("2026-12-31");
        i.setTargetIds(List.of("root"));
        i.setTiers(List.of(new ActivityInput.Tier(12, 1)));
        return i;
    }

    <T> T run(Mono<T> action) {
        return action.contextWrite(fixture.allowed()).block(WAIT);
    }

    ActivityService.Detail create() {
        return run(service.save(fixture.project, null, input()));
    }

    void reject(Mono<?> action, int status) {
        StepVerifier.create(action.contextWrite(fixture.allowed()))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, status))
                .verify(WAIT);
    }

    @Test
    void activityCreateAndUpdatePersistDisplayNameInRecordAndHistory() {
        var context =
                fixture.allowed()
                        .put(
                                org.hswebframework.web.authorization.Authentication.class,
                                FinanceOperatorNameTest.actor("龚超"));
        var created =
                service.save(fixture.project, null, input()).contextWrite(context).block(WAIT);
        assertEquals("龚超", created.activity().getModifierName());
        assertEquals("operator-id", created.activity().getModifierId());
        var edit = input();
        edit.setVersion(created.activity().getVersion());
        edit.setName("更新后的活动");
        var updated =
                service.save(fixture.project, created.activity().getId(), edit)
                        .contextWrite(context)
                        .block(WAIT);
        assertEquals("龚超", updated.activity().getModifierName());
        var history =
                service.history(fixture.project, created.activity().getId(), new QueryParamEntity())
                        .contextWrite(context)
                        .block(WAIT);
        assertEquals(2, history.getTotal());
        assertTrue(
                history.getData().stream()
                        .allMatch(
                                log ->
                                        "龚超".equals(log.getOperatorName())
                                                && "operator-id".equals(log.getOperatorId())));
    }

    @Test
    void createResolvesServerNamesAndWaitsForButtonReview() {
        var d = create();
        assertFalse(d.activity().getEnabled());
        assertEquals("PENDING", d.activity().getReviewState());
        assertEquals("充值赠送", d.config().getActivityTypeName());
        assertEquals("面积月费", d.config().getStandardName());
        assertEquals("1栋", d.config().getTargets().get(0).fullName());
        assertEquals(
                d.activity().getId(),
                run(service.detail(fixture.project, d.activity().getId())).activity().getId());
        var saved = activities.findById(d.activity().getId()).block(WAIT);
        assertFalse(saved.getDeleted());
        assertEquals(
                1,
                run(service.history(fixture.project, saved.getId(), new QueryParamEntity()))
                        .getTotal());
    }

    @Test
    void ownOptionsExposeOnlyIssuedProjectStandards() {
        var options = run(service.options(fixture.project));
        assertEquals(1, ((List<?>) options.get("standards")).size());
        issued.createUpdate()
                .set("deleted", true)
                .where("projectId", fixture.project)
                .execute()
                .block(WAIT);
        assertTrue(((List<?>) run(service.options(fixture.project)).get("standards")).isEmpty());
    }

    @Test
    void httpSerializesTiersAndTargetsAndOnlyOffersReadOnlyDetail() {
        var d = create();
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web.ActivityController(
                                        service))
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange).contextWrite(fixture.allowed()))
                        .build();
        var path =
                "/property/finance/projects/"
                        + fixture.project
                        + "/activities/"
                        + d.activity().getId();
        client.get()
                .uri(path)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.config.input.tiers[0].paidMonths")
                .isEqualTo(12)
                .jsonPath("$.config.targets[0].id")
                .isEqualTo("root")
                .jsonPath("$.activity.configJson")
                .doesNotExist();
        client.put()
                .uri(path + "/_state")
                .bodyValue(
                        new org.jetlinks.pro.property.finance.web.ActivityController.StateInput(
                                0L, false))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.activity.enabled")
                .isEqualTo(false);
        client.delete().uri(path + "?version=1").exchange().expectStatus().isOk();
        client.get().uri(path).exchange().expectStatus().isNotFound();
    }

    @Test
    void foreignProjectsAndForgedTargetsAreRejected() {
        var d = create();
        reject(service.detail("other", d.activity().getId()), 404);
        reject(service.save("other", null, input()), 409);
        var i = input();
        i.setTargetIds(List.of("foreign-space"));
        reject(service.save(fixture.project, null, i), 400);
        assertEquals(
                1,
                activities.createQuery().where("projectId", fixture.project).count().block(WAIT));
    }

    @Test
    void inactiveDictionaryAndQuarterlyMismatchDoNotCreateRows() {
        when(source.types()).thenReturn(Mono.just(List.of()));
        reject(service.save(fixture.project, null, input()), 400);
        when(source.types())
                .thenReturn(Mono.just(List.of(new PropertyDictionaryOption("prepay", "充值赠送"))));
        standards
                .createUpdate()
                .set("settlementMethod", "QUARTERLY")
                .where("id", fixture.standardId)
                .execute()
                .block(WAIT);
        reject(service.save(fixture.project, null, input()), 400);
        assertEquals(
                0,
                activities.createQuery().where("projectId", fixture.project).count().block(WAIT));
    }

    @Test
    void concurrentEditsHaveOneWinnerAndPreserveAudit() {
        var d = create();
        var a = input();
        a.setVersion(0L);
        a.setName("编辑一");
        var b = input();
        b.setVersion(0L);
        b.setName("编辑二");
        var results =
                Flux.merge(
                                service.save(fixture.project, d.activity().getId(), a)
                                        .map(x -> true)
                                        .onErrorReturn(false),
                                service.save(fixture.project, d.activity().getId(), b)
                                        .map(x -> true)
                                        .onErrorReturn(false))
                        .contextWrite(fixture.allowed())
                        .collectList()
                        .block(WAIT);
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1L, activities.findById(d.activity().getId()).block(WAIT).getVersion());
        assertEquals(
                2,
                run(service.history(fixture.project, d.activity().getId(), new QueryParamEntity()))
                        .getTotal());
    }

    @Test
    void disableAuditContainsOriginalStateAndDeletionRetainsHistory() {
        var d = create();
        activities
                .createUpdate()
                .set("reviewState", "APPROVED")
                .set("enabled", true)
                .where("id", d.activity().getId())
                .execute()
                .block(WAIT);
        run(service.state(fixture.project, d.activity().getId(), 0L, false));
        var log =
                logs.createQuery()
                        .where("activityId", d.activity().getId())
                        .and("operation", "DISABLE")
                        .fetchOne()
                        .block(WAIT);
        assertTrue(
                com.alibaba
                        .fastjson
                        .JSON
                        .parseObject(log.getBeforeJson())
                        .getJSONObject("activity")
                        .getBoolean("enabled"));
        assertFalse(
                com.alibaba
                        .fastjson
                        .JSON
                        .parseObject(log.getAfterJson())
                        .getJSONObject("activity")
                        .getBoolean("enabled"));
        run(service.delete(fixture.project, d.activity().getId(), 1L));
        reject(service.detail(fixture.project, d.activity().getId()), 404);
        assertTrue(activities.findById(d.activity().getId()).block(WAIT).getDeleted());
        assertEquals(
                3,
                run(service.history(fixture.project, d.activity().getId(), new QueryParamEntity()))
                        .getTotal());
    }

    @Test
    void orFiltersCannotEscapeProjectAndQueryDoesNotExposeSpacePaths() throws Exception {
        create();
        var q = new QueryParamEntity();
        q.or("projectId", "eq", "other");
        q.setPaging(false);
        q.setForUpdate(true);
        assertEquals(0, run(service.query("other", q)).getTotal());
        var json =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(run(service.query(fixture.project, q)));
        assertFalse(json.contains("targetIds"));
        assertFalse(json.contains("configJson"));
        assertFalse(json.contains("fullName"));
    }

    @Test
    void referencedStandardCannotBeDeletedUntilActivityIsLogicallyDeleted() {
        var d = create();
        reject(standardService.delete(fixture.project, fixture.standardId, 0L), 409);
        run(service.delete(fixture.project, d.activity().getId(), 0L));
        run(standardService.delete(fixture.project, fixture.standardId, 0L));
        assertTrue(standards.findById(fixture.standardId).block(WAIT).getDeleted());
    }

    @Test
    void deniedSpaceCannotReadOrEditOrDeleteAndLeavesRowUnchanged() {
        var d = create();
        var denied = mock(org.jetlinks.pro.assets.AssetsHolder.class);
        when(denied.assertPermission(anyString(), anyCollection(), any()))
                .thenReturn(Mono.error(FinanceScope.error("permission_denied", 403)));
        var context = fixture.allowed().put(org.jetlinks.pro.assets.AssetsHolder.class, denied);
        StepVerifier.create(
                        service.detail(fixture.project, d.activity().getId()).contextWrite(context))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        StepVerifier.create(
                        service.state(fixture.project, d.activity().getId(), 0L, false)
                                .contextWrite(context))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        StepVerifier.create(
                        service.delete(fixture.project, d.activity().getId(), 0L)
                                .contextWrite(context))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        assertEquals(0L, activities.findById(d.activity().getId()).block(WAIT).getVersion());
    }

    @Test
    void auditFailureRollsBackEntireCreate() throws Exception {
        var constraint = "fail_activity_" + UUID.randomUUID().toString().replace("-", "");
        var result =
                HouseSettingPersistenceTest.DB.execInContainer(
                        "psql",
                        "-U",
                        "postgres",
                        "-d",
                        "setting_test",
                        "-c",
                        "ALTER TABLE pm_fin_activity_log ADD CONSTRAINT "
                                + constraint
                                + " CHECK (project_id <> '"
                                + fixture.project
                                + "') NOT VALID");
        assertEquals(0, result.getExitCode(), result.getStderr());
        try {
            StepVerifier.create(
                            service.save(fixture.project, null, input())
                                    .contextWrite(fixture.allowed()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(
                    0,
                    activities
                            .createQuery()
                            .where("projectId", fixture.project)
                            .count()
                            .block(WAIT));
        } finally {
            HouseSettingPersistenceTest.DB.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "setting_test",
                    "-c",
                    "ALTER TABLE pm_fin_activity_log DROP CONSTRAINT " + constraint);
        }
    }

    ActivityService.ReviewInput review(ActivityService.Detail row, String reason) {
        return new ActivityService.ReviewInput(
                List.of(
                        new ActivityService.ReviewItem(
                                row.activity().getId(), row.activity().getVersion())),
                reason);
    }

    @Test
    void reviewApprovesAndEnablesButEditingResetsApprovalAndBlocksDirectEnable() {
        var d = create();
        reject(service.state(fixture.project, d.activity().getId(), 0L, true), 409);
        var approved = run(service.review(fixture.project, review(d, "核对范围及档位"), true)).get(0);
        assertEquals("APPROVED", approved.activity().getReviewState());
        assertTrue(approved.activity().getEnabled());
        assertNotNull(approved.activity().getReviewedAt());
        assertEquals("核对范围及档位", approved.activity().getReviewReason());
        var edit = input();
        edit.setVersion(approved.activity().getVersion());
        edit.setName("重新提交");
        var pending = run(service.save(fixture.project, d.activity().getId(), edit));
        assertEquals("PENDING", pending.activity().getReviewState());
        assertFalse(pending.activity().getEnabled());
        assertNull(pending.activity().getReviewedBy());
        assertNull(pending.activity().getReviewReason());
        var rejected =
                run(service.review(fixture.project, review(pending, "修改优惠月份"), false)).get(0);
        assertEquals("REJECTED", rejected.activity().getReviewState());
        assertFalse(rejected.activity().getEnabled());
        reject(
                service.state(
                        fixture.project,
                        d.activity().getId(),
                        rejected.activity().getVersion(),
                        true),
                409);
        reject(service.review(fixture.project, review(rejected, "重复审批"), true), 409);
        edit.setVersion(rejected.activity().getVersion());
        var again = run(service.save(fixture.project, d.activity().getId(), edit));
        assertTrue(
                run(service.review(fixture.project, review(again, "修改后通过"), true))
                        .get(0)
                        .activity()
                        .getEnabled());
    }

    @Test
    void batchReviewRejectsInvalidVersionOrProjectWithoutPartialWrites() {
        var a = create();
        var b = create();
        var invalid =
                new ActivityService.ReviewInput(
                        List.of(
                                new ActivityService.ReviewItem(a.activity().getId(), 0L),
                                new ActivityService.ReviewItem(b.activity().getId(), 99L)),
                        "批量核对");
        reject(service.review(fixture.project, invalid, true), 409);
        assertEquals(
                "PENDING", activities.findById(a.activity().getId()).block(WAIT).getReviewState());
        assertEquals(0L, activities.findById(a.activity().getId()).block(WAIT).getVersion());
        reject(service.review("other-project", review(a, "越项目"), true), 404);
        reject(service.review(fixture.project, review(a, "  "), true), 400);
        var duplicate =
                new ActivityService.ReviewInput(
                        List.of(
                                new ActivityService.ReviewItem(a.activity().getId(), 0L),
                                new ActivityService.ReviewItem(a.activity().getId(), 0L)),
                        "重复");
        reject(service.review(fixture.project, duplicate, true), 400);
        var approved =
                run(
                        service.review(
                                fixture.project,
                                new ActivityService.ReviewInput(
                                        List.of(
                                                new ActivityService.ReviewItem(
                                                        a.activity().getId(), 0L),
                                                new ActivityService.ReviewItem(
                                                        b.activity().getId(), 0L)),
                                        "全部通过"),
                                true));
        assertEquals(2, approved.size());
        assertTrue(approved.stream().allMatch(row -> row.activity().getEnabled()));
    }

    @Test
    void reviewChecksEveryTargetPermissionAndAuditFailureRollsBack() throws Exception {
        var d = create();
        var denied = mock(org.jetlinks.pro.assets.AssetsHolder.class);
        when(denied.assertPermission(anyString(), anyCollection(), any()))
                .thenReturn(Mono.error(FinanceScope.error("permission_denied", 403)));
        StepVerifier.create(
                        service.review(fixture.project, review(d, "无权审批"), true)
                                .contextWrite(
                                        fixture.allowed()
                                                .put(
                                                        org.jetlinks.pro.assets.AssetsHolder.class,
                                                        denied)))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        var constraint = "fail_review_" + UUID.randomUUID().toString().replace("-", "");
        var sql =
                "ALTER TABLE pm_fin_activity_log ADD CONSTRAINT "
                        + constraint
                        + " CHECK (operation <> 'APPROVE' OR project_id <> '"
                        + fixture.project
                        + "') NOT VALID";
        assertEquals(
                0,
                HouseSettingPersistenceTest.DB
                        .execInContainer("psql", "-U", "postgres", "-d", "setting_test", "-c", sql)
                        .getExitCode());
        try {
            StepVerifier.create(
                            service.review(fixture.project, review(d, "审计故障"), true)
                                    .contextWrite(fixture.allowed()))
                    .expectError()
                    .verify(WAIT);
            var actual = activities.findById(d.activity().getId()).block(WAIT);
            assertEquals("PENDING", actual.getReviewState());
            assertFalse(actual.getEnabled());
            assertEquals(0L, actual.getVersion());
        } finally {
            HouseSettingPersistenceTest.DB.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "setting_test",
                    "-c",
                    "ALTER TABLE pm_fin_activity_log DROP CONSTRAINT " + constraint);
        }
    }

    @Configuration
    @Import({HouseSettingPersistenceTest.Config.class, ActivityService.class})
    static class Config {
        @Bean
        ActivitySource activitySource() {
            return mock(ActivitySource.class);
        }
    }
}
