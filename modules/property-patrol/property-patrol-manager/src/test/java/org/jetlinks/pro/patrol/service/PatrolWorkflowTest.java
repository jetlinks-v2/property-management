package org.jetlinks.pro.patrol.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyCollection;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.jetlinks.pro.assets.AssetManager;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolderHolder;
import org.jetlinks.pro.patrol.asset.PatrolAssetSupplier;
import org.jetlinks.pro.patrol.domain.ExecutionPolicy;
import org.jetlinks.pro.patrol.domain.PatrolRule;
import org.jetlinks.pro.patrol.domain.RuleEngine;
import org.jetlinks.pro.patrol.entity.PatrolCategoryEntity;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity;
import org.jetlinks.pro.patrol.entity.PatrolGenerationEntity;
import org.jetlinks.pro.patrol.entity.PatrolPlanEntity;
import org.jetlinks.pro.patrol.entity.PatrolPointEntity;
import org.jetlinks.pro.patrol.entity.PatrolPostEntity;
import org.jetlinks.pro.patrol.entity.PatrolRosterEntity;
import org.jetlinks.pro.patrol.entity.PatrolStandardEntity;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.jetlinks.pro.patrol.integration.SaasPersonDirectory;
import org.jetlinks.pro.tenant.assets.TenantAssetsHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

class PatrolWorkflowTest {
    PatrolDatabase db;
    PatrolTaskService tasks;
    PatrolExecutionService executions;
    PatrolPlanService plans;
    PatrolPostService posts;
    PatrolPointService points;
    PatrolStandardService standards;
    PatrolRosterService rosters;
    PatrolTaskGenerator generator;
    PatrolExecutionWorkflow workflow;
    ReactiveRepository<PatrolTaskEntity, String> taskRepository;
    Context identity;
    Disposable provider;
    ReactiveRepository<PatrolGenerationEntity, String> generationRepository;

    @BeforeEach
    void setup() {
        db = new PatrolDatabase();
        taskRepository = db.repository(PatrolTaskEntity.class, PatrolTaskEntity::new);
        tasks = new PatrolTaskService(taskRepository);
        executions =
                new PatrolExecutionService(
                        db.repository(PatrolExecutionEntity.class, PatrolExecutionEntity::new));
        plans = new PatrolPlanService(db.repository(PatrolPlanEntity.class, PatrolPlanEntity::new));
        posts = new PatrolPostService(db.repository(PatrolPostEntity.class, PatrolPostEntity::new));
        points =
                new PatrolPointService(
                        db.repository(PatrolPointEntity.class, PatrolPointEntity::new));
        standards =
                new PatrolStandardService(
                        db.repository(PatrolStandardEntity.class, PatrolStandardEntity::new));
        rosters =
                new PatrolRosterService(
                        db.repository(PatrolRosterEntity.class, PatrolRosterEntity::new));
        var categories =
                new PatrolCategoryService(
                        db.repository(PatrolCategoryEntity.class, PatrolCategoryEntity::new));
        var supplier =
                new PatrolAssetSupplier(categories, points, standards, posts, rosters, plans);
        AssetManager manager = mock(AssetManager.class);
        when(manager.getAssets(any(AssetType.class), anyCollection()))
                .thenAnswer(inv -> supplier.getAssets(inv.getArgument(0), inv.getArgument(1)));
        provider =
                AssetsHolderHolder.registerProvider(
                        auth -> Mono.just(new TenantAssetsHolder(Set.of("north"), manager)));
        SimpleUser user = new SimpleUser();
        user.setId("alice");
        user.setName("Alice");
        SimpleAuthentication auth = new SimpleAuthentication();
        auth.setUser(user);
        identity = Context.of(Authentication.class, auth);
        var people = mock(SaasPersonDirectory.class);
        when(people.assertMembers(List.of("alice"))).thenReturn(Mono.empty());
        workflow =
                new PatrolExecutionWorkflow(
                        tasks,
                        plans,
                        points,
                        posts,
                        rosters,
                        executions,
                        people,
                        TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
        generationRepository =
                db.repository(PatrolGenerationEntity.class, PatrolGenerationEntity::new);
        generator =
                new PatrolTaskGenerator(
                        plans,
                        points,
                        posts,
                        standards,
                        tasks,
                        generationRepository,
                        TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
        var point = new PatrolPointEntity();
        point.setId("point");
        point.setName("Gate");
        point.setScopeKey("north|app");
        point.setCode("P001");
        point.setCategoryId("category");
        point.setExtension("tenantId", "north");
        points.insert(point).block();
        var post = new PatrolPostEntity();
        post.setId("post");
        post.setName("Security");
        post.setScopeKey("north|app");
        post.setUserIds(List.of("alice"));
        post.setExtension("tenantId", "north");
        posts.insert(post).block();
        var standard = new PatrolStandardEntity();
        standard.setId("standard");
        standard.setName("Safety");
        standard.setScopeKey("north|app");
        standard.setItems(
                List.of(
                        new PatrolStandardEntity.CheckItem(
                                "lock", "Lock", true, List.of("normal", "abnormal"))));
        standard.setExtension("tenantId", "north");
        standards.insert(standard).block();
        seedPlan("plan", "north");
    }

    void seedPlan(String id, String tenant) {
        var plan = new PatrolPlanEntity();
        plan.setId(id);
        plan.setName("Night patrol");
        plan.setScopeKey(tenant + "|app");
        plan.setPointId("point");
        plan.setPostId("post");
        plan.setStandardId("standard");
        plan.setEffectiveFrom(LocalDate.now().minusDays(1));
        plan.setEffectiveTo(LocalDate.now().plusDays(1));
        plan.setRule(
                new PatrolRule(
                        Map.of(),
                        Map.of(LocalDate.now(), List.of(new PatrolRule.Window(0, 1440, 60, 1))),
                        0));
        plan.setExtension("tenantId", tenant);
        plans.insert(plan).block();
    }

    PatrolTaskEntity seedTask(int expected) {
        var task = new PatrolTaskEntity();
        task.setId("task");
        task.setName("Night patrol");
        task.setPlanId("plan");
        task.setPointId("point");
        task.setPostId("post");
        task.setBusinessDate(LocalDate.now().toString());
        task.setStartTime(System.currentTimeMillis() - 60000);
        task.setEndTime(System.currentTimeMillis() + 3600000);
        task.setRequiredCount(expected);
        task.setRosterPolicy(ExecutionPolicy.RosterPolicy.optional);
        task.setStandardSnapshot(standards.findById("standard").block().getItems());
        task.setCreateTime(System.currentTimeMillis());
        tasks.insert(task).block();
        return task;
    }

    PatrolExecutionWorkflow.Submission submission(String request) {
        return new PatrolExecutionWorkflow.Submission(
                "task", request, List.of(new PatrolExecutionEntity.Answer("lock", "normal")), null);
    }

    @AfterEach
    void close() {
        if (provider != null) provider.dispose();
        if (db != null) db.close();
    }

    @Test
    void generationIsIdempotentAndPreservesSnapshots() {
        var first =
                generator
                        .generate("plan", LocalDate.now())
                        .contextWrite(identity)
                        .collectList()
                        .block();
        assertEquals(24, first.size());
        standards
                .createUpdate()
                .set(
                        PatrolStandardEntity::getItems,
                        List.of(
                                new PatrolStandardEntity.CheckItem(
                                        "lock", "Changed", true, List.of("changed"))))
                .where(PatrolStandardEntity::getId, "standard")
                .execute()
                .block();
        plans.createUpdate()
                .set(
                        PatrolPlanEntity::getRule,
                        new PatrolRule(
                                Map.of(),
                                Map.of(
                                        LocalDate.now(),
                                        List.of(new PatrolRule.Window(30, 1440, 60, 2))),
                                0))
                .where(PatrolPlanEntity::getId, "plan")
                .execute()
                .block();
        var again =
                generator
                        .generate("plan", LocalDate.now())
                        .contextWrite(identity)
                        .collectList()
                        .block();
        assertEquals(
                first.stream().map(PatrolTaskEntity::getId).toList(),
                again.stream().map(PatrolTaskEntity::getId).toList());
        assertEquals(24, tasks.createQuery().count().block());
        assertEquals("Lock", again.get(0).getStandardSnapshot().get(0).title());
    }

    @Test
    void realTenantHolderDeniesOtherProjectsPlan() {
        seedPlan("south-plan", "south");
        StepVerifier.create(
                        generator.generate("south-plan", LocalDate.now()).contextWrite(identity))
                .expectError()
                .verify();
        assertEquals(0, tasks.createQuery().count().block());
        StepVerifier.create(generator.preview("plan", LocalDate.now()).contextWrite(identity))
                .expectNextCount(24)
                .verifyComplete();
    }

    @Test
    void replayDoesNotIncrementAndPayloadConflictIsRejected() {
        seedTask(2);
        var first = workflow.submit(submission("request-1")).contextWrite(identity).block();
        var retry = workflow.submit(submission("request-1")).contextWrite(identity).block();
        assertEquals(first.getId(), retry.getId());
        assertEquals(1, tasks.findById("task").block().getCompletedCount());
        assertEquals(1, executions.createQuery().count().block());
        var conflict =
                new PatrolExecutionWorkflow.Submission(
                        "task",
                        "request-1",
                        List.of(new PatrolExecutionEntity.Answer("lock", "abnormal")),
                        null);
        StepVerifier.create(workflow.submit(conflict).contextWrite(identity))
                .expectError()
                .verify();
    }

    @Test
    void failedRecordWriteRollsBackCounter() {
        seedTask(2);
        var broken = spy(executions);
        doReturn(Mono.error(new IllegalStateException("database write failed")))
                .when(broken)
                .insert(any(PatrolExecutionEntity.class));
        var people = mock(SaasPersonDirectory.class);
        when(people.assertMembers(List.of("alice"))).thenReturn(Mono.empty());
        var service =
                new PatrolExecutionWorkflow(
                        tasks,
                        plans,
                        points,
                        posts,
                        rosters,
                        broken,
                        people,
                        TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
        StepVerifier.create(service.submit(submission("failed")).contextWrite(identity))
                .expectError()
                .verify();
        assertEquals(0, tasks.findById("task").block().getCompletedCount());
        assertEquals(0, executions.createQuery().count().block());
    }

    // H2 的 CLOB 编码器内部使用 toIterable；线程隔离仅用于 H2 测试，不改变生产响应式链路。
    @Test
    void twoConcurrentRequestsCannotExceedExpectedCount() {
        seedTask(1);
        var outcomes =
                Flux.merge(
                                workflow.submit(submission("one"))
                                        .subscribeOn(
                                                reactor.core.scheduler.Schedulers.boundedElastic())
                                        .map(record -> true)
                                        .onErrorReturn(false),
                                workflow.submit(submission("two"))
                                        .subscribeOn(
                                                reactor.core.scheduler.Schedulers.boundedElastic())
                                        .map(record -> true)
                                        .onErrorReturn(false))
                        .contextWrite(identity)
                        .collectList()
                        .block();
        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, tasks.findById("task").block().getCompletedCount());
        assertEquals(1, executions.createQuery().count().block());
    }

    @Test
    void invalidAnswerAndRemovedMemberHaveNoSideEffects() {
        seedTask(2);
        var invalid = new PatrolExecutionWorkflow.Submission("task", "bad", List.of(), null);
        StepVerifier.create(workflow.submit(invalid).contextWrite(identity)).expectError().verify();
        posts.createUpdate()
                .set(PatrolPostEntity::getUserIds, List.of("bob"))
                .where(PatrolPostEntity::getId, "post")
                .execute()
                .block();
        StepVerifier.create(workflow.submit(submission("removed")).contextWrite(identity))
                .expectError()
                .verify();
        assertEquals(0, tasks.findById("task").block().getCompletedCount());
    }

    @Test
    void typedRuleAndInspectionSnapshotsSurvivePersistence() {
        var rule = plans.findById("plan").block().getRule();
        assertEquals(
                24, RuleEngine.expand(rule, LocalDate.now(), ZoneId.of("Asia/Shanghai")).size());
        assertEquals(1440, rule.dates().get(LocalDate.now()).get(0).endMinute());
        var item = standards.findById("standard").block().getItems().get(0);
        assertEquals(
                new PatrolStandardEntity.CheckItem(
                        "lock", "Lock", true, List.of("normal", "abnormal")),
                item);
    }

    @Test
    void emptyGenerationStaysSealedAfterRuleEdit() {
        plans.createUpdate()
                .set(PatrolPlanEntity::getRule, new PatrolRule(Map.of(), Map.of(), 0))
                .where(PatrolPlanEntity::getId, "plan")
                .execute()
                .block();
        assertTrue(
                generator
                        .generate("plan", LocalDate.now())
                        .contextWrite(identity)
                        .collectList()
                        .block()
                        .isEmpty());
        plans.createUpdate()
                .set(
                        PatrolPlanEntity::getRule,
                        new PatrolRule(
                                Map.of(),
                                Map.of(
                                        LocalDate.now(),
                                        List.of(new PatrolRule.Window(0, 1440, 60, 1))),
                                0))
                .where(PatrolPlanEntity::getId, "plan")
                .execute()
                .block();
        assertTrue(
                generator
                        .generate("plan", LocalDate.now())
                        .contextWrite(identity)
                        .collectList()
                        .block()
                        .isEmpty());
        assertEquals(1, generationRepository.createQuery().count().block());
        assertEquals(0, tasks.createQuery().count().block());
    }

    @Test
    void failedGenerationRollsBackSealAndAllowsRetry() {
        var broken = spy(tasks);
        doReturn(Mono.error(new IllegalStateException("batch insert failed")))
                .when(broken)
                .insertBatch(any());
        var service =
                new PatrolTaskGenerator(
                        plans,
                        points,
                        posts,
                        standards,
                        broken,
                        generationRepository,
                        TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
        StepVerifier.create(service.generate("plan", LocalDate.now()).contextWrite(identity))
                .expectError()
                .verify();
        assertEquals(0, generationRepository.createQuery().count().block());
        assertEquals(
                24,
                generator
                        .generate("plan", LocalDate.now())
                        .contextWrite(identity)
                        .collectList()
                        .block()
                        .size());
    }

    @Test
    void concurrentGenerationReturnsSameCommittedWindows() {
        var results =
                Mono.zip(
                                generator
                                        .generate("plan", LocalDate.now())
                                        .collectList()
                                        .subscribeOn(
                                                reactor.core.scheduler.Schedulers.boundedElastic()),
                                generator
                                        .generate("plan", LocalDate.now())
                                        .collectList()
                                        .subscribeOn(
                                                reactor.core.scheduler.Schedulers.boundedElastic()))
                        .contextWrite(identity)
                        .block(Duration.ofSeconds(10));
        assertEquals(24, results.getT1().size());
        assertEquals(
                results.getT1().stream().map(PatrolTaskEntity::getId).toList(),
                results.getT2().stream().map(PatrolTaskEntity::getId).toList());
        assertEquals(24, tasks.createQuery().count().block());
        assertEquals(1, generationRepository.createQuery().count().block());
    }

    @Test
    void rosterIsScopedToApplicationAndRequiredPolicyCannotBypassIt() {
        seedTask(3);
        var roster = new PatrolRosterEntity();
        roster.setId("other-app");
        roster.setName("Other app");
        roster.setScopeKey("north|another-app");
        roster.setPostId("post");
        roster.setBusinessDate(LocalDate.now());
        roster.setUserId("bob");
        roster.setStartTime(System.currentTimeMillis() - 60000);
        roster.setEndTime(System.currentTimeMillis() + 3600000);
        rosters.insert(roster).block();
        assertNotNull(
                workflow.submit(submission("other-app-does-not-block"))
                        .contextWrite(identity)
                        .block());
        var required = tasks.findById("task").block();
        required.setId("required-task");
        required.setStartTime(required.getStartTime() + 1);
        required.setCompletedCount(0);
        required.setRosterPolicy(ExecutionPolicy.RosterPolicy.required);
        tasks.insert(required).block();
        var requiredSubmission =
                new PatrolExecutionWorkflow.Submission(
                        "required-task",
                        "required-without-roster",
                        submission("ignored").answers(),
                        null);
        StepVerifier.create(workflow.submit(requiredSubmission).contextWrite(identity))
                .expectError()
                .verify();
        roster.setId("same-app");
        roster.setScopeKey("north|app");
        rosters.insert(roster).block();
        StepVerifier.create(
                        workflow.submit(submission("another-person-is-rostered"))
                                .contextWrite(identity))
                .expectError()
                .verify();
        rosters.createUpdate()
                .set(PatrolRosterEntity::getUserId, "alice")
                .where(PatrolRosterEntity::getId, "same-app")
                .execute()
                .block();
        assertNotNull(workflow.submit(requiredSubmission).contextWrite(identity).block());
        assertEquals(1, tasks.findById("required-task").block().getCompletedCount());
        assertEquals(1, tasks.findById("task").block().getCompletedCount());
    }

    @Test
    void reportsCountOnlyAuthorizedPlansAndUseActualExecutionFacts() {
        db.enableTenantQueries();
        seedTask(2);
        seedPlan("south-plan", "south");
        var foreign = tasks.findById("task").block();
        foreign.setId("foreign-task");
        foreign.setPlanId("south-plan");
        tasks.insert(foreign).block();
        workflow.submit(submission("accepted")).contextWrite(identity).block();
        var reports = new org.jetlinks.pro.patrol.web.PatrolReportController(tasks);
        var result =
                reports.summary(
                                new org.jetlinks.pro.patrol.web.PatrolReportController.Range(
                                        LocalDate.now(), LocalDate.now()))
                        .contextWrite(identity)
                        .block();
        assertEquals(1, result.windows());
        assertEquals(2, result.expected());
        assertEquals(1, result.completed());
        assertEquals(0, result.completeWindows());
        assertEquals(0, result.overdueMissing());
        StepVerifier.create(
                        reports.summary(
                                        new org.jetlinks.pro.patrol.web.PatrolReportController
                                                .Range(
                                                LocalDate.now(), LocalDate.now().plusDays(31)))
                                .contextWrite(identity))
                .expectError()
                .verify();
    }
}
