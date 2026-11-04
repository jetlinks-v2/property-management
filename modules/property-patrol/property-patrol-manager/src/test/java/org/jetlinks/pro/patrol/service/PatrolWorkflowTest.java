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
    PatrolAttachmentService attachments;
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
        attachments = new PatrolAttachmentService(
                db.repository(org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity.class,
                        org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity::new),
                new org.jetlinks.pro.patrol.integration.PatrolScope(),
                mock(org.jetlinks.pro.patrol.integration.PlatformPatrolFiles.class),
                TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
        var supplier =
                new PatrolAssetSupplier(categories, points, standards, posts, rosters, plans, attachments,
                        new PatrolLevelService(db.repository(org.jetlinks.pro.patrol.entity.PatrolLevelEntity.class,
                                org.jetlinks.pro.patrol.entity.PatrolLevelEntity::new)),
                        new PatrolTagService(db.repository(org.jetlinks.pro.patrol.entity.PatrolTagEntity.class,
                                org.jetlinks.pro.patrol.entity.PatrolTagEntity::new)),
                        new PatrolWarningService(db.repository(org.jetlinks.pro.patrol.entity.PatrolWarningEntity.class,
                                org.jetlinks.pro.patrol.entity.PatrolWarningEntity::new)));
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
                        attachments,
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
                        new PatrolLevelService(db.repository(org.jetlinks.pro.patrol.entity.PatrolLevelEntity.class,
                                org.jetlinks.pro.patrol.entity.PatrolLevelEntity::new)),
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
        task.setTaskGroupId(PatrolTaskGenerator.groupId("plan", LocalDate.now()));
        task.setBelongDate(LocalDate.now().toString());
        task.setPeriodNo(1);
        task.setRuleVersion("fixture-rule-v1");
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
    void groupsSeparateAssignmentsAndDatesAndKeepCrossMidnightOwnership() {
        var day = LocalDate.now();
        plans.createUpdate().set(PatrolPlanEntity::getRule,
                new PatrolRule(Map.of(), Map.of(day,
                        List.of(new PatrolRule.Window(1380, 60, 120, 1))), 0))
                .where(PatrolPlanEntity::getId, "plan").execute().block();
        var rows = generator.generate("plan", day).contextWrite(identity).collectList().block();
        assertEquals(1, rows.size());
        var task = rows.get(0);
        assertEquals(day.toString(), task.getBelongDate());
        assertEquals(day.plusDays(1), java.time.Instant.ofEpochMilli(task.getEndTime())
                .atZone(ZoneId.of("Asia/Shanghai")).toLocalDate());
        seedPlan("other-assignment", "north");
        var other = generator.generate("other-assignment", day).contextWrite(identity).collectList().block();
        org.junit.jupiter.api.Assertions.assertNotEquals(task.getTaskGroupId(), other.get(0).getTaskGroupId());
        org.junit.jupiter.api.Assertions.assertNotEquals(task.getTaskGroupId(),
                PatrolTaskGenerator.groupId("plan", day.plusDays(1)));
        assertEquals(2, generationRepository.createQuery().count().block());
        assertEquals(1, tasks.createQuery().where(PatrolTaskEntity::getTaskGroupId,
                task.getTaskGroupId()).count().block());
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
        String groupId = first.get(0).getTaskGroupId();
        assertNotNull(groupId);
        assertEquals(1, first.stream().map(PatrolTaskEntity::getTaskGroupId).distinct().count());
        assertEquals(java.util.stream.IntStream.rangeClosed(1, 24).boxed().toList(),
                first.stream().map(PatrolTaskEntity::getPeriodNo).toList());
        assertEquals(LocalDate.now().toString(), first.get(0).getBelongDate());
        var group = generationRepository.findById(groupId).block();
        assertEquals(24, group.getPeriodCount());
        assertEquals(24, group.getRequiredCount());
        assertEquals(first.get(0).getRuleVersion(), group.getRuleVersion());
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
        assertEquals(first.get(0).getRuleVersion(), again.get(0).getRuleVersion());
        assertEquals(groupId, again.get(0).getTaskGroupId());
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
        var saved = executions.findById(first.getId()).block();
        var task = tasks.findById("task").block();
        assertEquals(task.getTaskGroupId(), saved.getTaskGroupId());
        assertEquals(task.getBelongDate(), saved.getBelongDate());
        assertEquals(task.getPeriodNo(), saved.getPeriodNo());
        assertEquals(task.getRuleVersion(), saved.getRuleVersion());
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
                        attachments,
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
                        new PatrolLevelService(db.repository(org.jetlinks.pro.patrol.entity.PatrolLevelEntity.class,
                                org.jetlinks.pro.patrol.entity.PatrolLevelEntity::new)),
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
    void taskGroupsPageBeforePeriodsAndRejectUnauthorizedGroupDetails() {
        db.enableTenantQueries();
        generator.generate("plan", LocalDate.now()).contextWrite(identity).collectList().block();
        seedPlan("plan-b", "north");
        generator.generate("plan-b", LocalDate.now()).contextWrite(identity).collectList().block();
        seedPlan("south-plan", "south");
        var foreign = new PatrolGenerationEntity();
        foreign.setId("foreign-group"); foreign.setPlanId("south-plan");
        foreign.setBusinessDate(LocalDate.now().toString()); foreign.setBelongDate(LocalDate.now().toString());
        generationRepository.insert(foreign).block();
        var controller = new org.jetlinks.pro.patrol.web.PatrolTaskGroupController(new PatrolTaskGroupService(generationRepository));
        var query = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery().orderByAsc("id").getParam();
        query.setPageSize(1); query.setPageIndex(0);
        var page = controller.queryPager(query).contextWrite(identity).block();
        assertEquals(2, page.getTotal()); assertEquals(1, page.getData().size());
        var second = query.clone(); second.setPageIndex(1);
        var secondPage = controller.queryPager(second).contextWrite(identity).block();
        org.junit.jupiter.api.Assertions.assertNotEquals(page.getData().get(0).getId(), secondPage.getData().get(0).getId());
        StepVerifier.create(controller.getById("foreign-group").contextWrite(identity)).expectError().verify();
        var periods = new org.jetlinks.pro.patrol.web.PatrolRecordPeriodController(tasks);
        assertEquals(24, periods.query(org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
                .is("taskGroupId", page.getData().get(0).getId()).getParam()).contextWrite(identity).count().block());
    }

    @Test
    void calendarKeepsOriginalEndDateAndRestRowsAndAssetIsolation() {
        db.enableTenantQueries();
        var task = seedTask(3);
        var day = LocalDate.now().minusDays(1);
        long start = day.atTime(23, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        tasks.createUpdate().set(PatrolTaskEntity::getStartTime, start)
                .set(PatrolTaskEntity::getEndTime, start + 7200000)
                .set(PatrolTaskEntity::getCompletedCount, 1)
                .where(PatrolTaskEntity::getId, task.getId()).execute().block();
        var rest = new PatrolRosterEntity();
        rest.setId("rest"); rest.setName("休息"); rest.setRest(true); rest.setPostId("post"); rest.setUserId("alice");
        rest.setBusinessDate(day.plusDays(1)); rest.setScopeKey("north|app");
        rest.setStartTime(start + 3600000); rest.setEndTime(start + 7200000); rest.setExtension("tenantId", "north");
        rosters.insert(rest).block();
        var foreign = new PatrolRosterEntity();
        foreign.setId("foreign-roster"); foreign.setName("South"); foreign.setPostId("post"); foreign.setUserId("bob");
        foreign.setBusinessDate(day.plusDays(1)); foreign.setScopeKey("south|app");
        foreign.setStartTime(start + 3600000); foreign.setEndTime(start + 7200000); foreign.setExtension("tenantId", "south");
        rosters.insert(foreign).block();
        var calendar = new PatrolCalendarService(rosters, tasks);
        assertEquals(0, calendar.day(day).contextWrite(identity).block().tasks().size());
        var detail = calendar.day(day.plusDays(1)).contextWrite(identity).block();
        assertEquals(1, detail.tasks().size()); assertEquals(1, detail.staff().size());
        assertEquals(0, detail.staffCount()); assertEquals(0, detail.shiftCount());
        assertEquals(3, detail.expectedCount()); assertEquals(1, detail.completedCount());
        var month = calendar.month(java.time.YearMonth.from(day.plusDays(1))).contextWrite(identity).block();
        assertEquals(java.time.YearMonth.from(day.plusDays(1)).lengthOfMonth(), month.days().size());
        assertEquals(0, month.summary().rosterCount());
    }

    @Test
    void originalStatisticsAggregateAssignedRoleAndPeriodCompletionRatherThanCheckResults() {
        db.enableTenantQueries();
        var first = seedTask(2);
        tasks.createUpdate().set(PatrolTaskEntity::getCompletedCount, 1)
                .where(PatrolTaskEntity::getId, first.getId()).execute().block();
        var second = tasks.findById("task").block(); second.setId("period2"); second.setPeriodNo(2);
        second.setStartTime(second.getStartTime() + 1); second.setCompletedCount(2); tasks.insert(second).block();
        seedPlan("south-plan", "south");
        var foreign = tasks.findById("task").block(); foreign.setId("foreign"); foreign.setPlanId("south-plan");
        foreign.setTaskGroupId("foreign"); foreign.setCompletedCount(2); tasks.insert(foreign).block();
        var service = new PatrolStatisticsService(tasks);
        var range = new PatrolStatisticsService.Range(LocalDate.now(), LocalDate.now(), List.of());
        var rows = service.query(range, true).contextWrite(identity).block();
        assertEquals(1, rows.size()); var row = rows.get(0);
        assertEquals("post", row.id()); assertEquals(4, row.expected()); assertEquals(3, row.completed());
        assertEquals(75, row.coverage()); assertEquals(50, row.qualification());
        assertEquals(2, row.taskCount()); assertEquals(1, row.completedTasks());
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
        required.setPeriodNo(2);
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
        foreign.setTaskGroupId(PatrolTaskGenerator.groupId("south-plan", LocalDate.now()));
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

    @Test
    void numericJudgmentAndSnapshotSurviveDatabaseAndLaterStandardChanges() {
        standards.createUpdate().set(PatrolStandardEntity::getItems,
                List.of(PatrolInspectionTest.number(false)))
                .where(PatrolStandardEntity::getId, "standard").execute().block();
        seedTask(2);
        var accepted = workflow.submit(new PatrolExecutionWorkflow.Submission("task", "pressure",
                List.of(new PatrolExecutionEntity.Answer("pressure", "0.5")), null)).contextWrite(identity).block();
        standards.createUpdate().set(PatrolStandardEntity::getItems, List.of(
                new PatrolStandardEntity.CheckItem("lock", "Changed", true, List.of("normal"))))
                .where(PatrolStandardEntity::getId, "standard").execute().block();
        var saved = executions.findById(accepted.getId()).block();
        assertEquals(PatrolExecutionEntity.CheckResult.fail, saved.getResult());
        assertEquals(PatrolExecutionEntity.CheckResult.fail, saved.getItemResults().get(0).result());
        assertEquals(new java.math.BigDecimal("0.4"), saved.getStandardSnapshot().get(0).upperLimit());
        assertEquals(1, tasks.findById("task").block().getCompletedCount());
    }

    @Test
    void requiredImageChecksRealAssetsPurposeScopeAndUploaderBeforeIncrement() {
        standards.createUpdate().set(PatrolStandardEntity::getItems,
                List.of(PatrolInspectionTest.number(true)))
                .where(PatrolStandardEntity::getId, "standard").execute().block();
        seedTask(2);
        var missing = new PatrolExecutionWorkflow.Submission("task", "missing-image",
                List.of(new PatrolExecutionEntity.Answer("pressure", "0.3")), null);
        StepVerifier.create(workflow.submit(missing).contextWrite(identity)).expectError().verify();
        var photo = new org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity();
        photo.setId("photo"); photo.setName("Photo"); photo.setScopeKey("south|app");
        photo.setCreatorId("alice"); photo.setExtension("tenantId", "south");
        photo.setPurpose(org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity.Purpose.evidence);
        photo.setPlatformFileId("platform-file"); photo.setInternalUrl("internal");
        photo.setMediaType("image/png"); photo.setLength(100);
        attachments.insert(photo).block();
        var submission = new PatrolExecutionWorkflow.Submission("task", "image-result",
                List.of(new PatrolExecutionEntity.Answer("pressure", "0.3", "photo")), null);
        StepVerifier.create(workflow.submit(submission).contextWrite(identity)).expectError().verify();
        photo.setId("other-app-photo"); photo.setScopeKey("north|another-app"); photo.setExtension("tenantId", "north");
        attachments.insert(photo).block();
        var otherApp = new PatrolExecutionWorkflow.Submission("task", "other-app-result",
                List.of(new PatrolExecutionEntity.Answer("pressure", "0.3", "other-app-photo")), null);
        StepVerifier.create(workflow.submit(otherApp).contextWrite(identity)).expectError().verify();
        // scopeKey 不可通过业务编辑移动；测试用新 ID 分别建立合法范围的不同用途资产。
        photo.setId("local-photo"); photo.setScopeKey("north|app"); photo.setCreatorId("bob");
        attachments.insert(photo).block();
        var local = new PatrolExecutionWorkflow.Submission("task", "local-result",
                List.of(new PatrolExecutionEntity.Answer("pressure", "0.3", "local-photo")), null);
        StepVerifier.create(workflow.submit(local).contextWrite(identity)).expectError().verify();
        photo.setId("example-photo"); photo.setCreatorId("alice");
        photo.setPurpose(org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity.Purpose.example);
        attachments.insert(photo).block();
        var example = new PatrolExecutionWorkflow.Submission("task", "example-result",
                List.of(new PatrolExecutionEntity.Answer("pressure", "0.3", "example-photo")), null);
        StepVerifier.create(workflow.submit(example).contextWrite(identity)).expectError().verify();
        assertEquals(0, tasks.findById("task").block().getCompletedCount());
        assertEquals(0, executions.createQuery().count().block());
        photo.setId("valid-photo"); photo.setPurpose(org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity.Purpose.evidence);
        attachments.insert(photo).block();
        var valid = new PatrolExecutionWorkflow.Submission("task", "valid-result",
                List.of(new PatrolExecutionEntity.Answer("pressure", "0.3", "valid-photo")), null);
        var saved = workflow.submit(valid).contextWrite(identity).block();
        assertEquals(PatrolExecutionEntity.CheckResult.pass, saved.getResult());
        assertEquals("valid-photo", executions.findById(saved.getId()).block().getAnswers().get(0).attachmentId());
        workflow.submit(valid).contextWrite(identity).block();
        assertEquals(1, tasks.findById("task").block().getCompletedCount());
    }
    @Test
    void dailyGenerationRunsWithoutUserAndUsesExistingSeals() {
        var daily = new PatrolDailyGeneration(plans, generator);
        daily.generate(LocalDate.now()).block();
        daily.generate(LocalDate.now()).block();
        assertEquals(24, tasks.createQuery().count().block());
        assertEquals(1, generationRepository.createQuery().count().block());
        daily.generate(LocalDate.now().plusDays(2)).block();
        assertEquals(1, generationRepository.createQuery().count().block());
    }

    @Test
    void scheduledGenerationRestoresTenantFromThePersistedPlan() {
        var scopedPlans = spy(plans);
        doReturn(Mono.defer(() -> org.jetlinks.pro.tenant.holder.TenantHolder.currentId()
                .doOnNext(id -> assertEquals("north", id)).then(plans.findById("plan"))))
                .when(scopedPlans).findById("plan");
        var scheduled = new PatrolTaskGenerator(scopedPlans, points, posts, standards, tasks,
                new PatrolLevelService(db.repository(org.jetlinks.pro.patrol.entity.PatrolLevelEntity.class,
                        org.jetlinks.pro.patrol.entity.PatrolLevelEntity::new)), generationRepository,
                TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
        scheduled.generateScheduled(plans.findById("plan").block(), LocalDate.now()).block();
        assertEquals(24, tasks.createQuery().count().block());
    }

    @Test
    void scheduledPlatformApplicationDoesNotInventTenantContext() {
        var plan = plans.findById("plan").block();
        plan.setScopeKey("|app");
        plans.updateById("plan", plan).block();
        var scopedPlans = spy(plans);
        doReturn(Mono.deferContextual(context -> {
            assertEquals(null, org.jetlinks.pro.tenant.holder.TenantHolder.readTenantId(context));
            return plans.findById("plan");
        })).when(scopedPlans).findById("plan");
        var scheduled = new PatrolTaskGenerator(scopedPlans, points, posts, standards, tasks,
                new PatrolLevelService(db.repository(org.jetlinks.pro.patrol.entity.PatrolLevelEntity.class,
                        org.jetlinks.pro.patrol.entity.PatrolLevelEntity::new)), generationRepository,
                TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
        scheduled.generateScheduled(plan, LocalDate.now()).block();
        assertEquals(24, tasks.createQuery().count().block());
    }

    PatrolRosterImportService rosterImporter(PatrolRosterService target) {
        var scope = mock(org.jetlinks.pro.patrol.integration.PatrolScope.class);
        when(scope.current()).thenReturn(Mono.just(new org.jetlinks.pro.patrol.integration.PatrolScope.Current("north", "app")));
        var people = mock(SaasPersonDirectory.class);
        when(people.assertMembers(List.of("alice"))).thenReturn(Mono.empty());
        when(people.query(any())).thenReturn(Mono.just(org.hswebframework.web.api.crud.entity.PagerResult.of(1,
                List.of(new SaasPersonDirectory.Person("alice", "Alice")))));
        var validator = new PatrolConfigurationValidator(null, points, standards, posts, people, scope, attachments, null, null);
        return new PatrolRosterImportService(target, posts, validator, people, scope,
                db.repository(org.jetlinks.pro.patrol.entity.PatrolRosterMonthEntity.class,
                        org.jetlinks.pro.patrol.entity.PatrolRosterMonthEntity::new),
                TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
    }

    @Test
    void monthlyImportReplacesAtomicallyRetainsAuditAndRejectsUnauthorizedOldRoster() throws Exception {
        var service = rosterImporter(rosters);
        var month = java.time.YearMonth.of(2026, 11);
        var file = PatrolRosterWorkbookTest.workbook("alice", "夜班");
        assertEquals(1, service.importMonth(month, file).contextWrite(identity).block());
        var old = rosters.createQuery().fetch().blockFirst();
        // 测试模拟平台实体事件给资产添加的租户归属，再使用真实 TenantAssetsHolder 验证 save。
        old.setExtension("tenantId", "north"); rosters.updateById(old.getId(), old).block();
        assertEquals(1, service.importMonth(month, PatrolRosterWorkbookTest.workbook("alice", "休息")).contextWrite(identity).block());
        assertEquals(2, rosters.createQuery().count().block());
        assertEquals(1, rosters.createQuery().where(PatrolRosterEntity::isEnabled, true).count().block());
        assertTrue(!rosters.findById(old.getId()).block().isEnabled());
        var current = rosters.createQuery().where(PatrolRosterEntity::isEnabled, true).fetch().blockFirst();
        assertEquals("Alice", current.getUserName()); assertTrue(current.isRest());
        current.setExtension("tenantId", "south"); rosters.updateById(current.getId(), current).block();
        StepVerifier.create(service.importMonth(month, file).contextWrite(identity)).expectError().verify();
        assertTrue(rosters.findById(current.getId()).block().isEnabled());
        assertEquals(2, rosters.createQuery().count().block());
    }

    @Test
    void importFailureRollsBackDeactivationAndWrongMembersNeverWrite() throws Exception {
        var service = rosterImporter(rosters);
        var month = java.time.YearMonth.of(2026, 11);
        var file = PatrolRosterWorkbookTest.workbook("alice", "夜班");
        service.importMonth(month, file).contextWrite(identity).block();
        var old = rosters.createQuery().fetch().blockFirst(); old.setExtension("tenantId", "north");
        rosters.updateById(old.getId(), old).block();
        var broken = spy(rosters);
        doReturn(Mono.error(new IllegalStateException("write failed"))).when(broken).insertBatch(any());
        var failing = rosterImporter(broken);
        StepVerifier.create(failing.importMonth(month, file).contextWrite(identity)).expectError().verify();
        assertTrue(rosters.findById(old.getId()).block().isEnabled());
        assertEquals(1, rosters.createQuery().count().block());
        StepVerifier.create(service.importMonth(month, PatrolRosterWorkbookTest.workbook("unassigned", "夜班"))
                .contextWrite(identity)).expectError().verify();
        assertEquals(1, rosters.createQuery().count().block());
    }

}
