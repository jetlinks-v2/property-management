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
import org.jetlinks.pro.patrol.entity.PatrolRole;
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
    TestApplicationRoles roles;
    PatrolPointService points;
    PatrolCategoryService categories;
    PatrolStandardService standards;
    PatrolRosterService rosters;
    PatrolAttachmentService attachments;
    PatrolAssetSupplier supplier;
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
                        db.repository(PatrolExecutionEntity.class, PatrolExecutionEntity::new),
                        new org.hswebframework.web.crud.query.DefaultQueryHelper(db.operator()));
        plans = new PatrolPlanService(db.repository(PatrolPlanEntity.class, PatrolPlanEntity::new));
        roles = new TestApplicationRoles();
        points =
                new PatrolPointService(
                        db.repository(PatrolPointEntity.class, PatrolPointEntity::new),
                        db.repository(org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity.class,
                                org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity::new));
        standards =
                new PatrolStandardService(
                        db.repository(PatrolStandardEntity.class, PatrolStandardEntity::new));
        rosters =
                new PatrolRosterService(
                        db.repository(PatrolRosterEntity.class, PatrolRosterEntity::new));
        categories =
                new PatrolCategoryService(
                        db.repository(PatrolCategoryEntity.class, PatrolCategoryEntity::new));
        attachments = new PatrolAttachmentService(
                db.repository(org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity.class,
                        org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity::new),
                new org.jetlinks.pro.patrol.integration.PatrolScope(),
                mock(org.jetlinks.pro.patrol.integration.PlatformPatrolFiles.class),
                TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
        supplier =
                new PatrolAssetSupplier(categories, points, standards, rosters, plans, attachments,
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
        identity = Context.of(Authentication.class, auth, org.jetlinks.pro.patrol.integration.PatrolProjectContext.class,
                org.jetlinks.pro.patrol.integration.ProjectTestContexts.project("app"));
        var people = mock(SaasPersonDirectory.class);
        when(people.assertMembers(List.of("alice"))).thenReturn(Mono.empty());
        workflow =
                new PatrolExecutionWorkflow(
                        tasks,
                        plans,
                        points,
                        roles,
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
                        roles,
                        standards,
                        tasks,
                        new PatrolLevelService(db.repository(org.jetlinks.pro.patrol.entity.PatrolLevelEntity.class,
                                org.jetlinks.pro.patrol.entity.PatrolLevelEntity::new)),
                        categories,
                        generationRepository,
                        TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
        var category = new PatrolCategoryEntity();
        category.setId("category"); category.setName("Building"); category.setScopeKey("app"); category.setProjectId("project"); category.setProjectName("Project");
        category.setExtension("tenantId", "north"); categories.insert(category).block();
        var point = new PatrolPointEntity();
        point.setId("point");
        point.setName("Gate");
        point.setScopeKey("app"); point.setProjectId("project"); point.setProjectName("Project");
        point.setCode("P001");
        point.setCategoryId("category");
        point.setExtension("tenantId", "north");
        points.insert(point).block();
        var post = new PatrolRole();
        post.setId("post");
        post.setName("Security");
        post.setScopeKey("app"); post.setProjectId("project"); post.setProjectName("Project");
        post.setUserIds(List.of("alice"));
        post.setExtension("tenantId", "north");
        roles.put(post);
        var standard = new PatrolStandardEntity();
        standard.setId("standard");
        standard.setName("Safety");
        standard.setScopeKey("app"); standard.setProjectId("project"); standard.setProjectName("Project");
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
        plan.setScopeKey("app"); plan.setProjectId("project"); plan.setProjectName("Project");
        plan.setPointId("point");
        plan.setRoleId("post");
        plan.setRoleName("巡更员");
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
        return seedTask(expected, System.currentTimeMillis() - 60000, System.currentTimeMillis() + 3600000);
    }

    PatrolTaskEntity seedTask(int expected, long start, long end) {
        return seedTask(expected, start, end, LocalDate.now());
    }

    PatrolTaskEntity seedTask(int expected, long start, long end, LocalDate belongDate) {
        var task = new PatrolTaskEntity();
        task.setId("task");
        task.setName("Night patrol");
        task.setPlanId("plan");
        task.setPointId("point");
        task.setRoleId("post");
        task.setTaskGroupId(PatrolTaskGenerator.groupId("plan", belongDate));
        task.setBelongDate(belongDate.toString());
        task.setPeriodNo(1);
        task.setRuleVersion("fixture-rule-v1");
        task.setBusinessDate(belongDate.toString());
        task.setStartTime(start);
        task.setEndTime(end);
        task.setRequiredCount(expected);
        task.setRosterPolicy(ExecutionPolicy.RosterPolicy.optional);
        task.setStandardSnapshot(standards.findById("standard").block().getItems());
        task.setCreateTime(System.currentTimeMillis());
        tasks.insert(task).block();
        return task;
    }

    PatrolExecutionWorkflow.Submission submission(String request) {
        return new PatrolExecutionWorkflow.Submission(
                "task", request, List.of(new PatrolExecutionEntity.Answer("lock", "normal")), null, null, null);
    }

    @AfterEach
    void close() {
        if (provider != null) provider.dispose();
        if (db != null) db.close();
    }

    private PatrolPointCategoryService pointCategories(
            ReactiveRepository<org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity, String> repository) {
        var scope = mock(org.jetlinks.pro.patrol.integration.PatrolScope.class);
        when(scope.current()).thenReturn(Mono.just(new org.jetlinks.pro.patrol.integration.PatrolScope.Current("app")));
        return new PatrolPointCategoryService(repository, points, categories, plans, scope,
                TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
    }

    private void secondCategory() {
        var category = new PatrolCategoryEntity();
        category.setId("category2"); category.setName("Fire safety"); category.setScopeKey("app"); category.setProjectId("project"); category.setProjectName("Project");
        category.setExtension("tenantId", "north"); categories.insert(category).block();
    }

    @Test
    void cancellingOneCategoryKeepsOtherCategoryPlanPhysicalPointAndAllHistoricalFacts() {
        var relations = db.repository(org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity::new);
        var service = pointCategories(relations);
        secondCategory();
        assertEquals(List.of("category"), service.categoryIds(points.findById("point").block()).block());
        seedTask(2);
        workflow.submit(submission("before-cancel")).contextWrite(identity).block();
        var historical = tasks.findById("task").block();
        var execution = executions.createQuery().fetchOne().block();
        service.link(List.of("point"), "category2").contextWrite(identity).block();
        seedPlan("category2-plan", "north");
        plans.createUpdate().set(PatrolPlanEntity::getCategoryId, "category2")
                .where(PatrolPlanEntity::getId, "category2-plan").execute().block();

        service.cancel(List.of("point"), "category").contextWrite(identity).block();
        service.cancel(List.of("point"), "category").contextWrite(identity).block();
        assertEquals(List.of("category2"), service.categoryIds(points.findById("point").block()).block());
        assertTrue(points.findById("point").block().isEnabled());
        var originalPlan = plans.findById("plan").block();
        assertEquals("category", originalPlan.getCategoryId());
        org.junit.jupiter.api.Assertions.assertFalse(originalPlan.isEnabled());
        assertTrue(plans.findById("category2-plan").block().isEnabled());
        assertEquals(2, relations.createQuery().count().block());
        assertEquals(historical.getCompletedCount(), tasks.findById("task").block().getCompletedCount());
        assertEquals(historical.getLastExecutionTime(), tasks.findById("task").block().getLastExecutionTime());
        assertEquals(execution.getId(), executions.createQuery().fetchOne().block().getId());
        assertEquals(1, executions.createQuery().count().block());
        StepVerifier.create(workflow.submit(submission("after-cancel")).contextWrite(identity))
                .expectError(org.hswebframework.web.exception.BusinessException.class).verify();
    }

    @Test
    void lastCategoryCancellationCannotResurrectLegacyFallbackAndRebindingDoesNotEnableOldPlans() {
        var relations = db.repository(org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity::new);
        var service = pointCategories(relations);
        service.cancel(List.of("point"), "category").contextWrite(identity).block();
        var physical = points.findById("point").block();
        assertTrue(physical.isEnabled());
        assertEquals("category", physical.getCategoryId());
        assertEquals(List.of(), service.categoryIds(physical).block());
        var tombstone = relations.createQuery().fetchOne().block();
        service.link(List.of("point"), "category").contextWrite(identity).block();
        assertEquals(List.of("category"), service.categoryIds(physical).block());
        assertEquals(tombstone.getId(), relations.createQuery().fetchOne().block().getId());
        assertEquals(tombstone.getCreateTime(), relations.createQuery().fetchOne().block().getCreateTime());
        org.junit.jupiter.api.Assertions.assertFalse(plans.findById("plan").block().isEnabled());
    }

    @Test
    void categoryChangesRejectForeignApplicationAndMixedUnauthorizedPointsWithoutWrites() {
        var relations = db.repository(org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity::new);
        var service = pointCategories(relations);
        var foreignCategory = new PatrolCategoryEntity(); foreignCategory.setId("other-app-category");
        foreignCategory.setName("Other application"); foreignCategory.setScopeKey("other-app"); foreignCategory.setProjectId("project"); foreignCategory.setProjectName("Project");
        foreignCategory.setExtension("tenantId", "north"); categories.insert(foreignCategory).block();
        StepVerifier.create(service.link(List.of("point"), foreignCategory.getId()).contextWrite(identity))
                .expectError(org.hswebframework.web.exception.BusinessException.class).verify();
        var foreign = new PatrolPointEntity(); foreign.setId("foreign"); foreign.setName("Other tenant point");
        foreign.setCode("FOREIGN"); foreign.setCategoryId("category"); foreign.setScopeKey("other-app"); foreign.setProjectId("project"); foreign.setProjectName("Project");
        foreign.setExtension("tenantId", "south"); points.insert(foreign).block();
        StepVerifier.create(service.cancel(List.of("point", "foreign"), "category").contextWrite(identity))
                .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
        assertEquals(0, relations.createQuery().count().block());
        assertTrue(plans.findById("plan").block().isEnabled());
        org.junit.jupiter.api.Assertions.assertNull(plans.findById("plan").block().getCategoryId());
    }

    @Test
    void categoryBindingFailureRollsBackPublishedMembershipAndPlanMigration() {
        var relations = db.repository(org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity::new);
        var failing = spy(relations);
        doReturn(Mono.error(new IllegalStateException("injected relation insert failure"))).when(failing)
                .insert(org.mockito.ArgumentMatchers.argThat(
                        (org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity row) -> "category2".equals(row.getCategoryId())));
        secondCategory();
        StepVerifier.create(pointCategories(failing).link(List.of("point"), "category2").contextWrite(identity))
                .expectError(IllegalStateException.class).verify();
        assertEquals(0, relations.createQuery().count().block());
        assertTrue(plans.findById("plan").block().isEnabled());
        org.junit.jupiter.api.Assertions.assertNull(plans.findById("plan").block().getCategoryId());
    }

    @Test
    void relationUpdateFailureRollsBackPlanStopAndLegacyMaterialization() {
        var relations = db.repository(org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity::new);
        var failing = spy(relations);
        var update = spy(relations.createUpdate());
        doReturn(Mono.error(new IllegalStateException("injected relation update failure"))).when(update).execute();
        doReturn(update).when(failing).createUpdate();
        StepVerifier.create(pointCategories(failing).cancel(List.of("point"), "category").contextWrite(identity))
                .expectError(IllegalStateException.class).verify();
        assertEquals(0, relations.createQuery().count().block());
        assertTrue(plans.findById("plan").block().isEnabled());
        org.junit.jupiter.api.Assertions.assertNull(plans.findById("plan").block().getCategoryId());
    }

    @Test
    void categoryBatchSupportsOneHundredPointsAndRejectsEmptyOrOversizedOperations() {
        var relations = db.repository(org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity::new);
        var service = pointCategories(relations);
        secondCategory();
        var selected = new java.util.ArrayList<PatrolPointEntity>();
        for (int n = 0; n < 100; n++) {
            var point = new PatrolPointEntity(); point.setId("category-batch-" + n);
            point.setName("Batch point " + n); point.setCode("CB" + n); point.setCategoryId("category");
            point.setScopeKey("app"); point.setProjectId("project"); point.setProjectName("Project"); point.setExtension("tenantId", "north"); selected.add(point);
        }
        points.insertBatch(Mono.just(selected)).block();
        var ids = selected.stream().map(PatrolPointEntity::getId).toList();
        long started = System.nanoTime();
        service.link(ids, "category2").contextWrite(identity).block(Duration.ofSeconds(30));
        service.cancel(ids, "category").contextWrite(identity).block(Duration.ofSeconds(30));
        System.out.println("100-point category link+cancel elapsed ms=" + (System.nanoTime() - started) / 1_000_000);
        assertEquals(200, relations.createQuery().count().block());
        assertEquals(100, relations.createQuery().where("enabled", true).and("categoryId", "category2").count().block());
        assertEquals(100, points.createQuery().in(PatrolPointEntity::getId, ids).and("enabled", true).count().block());
        StepVerifier.create(service.cancel(List.of(), "category").contextWrite(identity))
                .expectError(org.hswebframework.web.exception.BusinessException.class).verify();
        var excessive = new java.util.ArrayList<>(ids); excessive.add("point");
        StepVerifier.create(service.cancel(excessive, "category2").contextWrite(identity))
                .expectError(org.hswebframework.web.exception.BusinessException.class).verify();
        assertEquals(100, relations.createQuery().where("enabled", true).count().block());
    }

    @Test
    void categoryFiltersUseAllCurrentRelationsAndPreserveExclusionsPagingAndLegacyRows() {
        var relations = db.repository(org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity::new);
        var service = pointCategories(relations);
        secondCategory();
        var other = new PatrolPointEntity(); other.setId("point2"); other.setName("Pump"); other.setCode("P002");
        other.setScopeKey("app"); other.setProjectId("project"); other.setProjectName("Project"); other.setCategoryId("category"); other.setExtension("tenantId", "north");
        points.insert(other).block();
        var first = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery().is("categoryId", "category").getParam();
        assertEquals(2, points.count(first).block());
        service.link(List.of("point"), "category2").contextWrite(identity).block();
        assertEquals(List.of("category", "category2"), points.findById("point").block().getCategoryIds());
        var excluded = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
                .notIn("categoryId", List.of("category")).getParam();
        assertEquals(0, points.count(excluded).block());
        var filter = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
                .in("categoryId", List.of("category2")).or().is("name", "Missing name").orderByAsc("id").getParam();
        filter.setPageSize(1);
        var page = points.queryPager(filter).block();
        assertEquals(1, page.getTotal());
        assertEquals("point", page.getData().get(0).getId());
        assertEquals(List.of("category", "category2"), page.getData().get(0).getCategoryIds());
        assertEquals("categoryId", filter.getTerms().get(0).getColumn());
        assertEquals("in", filter.getTerms().get(0).getTermType());
        service.cancel(List.of("point"), "category").contextWrite(identity).block();
        assertEquals(List.of("point2"), points.query(first).map(PatrolPointEntity::getId).collectList().block());
        assertEquals(List.of("point"), points.query(excluded).map(PatrolPointEntity::getId).collectList().block());
        service.cancel(List.of("point"), "category2").contextWrite(identity).block();
        assertEquals(List.of(), points.findById("point").block().getCategoryIds());
        assertEquals(0, points.count(filter).block());
        assertEquals(2, points.createQuery().count().block());
    }

    @Test
    void categorySpecificAssignmentsGenerateIndependentSnapshotsAndCannotBeImplicitlyMerged() {
        var relations = db.repository(org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity::new);
        var service = pointCategories(relations);
        secondCategory();
        service.link(List.of("point"), "category2").contextWrite(identity).block();
        var assignments = pointAssignments();
        StepVerifier.create(assignments.assign(List.of("point"), List.of("post")).contextWrite(identity))
                .expectError(org.hswebframework.web.exception.BusinessException.class).verify();
        assignments.assign(List.of("point"), List.of("post"), "category2").contextWrite(identity).block();
        ownNewAssignments();
        var primary = assignments.detail(List.of("point"), "category").contextWrite(identity).block().get(0);
        var secondary = assignments.detail(List.of("point"), "category2").contextWrite(identity).block().get(0);
        assertEquals("plan", primary.assignments().get(0).getId());
        assertEquals("category2", secondary.categoryId());
        assertEquals(1, secondary.assignments().size());
        org.junit.jupiter.api.Assertions.assertNotEquals("plan", secondary.assignments().get(0).getId());
        var level = new org.jetlinks.pro.patrol.entity.PatrolLevelEntity();
        level.setId("level2"); level.setName("Fire schedule"); level.setScopeKey("app"); level.setProjectId("project"); level.setProjectName("Project");
        level.setExtension("tenantId", "north"); level.setRule(new PatrolRule(Map.of(),
                Map.of(LocalDate.now(), List.of(new PatrolRule.Window(480, 600, 60, 1))), 0));
        new PatrolLevelService(db.repository(org.jetlinks.pro.patrol.entity.PatrolLevelEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolLevelEntity::new)).insert(level).block();
        assignments.configure(List.of("point"), List.of(new PatrolPointAssignmentService.Rule("post", "level2", "standard")), "category2")
                .contextWrite(identity).block();
        var first = generator.generate("plan", LocalDate.now()).contextWrite(identity).collectList().block();
        String secondaryId = secondary.assignments().get(0).getId();
        var second = generator.generate(secondaryId, LocalDate.now()).contextWrite(identity).collectList().block();
        assertEquals("category", first.get(0).getCategoryId());
        assertEquals("category2", second.get(0).getCategoryId());
        assertEquals("Fire safety", second.get(0).getCategoryName());
        assertEquals(2, second.get(0).getRequiredCount());
        assertEquals(24, first.get(0).getRequiredCount());
        org.junit.jupiter.api.Assertions.assertNotEquals(first.get(0).getTaskGroupId(), second.get(0).getTaskGroupId());
        service.cancel(List.of("point"), "category").contextWrite(identity).block();
        assertTrue(plans.findById(secondaryId).block().isEnabled());
        assertEquals("category2", generator.preview(secondaryId, LocalDate.now()).contextWrite(identity).blockFirst().getCategoryId());
        assertEquals(2, tasks.createQuery().count().block());
    }

    private PatrolConfigurationValidator categoryValidator() {
        var scope = mock(org.jetlinks.pro.patrol.integration.PatrolScope.class);
        when(scope.current()).thenReturn(Mono.just(new org.jetlinks.pro.patrol.integration.PatrolScope.Current("app")));
        return new PatrolConfigurationValidator(categories, points, standards, roles,
                mock(SaasPersonDirectory.class), scope, attachments,
                new PatrolLevelService(db.repository(org.jetlinks.pro.patrol.entity.PatrolLevelEntity.class,
                        org.jetlinks.pro.patrol.entity.PatrolLevelEntity::new)),
                new PatrolTagService(db.repository(org.jetlinks.pro.patrol.entity.PatrolTagEntity.class,
                        org.jetlinks.pro.patrol.entity.PatrolTagEntity::new)));
    }

    @Test
    void pointCodeIsOptionalOnCreateAndStableWhenBlankOnEdit() {
        db.enableTenantQueries();
        var controller = new org.jetlinks.pro.patrol.web.PatrolPointController(points, categoryValidator());
        var codes = new java.util.HashSet<String>();
        for (String code : java.util.Arrays.asList(null, "", "   ", "  CUSTOM-001  ")) {
            var input = new PatrolPointEntity(); input.setName("Optional code");
            input.setProjectId("project"); input.setCategoryId("category"); input.setCode(code);
            var created = controller.create(input).contextWrite(identity).block();
            assertNotNull(created);
            String expected = code != null && !code.isBlank() ? "CUSTOM-001"
                    : "PT" + created.getId().replace("-", "").toUpperCase(java.util.Locale.ROOT);
            assertEquals(expected, created.getCode());
            assertEquals(expected, points.findById(created.getId()).block().getCode());
            assertTrue(codes.add(created.getCode()));
        }
        // 使用测试中已绑定当前租户的既有点位，覆盖编辑保留编号的真实资产入口。
        for (String blank : java.util.Arrays.asList(null, "", " ")) {
            var edit = new PatrolPointEntity(); edit.setName("Renamed"); edit.setCategoryId("category"); edit.setCode(blank);
            var saved = controller.update("point", edit).contextWrite(identity).block();
            assertEquals("P001", saved.getCode());
            assertEquals("P001", points.findById("point").block().getCode());
        }
    }

    @Test
    void pointMetadataEditPreservesMembershipAndCannotChangeCategoryThroughGenericSave() throws Exception {
        var relations = db.repository(org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity::new);
        secondCategory();
        var service = pointCategories(relations);
        service.link(List.of("point"), "category2").contextWrite(identity).block();
        service.cancel(List.of("point"), "category").contextWrite(identity).block();
        categories.createUpdate().set(PatrolCategoryEntity::isEnabled, false)
                .where(PatrolCategoryEntity::getId, "category").execute().block();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var input = mapper.readValue("{\"name\":\"Renamed\",\"code\":\"P001\",\"categoryId\":\"category\",\"categoryIds\":[\"forged\"]}", PatrolPointEntity.class);
        var controller = new org.jetlinks.pro.patrol.web.PatrolPointController(points, categoryValidator());
        var saved = controller.update("point", input).contextWrite(identity).block();
        assertEquals(List.of("category2"), saved.getCategoryIds());
        assertEquals("category", saved.getCategoryId());
        assertEquals("Renamed", points.findById("point").block().getName());
        assertEquals(List.of("category2"), points.findById("point").block().getCategoryIds());
        input.setCategoryId("category2");
        StepVerifier.create(controller.update("point", input).contextWrite(identity))
                .expectErrorMessage("error.patrol.point.category_managed").verify();
        assertEquals("category", points.findById("point").block().getCategoryId());
    }

    @Test
    void assignmentReadBeforeConcurrentCancellationCannotRestoreCancelledCategoryPlans() {
        var selected = reactor.core.publisher.Sinks.<Boolean>one();
        var resume = reactor.core.publisher.Sinks.<Boolean>one();
        var staleReader = spy(points);
        doReturn(points.findById(List.of("point")).collectList().flatMapMany(snapshot -> {
            selected.tryEmitValue(true);
            return resume.asMono().thenMany(Flux.fromIterable(snapshot));
        })).when(staleReader).findById(List.of("point"));
        var scope = mock(org.jetlinks.pro.patrol.integration.PatrolScope.class);
        when(scope.current()).thenReturn(Mono.just(new org.jetlinks.pro.patrol.integration.PatrolScope.Current("app")));
        var assignments = new PatrolPointAssignmentService(staleReader, plans, categoryValidator(), scope,
                TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
        var pending = assignments.assign(List.of("point"), List.of("post"), "category")
                .contextWrite(identity).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).toFuture();
        try {
            assertTrue(selected.asMono().block(Duration.ofSeconds(10)));
            var relations = db.repository(org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity.class,
                    org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity::new);
            pointCategories(relations).cancel(List.of("point"), "category").contextWrite(identity).block(Duration.ofSeconds(10));
            resume.tryEmitValue(true);
            StepVerifier.create(Mono.fromFuture(pending)).expectErrorMessage("error.patrol.assignment.changed").verify(Duration.ofSeconds(10));
            assertEquals(List.of(), points.findById("point").block().getCategoryIds());
            org.junit.jupiter.api.Assertions.assertFalse(plans.findById("plan").block().isEnabled());
        } finally { resume.tryEmitValue(true); pending.cancel(true); }
    }

    @Test
    void genericPlanUpdateRetainsAuthoritativeCategoryEvenWithReadOnlyJsonField() throws Exception {
        secondCategory();
        var relations = db.repository(org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity::new);
        pointCategories(relations).link(List.of("point"), "category2").contextWrite(identity).block();
        var old = plans.findById("plan").block();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var input = mapper.readValue(mapper.writeValueAsString(old), PatrolPlanEntity.class);
        org.junit.jupiter.api.Assertions.assertNull(input.getCategoryId());
        categoryValidator().validate(input, old).contextWrite(identity).block();
        assertEquals("category", input.getCategoryId());
        input.setCategoryId("category2");
        categoryValidator().validate(input, old).contextWrite(identity).block();
        assertEquals("category", input.getCategoryId());
    }

    @Test
    void assignmentOverviewIncludesReadableActivePlansAcrossCategoriesOnly() {
        db.enableTenantQueries();
        secondCategory();
        var relations = db.repository(org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity::new);
        pointCategories(relations).link(List.of("point"), "category2").contextWrite(identity).block();
        seedPlan("secondary", "north");
        plans.createUpdate().set(PatrolPlanEntity::getCategoryId, "category2")
                .where(PatrolPlanEntity::getId, "secondary").execute().block();
        seedPlan("hidden", "north");
        var hidden = plans.findById("hidden").block();
        hidden.setExtension("tenantId", "south"); plans.updateById("hidden", hidden).block();
        seedPlan("disabled", "north");
        plans.createUpdate().set(PatrolPlanEntity::isEnabled, false)
                .where(PatrolPlanEntity::getId, "disabled").execute().block();
        var summary = pointAssignments().summary(List.of("point")).contextWrite(identity).block();
        assertEquals(Set.of("plan", "secondary"), summary.get(0).assignments().stream()
                .map(PatrolPlanEntity::getId).collect(java.util.stream.Collectors.toSet()));
        pointCategories(relations).cancel(List.of("point"), "category2").contextWrite(identity).block();
        assertEquals(List.of("plan"), pointAssignments().summary(List.of("point")).contextWrite(identity).block()
                .get(0).assignments().stream().map(PatrolPlanEntity::getId).toList());
    }

    @Test
    void pointExportsUseCurrentCategoriesAndCategorySpecificPostsWithoutResurrectingCancelledLinks() throws Exception {
        db.enableTenantQueries();
        secondCategory(); secondAssignmentPost();
        var relations = db.repository(org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity::new);
        var service = pointCategories(relations);
        service.link(List.of("point"), "category2").contextWrite(identity).block();
        pointAssignments().assign(List.of("point"), List.of("post2"), "category2").contextWrite(identity).block();
        ownNewAssignments();
        var query = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery().is("id", "point").getParam();
        query.setPageIndex(7); query.setPageSize(1);
        var transfer = pointTransfer(points);
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(
                transfer.export(query).contextWrite(identity).block()))) {
            assertEquals("Building、Fire safety", book.getSheetAt(0).getRow(1).getCell(2).getStringCellValue());
            assertEquals("Evening、Security", book.getSheetAt(0).getRow(1).getCell(3).getStringCellValue());
        }
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(
                transfer.export(query, "category2").contextWrite(identity).block()))) {
            assertEquals("Fire safety", book.getSheetAt(0).getRow(1).getCell(2).getStringCellValue());
            assertEquals("Evening", book.getSheetAt(0).getRow(1).getCell(3).getStringCellValue());
        }
        assertEquals(7, query.getPageIndex());
        service.cancel(List.of("point"), "category").contextWrite(identity).block();
        service.cancel(List.of("point"), "category2").contextWrite(identity).block();
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(
                transfer.export(query).contextWrite(identity).block()))) {
            assertEquals("", book.getSheetAt(0).getRow(1).getCell(2).getStringCellValue());
            assertEquals("", book.getSheetAt(0).getRow(1).getCell(3).getStringCellValue());
        }
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(
                transfer.export(query, "category").contextWrite(identity).block()))) {
            assertEquals(0, book.getSheetAt(0).getLastRowNum());
        }
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
        assertEquals(1, first.size());
        String groupId = first.get(0).getTaskGroupId();
        assertNotNull(groupId);
        assertEquals(1, first.stream().map(PatrolTaskEntity::getTaskGroupId).distinct().count());
        assertEquals(List.of(1),
                first.stream().map(PatrolTaskEntity::getPeriodNo).toList());
        assertEquals(LocalDate.now().toString(), first.get(0).getBelongDate());
        var group = generationRepository.findById(groupId).block();
        assertEquals(1, group.getPeriodCount());
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
        assertEquals(1, tasks.createQuery().count().block());
        assertEquals("Lock", again.get(0).getStandardSnapshot().get(0).title());
        assertEquals(first.get(0).getRuleVersion(), again.get(0).getRuleVersion());
        assertEquals(groupId, again.get(0).getTaskGroupId());
    }

    @Test
    void legacyJsonFallsBackToGlobalGapAndNewPeriodsPersistIndependentGaps() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var old = mapper.readValue("""
                {"weekly":{"1":[{"startMinute":480,"endMinute":720,"intervalMinutes":60,"requiredCount":1}]},
                 "dates":{},"cooldownMinutes":15}
                """, PatrolRule.class);
        assertEquals(15, RuleEngine.expand(old, LocalDate.of(2026, 11, 2), ZoneId.of("Asia/Shanghai")).get(0).cooldownMinutes());
        var day = LocalDate.now();
        var configured = new PatrolRule(Map.of(), Map.of(day, List.of(
                new PatrolRule.Window(480, 720, 60, 1, 10, "2", "1"),
                new PatrolRule.Window(780, 1080, 120, 1, 120, "2", "2"))), 0);
        plans.createUpdate().set(PatrolPlanEntity::getRule, configured)
                .where(PatrolPlanEntity::getId, "plan").execute().block();
        var rows = generator.generate("plan", day).contextWrite(identity).collectList().block();
        assertEquals(List.of(4, 2), rows.stream().map(PatrolTaskEntity::getRequiredCount).toList());
        assertEquals(List.of(10, 120), rows.stream().map(PatrolTaskEntity::getCooldownMinutes).toList());
        assertEquals(List.of(1, 2), rows.stream().map(PatrolTaskEntity::getPeriodNo).toList());
        var group = generationRepository.findById(rows.get(0).getTaskGroupId()).block();
        assertEquals(2, group.getPeriodCount());
        assertEquals(6, group.getRequiredCount());
        assertEquals("2", plans.findById("plan").block().getRule().dates().get(day).get(1).cooldownUnit());
    }

    @Test
    void previouslySealedTaskAndExecutionKeepTheirVersionAndProgress() {
        var task = seedTask(2);
        var group = new PatrolGenerationEntity();
        group.setId(task.getTaskGroupId()); group.setPlanId("plan");
        group.setBusinessDate(LocalDate.now().toString()); group.setBelongDate(LocalDate.now().toString());
        group.setRuleVersion("fixture-rule-v1"); group.setPeriodCount(1); group.setRequiredCount(2);
        generationRepository.insert(group).block();
        var execution = workflow.submit(submission("historical-request")).contextWrite(identity).block();
        var replay = generator.generate("plan", LocalDate.now()).contextWrite(identity).collectList().block();
        assertEquals(1, replay.size());
        assertEquals("task", replay.get(0).getId());
        assertEquals(2, replay.get(0).getRequiredCount());
        assertEquals(1, replay.get(0).getCompletedCount());
        assertEquals("fixture-rule-v1", replay.get(0).getRuleVersion());
        assertEquals("fixture-rule-v1", executions.findById(execution.getId()).block().getRuleVersion());
        assertEquals(1, executions.createQuery().count().block());
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
                .expectNextCount(1)
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
                        null, null, null);
        StepVerifier.create(workflow.submit(conflict).contextWrite(identity))
                .expectError()
                .verify();
    }

    @Test
    void collectionMetadataIsImmutableAndQueryableWithoutReplacingServerTime() {
        seedTask(3);
        long collected = System.currentTimeMillis() - 60_000;
        var submission = new PatrolExecutionWorkflow.Submission("task", "collected-online",
                List.of(new PatrolExecutionEntity.Answer("lock", "normal")), null,
                collected, PatrolExecutionEntity.NetworkStatus.online);
        var accepted = workflow.submit(submission).contextWrite(identity).block();
        var saved = executions.findById(accepted.getId()).block();
        assertEquals(collected, saved.getCollectedTime());
        assertEquals(PatrolExecutionEntity.NetworkStatus.online, saved.getNetworkStatus());
        assertTrue(saved.getReceiveTime() > collected);
        assertEquals(saved.getReceiveTime(), tasks.findById("task").block().getLastExecutionTime());
        assertEquals(accepted.getId(), workflow.submit(submission).contextWrite(identity).block().getId());
        assertEquals(1, tasks.findById("task").block().getCompletedCount());
        var online = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
                .is("networkStatus", "online").gte("collectedTime", collected).getParam();
        var page = executions.queryPager(online).block();
        assertEquals(1, page.getTotal());
        assertEquals(collected, page.getData().get(0).getCollectedTime());
        assertEquals(0, executions.count(org.hswebframework.web.api.crud.entity.QueryParamEntity
                .newQuery().is("networkStatus", "offline").getParam()).block());
        for (var changed : List.of(
                new PatrolExecutionWorkflow.Submission("task", "collected-online", submission.answers(), null,
                        collected + 1, submission.networkStatus()),
                new PatrolExecutionWorkflow.Submission("task", "collected-online", submission.answers(), null,
                        collected, PatrolExecutionEntity.NetworkStatus.offline))) {
            StepVerifier.create(workflow.submit(changed).contextWrite(identity))
                    .expectErrorMatches(error -> error instanceof org.hswebframework.web.exception.BusinessException business
                            && business.getStatus() == 409
                            && "error.patrol.execution.idempotency_conflict".equals(error.getMessage()))
                    .verify();
        }
        assertEquals(1, executions.createQuery().count().block());
    }

    @Test
    void publishedClientWithoutCollectionMetadataRemainsUnrecorded() {
        seedTask(2);
        var accepted = workflow.submit(submission("old-client")).contextWrite(identity).block();
        var saved = executions.findById(accepted.getId()).block();
        org.junit.jupiter.api.Assertions.assertNull(saved.getCollectedTime());
        org.junit.jupiter.api.Assertions.assertNull(saved.getNetworkStatus());
        assertNotNull(saved.getReceiveTime());
        assertEquals(accepted.getId(), workflow.submit(submission("old-client")).contextWrite(identity).block().getId());
    }

    @Test
    void clientCollectionTimeCannotAuthorizeAnExpiredWindow() {
        long now = System.currentTimeMillis();
        seedTask(1, now - 120_000, now - 60_000);
        var body = new PatrolExecutionWorkflow.Submission("task", "forged-collection",
                List.of(new PatrolExecutionEntity.Answer("lock", "normal")), null,
                now - 90_000, PatrolExecutionEntity.NetworkStatus.offline);
        StepVerifier.create(workflow.submit(body).contextWrite(identity))
                .expectErrorMatches(error -> "error.patrol.execution.outside_window".equals(error.getMessage()))
                .verify();
        assertEquals(0, executions.createQuery().count().block());
        assertEquals(0, tasks.findById("task").block().getCompletedCount());
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
                        roles,
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
        var invalid = new PatrolExecutionWorkflow.Submission("task", "bad", List.of(), null, null, null);
        StepVerifier.create(workflow.submit(invalid).contextWrite(identity))
                .expectErrorSatisfies(error -> assertEquals(400,
                        ((org.hswebframework.web.exception.BusinessException) error).getStatus())).verify();
        roles.get("post").setUserIds(List.of("bob"));
        StepVerifier.create(workflow.submit(submission("removed")).contextWrite(identity))
                .expectError()
                .verify();
        assertEquals(0, tasks.findById("task").block().getCompletedCount());
    }

    @Test
    void typedRuleAndInspectionSnapshotsSurvivePersistence() {
        var rule = plans.findById("plan").block().getRule();
        assertEquals(
                1, RuleEngine.expand(rule, LocalDate.now(), ZoneId.of("Asia/Shanghai")).size());
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
                .set(PatrolPlanEntity::getRule, new PatrolRule(Map.of(), Map.of(LocalDate.now(), List.of()), 0))
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
                        roles,
                        standards,
                        broken,
                        new PatrolLevelService(db.repository(org.jetlinks.pro.patrol.entity.PatrolLevelEntity.class,
                                org.jetlinks.pro.patrol.entity.PatrolLevelEntity::new)),
                        categories,
                        generationRepository,
                        TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
        StepVerifier.create(service.generate("plan", LocalDate.now()).contextWrite(identity))
                .expectError()
                .verify();
        assertEquals(0, generationRepository.createQuery().count().block());
        assertEquals(
                1,
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
        assertEquals(1, results.getT1().size());
        assertEquals(
                results.getT1().stream().map(PatrolTaskEntity::getId).toList(),
                results.getT2().stream().map(PatrolTaskEntity::getId).toList());
        assertEquals(1, tasks.createQuery().count().block());
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
        var controller = new org.jetlinks.pro.patrol.web.PatrolTaskGroupController(new PatrolTaskGroupService(generationRepository, new org.hswebframework.web.crud.query.DefaultQueryHelper(db.operator()), tasks));
        var query = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery().orderByAsc("id").getParam();
        query.setPageSize(1); query.setPageIndex(0);
        var page = controller.queryPager(query).contextWrite(identity).block();
        assertEquals(2, page.getTotal()); assertEquals(1, page.getData().size());
        var second = query.clone(); second.setPageIndex(1);
        var secondPage = controller.queryPager(second).contextWrite(identity).block();
        org.junit.jupiter.api.Assertions.assertNotEquals(page.getData().get(0).getId(), secondPage.getData().get(0).getId());
        StepVerifier.create(controller.getById("foreign-group").contextWrite(identity)).expectError().verify();
        var periods = new org.jetlinks.pro.patrol.web.PatrolRecordPeriodController(tasks);
        assertEquals(1, periods.query(org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
                .is("taskGroupId", page.getData().get(0).getId()).getParam()).contextWrite(identity).count().block());
    }

    @Test
    void restDaySealsRemainIdempotentButDoNotAppearAsTasksOrConsumePages() {
        db.enableTenantQueries();
        var date = LocalDate.now();
        plans.createUpdate().set(PatrolPlanEntity::getRule,
                new PatrolRule(Map.of(), Map.of(date, List.of()), 0))
                .where(PatrolPlanEntity::getId, "plan").execute().block();
        assertTrue(generator.generate("plan", date).contextWrite(identity).collectList().block().isEmpty());
        assertTrue(generator.generate("plan", date).contextWrite(identity).collectList().block().isEmpty());
        assertEquals(1, generationRepository.createQuery().count().block());
        seedPlan("plan-b", "north");
        generator.generate("plan-b", date).contextWrite(identity).collectList().block();
        var controller = new org.jetlinks.pro.patrol.web.PatrolTaskGroupController(
                new PatrolTaskGroupService(generationRepository,
                        new org.hswebframework.web.crud.query.DefaultQueryHelper(db.operator()), tasks));
        // OR 条件也不能使空封印进入分页；导出复用同一分页查询。
        var query = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
                .is("planId", "plan").or("planId", "plan-b").getParam();
        query.setPageSize(1);
        var page = controller.queryPager(query.clone()).contextWrite(identity).block();
        assertEquals(1, page.getTotal());
        assertEquals("plan-b", page.getData().get(0).getPlanId());
        assertEquals(1, controller.count(query.clone()).contextWrite(identity).block());
        assertEquals(1, controller.query(query.clone().noPaging()).contextWrite(identity).count().block());
        var seal = generationRepository.createQuery().where("planId", "plan").fetchOne().block();
        StepVerifier.create(controller.getById(seal.getId()).contextWrite(identity)).expectError().verify();
    }

    @Test
    void taskGroupsFilterSnapshotsBeforePagingAndSumAllPeriods() {
        db.enableTenantQueries();
        plans.createUpdate().set(PatrolPlanEntity::getRule,
                new PatrolRule(Map.of(), Map.of(LocalDate.now(), List.of(
                        new PatrolRule.Window(480, 720, 60, 1),
                        new PatrolRule.Window(780, 1080, 60, 1))), 0))
                .where(PatrolPlanEntity::getId, "plan").execute().block();
        var periods = generator.generate("plan", LocalDate.now()).contextWrite(identity).collectList().block();
        assertEquals(2, periods.size());
        tasks.createUpdate().set(PatrolTaskEntity::getCompletedCount, 2)
                .set(PatrolTaskEntity::getLastExecutionTime, 1234L)
                .where(PatrolTaskEntity::getId, periods.get(0).getId()).execute().block();
        tasks.createUpdate().set(PatrolTaskEntity::getCompletedCount, 3)
                .set(PatrolTaskEntity::getLastExecutionTime, 2345L)
                .where(PatrolTaskEntity::getId, periods.get(1).getId()).execute().block();
        // 配置变更不修改已生成分类快照，另一组不参与本组进度。
        categories.createUpdate().set(PatrolCategoryEntity::getName, "Renamed")
                .where(PatrolCategoryEntity::getId, "category").execute().block();
        points.createUpdate().set(PatrolPointEntity::getCategoryId, "different-category")
                .where(PatrolPointEntity::getId, "point").execute().block();
        seedPlan("plan-b", "north");
        generator.generate("plan-b", LocalDate.now()).contextWrite(identity).collectList().block();
        var controller = new org.jetlinks.pro.patrol.web.PatrolTaskGroupController(
                new PatrolTaskGroupService(generationRepository,
                        new org.hswebframework.web.crud.query.DefaultQueryHelper(db.operator()), tasks));
        var query = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
                .is("categoryId", "category").orderByAsc("id").getParam();
        query.setPageSize(1);
        var page = controller.queryPager(query).contextWrite(identity).block();
        assertEquals(1, page.getTotal());
        var group = page.getData().get(0);
        assertEquals("Building", group.getCategoryName());
        assertEquals(9, group.getRequiredCount()); assertEquals(5, group.getCompletedCount());
        assertEquals(2345L, group.getLastExecutionTime()); assertEquals(2, group.getPeriodCount());
        assertEquals(1, controller.count(query.clone()).contextWrite(identity).block());
        assertEquals(5, controller.getById(group.getId()).contextWrite(identity).block().getCompletedCount());
        var none = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery().is("levelId", "other-level").getParam();
        assertEquals(0, controller.queryPager(none).contextWrite(identity).block().getTotal());
    }

    @Test
    void taskGroupMissedFiltersKeepHistoricalApplicationsAndAuthorizedPaging() {
        db.enableTenantQueries();
        var missed = db.repository(org.jetlinks.pro.patrol.entity.PatrolMissedItemEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolMissedItemEntity::new);
        var states = List.of("pending", "approved", "rejected", "withdrawn", "none", "foreign");
        seedTask(2);
        for (String state : states) {
            String planId = "missed-" + state;
            seedPlan(planId, state.equals("foreign") ? "south" : "north");
            // 生成器独立受资产权限保护；外部租户组仅作为越权查询夹具直接写入。
            String groupId = PatrolTaskGenerator.groupId(planId, LocalDate.now());
            var group = new PatrolGenerationEntity();
            group.setId(groupId); group.setPlanId(planId); group.setBusinessDate(LocalDate.now().toString());
            group.setBelongDate(LocalDate.now().toString()); group.setPointName(state);
            generationRepository.insert(group).block();
            // 漏检必须关联真实时段，不能只用生成封印模拟业务任务组。
            var period = tasks.findById("task").block();
            period.setId(planId); period.setPlanId(planId); period.setTaskGroupId(groupId); period.setPeriodNo(1);
            tasks.insert(period).block();
            for (int n = 1; n <= 2; n++) {
                var item = new org.jetlinks.pro.patrol.entity.PatrolMissedItemEntity();
                item.setId(planId + n); item.setTaskId(planId); item.setTaskGroupId(groupId);
                item.setPlanId(planId); item.setOccurrenceNo(n); item.setCreateTime(System.currentTimeMillis());
                item.setStatus(org.jetlinks.pro.patrol.entity.PatrolMissedItemEntity.Status.valueOf(
                        state.equals("foreign") ? "pending" : state));
                if (!state.equals("none")) item.setApplicationId("application-" + planId + n);
                missed.insert(item).block();
            }
        }
        var controller = new org.jetlinks.pro.patrol.web.PatrolTaskGroupController(
                new PatrolTaskGroupService(generationRepository,
                        new org.hswebframework.web.crud.query.DefaultQueryHelper(db.operator()), tasks));
        var pending = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
                .accept("id", "patrol-group-missed", Map.of("status", "pending")).getParam();
        assertEquals(1, controller.queryPager(pending).contextWrite(identity).block().getTotal());
        var applied = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
                .accept("id", "patrol-group-missed", Map.of("applicationId$notnull", true))
                .orderByAsc("id").getParam();
        applied.setPageSize(2);
        var first = controller.queryPager(applied.clone()).contextWrite(identity).block();
        assertEquals(4, first.getTotal()); assertEquals(2, first.getData().size());
        applied.setPageIndex(1);
        var second = controller.queryPager(applied).contextWrite(identity).block();
        assertEquals(4, second.getTotal());
        var ids = java.util.stream.Stream.concat(first.getData().stream(), second.getData().stream())
                .map(PatrolGenerationEntity::getId).collect(java.util.stream.Collectors.toSet());
        assertEquals(4, ids.size());
        assertEquals(4, controller.count(applied.clone()).contextWrite(identity).block());
        // 导出忽略当前列表页，保留同一申请条件和计划资产隔离；foreign 不进入文件。
        applied.setPageSize(1);
        var download = controller.export(applied.clone()).contextWrite(identity).block();
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(download.getBody()))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(5, sheet.getPhysicalNumberOfRows());
            var names = java.util.stream.IntStream.range(1, 5)
                    .mapToObj(i -> sheet.getRow(i).getCell(0).getStringCellValue()).collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of("pending", "approved", "rejected", "withdrawn"), names);
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void pointTagAndPostFiltersRunBeforePagingAndDoNotTreatIdsAsWildcards() {
        db.enableTenantQueries();
        points.createUpdate().set(PatrolPointEntity::getTagIds, List.of("tag", "literal%_!"))
                .where(PatrolPointEntity::getId, "point").execute().block();
        var second = points.findById("point").block();
        second.setId("second"); second.setCode("P002"); second.setTagIds(List.of("tag-extra", "literalXX!"));
        points.insert(second).block();
        var controller = new org.jetlinks.pro.patrol.web.PatrolPointController(points, null);
        var query = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
                .accept("tagIds", "patrol-point-tags", List.of("tag"))
                .accept("id", "patrol-point-plan", Map.of("roleId$in", List.of("post")))
                .orderByAsc("id").getParam();
        query.setPageSize(1);
        var page = controller.queryPager(query).contextWrite(identity).block();
        assertEquals(1, page.getTotal()); assertEquals("point", page.getData().get(0).getId());
        var literal = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
                .accept("tagIds", "patrol-point-tags", List.of("literal%_!", "missing")).getParam();
        assertEquals(1, controller.queryPager(literal).contextWrite(identity).block().getTotal());
        var exclude = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
                .accept("tagIds", "patrol-point-tags$not", List.of("tag")).getParam();
        assertEquals("second", controller.queryPager(exclude).contextWrite(identity).block().getData().get(0).getId());
        var none = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
                .accept("id", "patrol-point-plan", Map.of("roleId$in", List.of("unknown"))).getParam();
        assertEquals(0, controller.queryPager(none).contextWrite(identity).block().getTotal());
    }

    @Test
    void executionHistoryProjectsOriginalPeriodAndInspectionSnapshots() {
        var previous = org.hswebframework.ezorm.core.GlobalConfig.getPropertyOperator();
        new org.jetlinks.pro.patrol.configuration.PatrolJsonMetadataCustomizer(new com.fasterxml.jackson.databind.ObjectMapper())
                .registerBusinessDateConverter();
        try {
            org.hswebframework.ezorm.core.GlobalConfig.setPropertyOperator(
                    new org.jetlinks.pro.configure.crud.FastObjectPropertyOperator());
            var task = seedTask(3);
            var submission = new PatrolExecutionWorkflow.Submission(task.getId(), "period-details",
                    List.of(new PatrolExecutionEntity.Answer("lock", "normal")), "checked", null, null);
            var saved = workflow.submit(submission).contextWrite(identity).block();
            assertEquals(task.getStartTime(), saved.getPeriodStartTime());
            var query = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery().is("taskId", task.getId()).getParam();
            var page = executions.queryPager(query).block();
            assertEquals(1, page.getTotal()); var record = page.getData().get(0);
            assertEquals(task.getStartTime(), record.getPeriodStartTime());
            assertEquals(task.getEndTime(), record.getPeriodEndTime());
            assertEquals("Lock", record.getStandardSnapshot().get(0).title());
            assertEquals("normal", record.getAnswers().get(0).value());
            assertEquals(record.getPeriodEndTime(), executions.findById(record.getId()).block().getPeriodEndTime());
        } finally {
            org.hswebframework.ezorm.core.GlobalConfig.setPropertyOperator(previous);
        }
    }

    @Test
    void calendarUsesBelongDateAcrossMonthBoundaryAndKeepsRestRowsAndAssetIsolation() {
        db.enableTenantQueries();
        var day = LocalDate.of(2026, 10, 31);
        long start = day.atTime(23, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        // Task period snapshots are immutable; seed the cross-day period at creation.
        var task = seedTask(3, start, start + 7200000, day);
        tasks.createUpdate().set(PatrolTaskEntity::getCompletedCount, 1)
                .where(PatrolTaskEntity::getId, task.getId()).execute().block();
        var rest = new PatrolRosterEntity();
        rest.setId("rest"); rest.setName("休息"); rest.setRest(true); rest.setRoleId("post"); rest.setUserId("alice");
        rest.setBusinessDate(day.plusDays(1)); rest.setScopeKey("app"); rest.setProjectId("project"); rest.setProjectName("Project");
        rest.setStartTime(start + 3600000); rest.setEndTime(start + 7200000); rest.setExtension("tenantId", "north");
        rosters.insert(rest).block();
        var foreign = new PatrolRosterEntity();
        foreign.setId("foreign-roster"); foreign.setName("South"); foreign.setRoleId("post"); foreign.setUserId("bob");
        foreign.setBusinessDate(day.plusDays(1)); foreign.setScopeKey("other-app"); foreign.setProjectId("project"); foreign.setProjectName("Project");
        foreign.setStartTime(start + 3600000); foreign.setEndTime(start + 7200000); foreign.setExtension("tenantId", "south");
        rosters.insert(foreign).block();
        var calendarPeople = mock(SaasPersonDirectory.class);
        when(calendarPeople.telephones(List.of("alice"))).thenReturn(Mono.just(Map.of("alice", "13800000001")));
        when(calendarPeople.telephones(List.of())).thenReturn(Mono.just(Map.of()));
        var calendar = new PatrolCalendarService(rosters, tasks, calendarPeople);
        var taskDay = calendar.day(day).contextWrite(identity).block();
        assertEquals(1, taskDay.tasks().size());
        assertEquals(1, taskDay.pointCount()); assertEquals(0, taskDay.completedPointCount());
        assertEquals(1, taskDay.missedPointCount());
        assertEquals(3, taskDay.expectedCount()); assertEquals(1, taskDay.completedCount());
        var detail = calendar.day(day.plusDays(1)).contextWrite(identity).block();
        assertEquals(0, detail.tasks().size()); assertEquals(1, detail.staff().size());
        assertEquals(Map.of("alice", "13800000001"), detail.staffTelephones());
        assertEquals(0, detail.staffCount()); assertEquals(0, detail.shiftCount());
        assertEquals(0, detail.expectedCount()); assertEquals(0, detail.completedCount());
        var october = calendar.month(java.time.YearMonth.from(day)).contextWrite(identity).block();
        var october31 = october.days().get(30);
        assertEquals(1, october31.pointCount()); assertEquals(0, october31.completedPointCount());
        assertEquals(1, october31.missedPointCount());
        var month = calendar.month(java.time.YearMonth.from(day.plusDays(1))).contextWrite(identity).block();
        assertEquals(java.time.YearMonth.from(day.plusDays(1)).lengthOfMonth(), month.days().size());
        assertEquals(0, month.summary().rosterCount());
        assertEquals(0, month.summary().taskCount());
    }

    @Test
    void calendarCountsPointsAcrossRolesAndPeriodsAndRequiresAllRolesToComplete() {
        db.enableTenantQueries();
        long now = System.currentTimeMillis();
        var first = seedTask(2, now - 7200000, now - 3600000);
        tasks.createUpdate().set(PatrolTaskEntity::getCompletedCount, 1).where(PatrolTaskEntity::getId, first.getId()).execute().block();
        seedPlan("plan2", "north");
        var second = tasks.findById(first.getId()).block(); second.setId("role2"); second.setPlanId("plan2");
        second.setTaskGroupId("group2"); second.setRoleId("post2"); second.setCompletedCount(0);
        second.setStartTime(now - 60000); second.setEndTime(now + 3600000); tasks.insert(second).block();
        var period = tasks.findById(first.getId()).block(); period.setId("period2"); period.setPeriodNo(2);
        period.setStartTime(now - 10800000); period.setEndTime(now - 7200000); period.setCompletedCount(0); tasks.insert(period).block();
        var other = tasks.findById(first.getId()).block(); other.setId("other-point"); other.setPointId("other-point");
        other.setTaskGroupId("other-group"); other.setStartTime(now - 10800001); other.setCompletedCount(2); tasks.insert(other).block();
        var future = tasks.findById(first.getId()).block(); future.setId("future-point"); future.setPointId("future-point");
        future.setTaskGroupId("future-group"); future.setStartTime(now + 3600000); future.setEndTime(now + 7200000);
        future.setCompletedCount(0); tasks.insert(future).block();
        seedPlan("hidden-plan", "south");
        var hidden = tasks.findById(other.getId()).block(); hidden.setId("hidden"); hidden.setPlanId("hidden-plan");
        hidden.setTaskGroupId("hidden-group"); hidden.setPointId("hidden"); hidden.setStartTime(now - 10800002); tasks.insert(hidden).block();
        var people = mock(SaasPersonDirectory.class); when(people.telephones(List.of())).thenReturn(Mono.just(Map.of()));
        var calendar = new PatrolCalendarService(rosters, tasks, people);
        var detail = calendar.day(LocalDate.now()).contextWrite(identity).block();
        assertEquals(3, detail.pointCount()); assertEquals(1, detail.completedPointCount()); assertEquals(1, detail.missedPointCount());
        var day = calendar.month(java.time.YearMonth.now()).contextWrite(identity).block().days().get(LocalDate.now().getDayOfMonth() - 1);
        assertEquals(detail.pointCount(), day.pointCount()); assertEquals(detail.completedPointCount(), day.completedPointCount());
        assertEquals(detail.missedPointCount(), day.missedPointCount());
        // 一个角色完成仍不能算点位完成；所有角色及全部时段均完成后才增加一个点位。
        tasks.createUpdate().set(PatrolTaskEntity::getCompletedCount, 2).where(PatrolTaskEntity::getId, "task").execute().block();
        tasks.createUpdate().set(PatrolTaskEntity::getCompletedCount, 2).where(PatrolTaskEntity::getId, "period2").execute().block();
        assertEquals(1, calendar.day(LocalDate.now()).contextWrite(identity).block().completedPointCount());
        tasks.createUpdate().set(PatrolTaskEntity::getCompletedCount, 2).where(PatrolTaskEntity::getId, "role2").execute().block();
        var done = calendar.day(LocalDate.now()).contextWrite(identity).block();
        assertEquals(2, done.completedPointCount()); assertEquals(0, done.missedPointCount());
    }

    @Test
    void calendarUsesStoredBusinessDateForDeployedTasksWithoutBelongDate() {
        db.enableTenantQueries();
        var task = seedTask(1);
        var old = tasks.findById(task.getId()).block(); old.setId("legacy-date"); old.setTaskGroupId("legacy-date");
        old.setBelongDate(null); old.setBusinessDate("2026-10-31"); old.setStartTime(task.getStartTime() + 1); tasks.insert(old).block();
        var people = mock(SaasPersonDirectory.class); when(people.telephones(List.of())).thenReturn(Mono.just(Map.of()));
        var calendar = new PatrolCalendarService(rosters, tasks, people);
        var result = calendar.day(LocalDate.of(2026, 10, 31)).contextWrite(identity).block();
        assertEquals(List.of("legacy-date"), result.tasks().stream().map(PatrolTaskEntity::getId).toList());
        assertEquals(1, result.pointCount());
    }

    @Test
    void calendarSeparatesAbsenceFromMissedPointsAndWaitsUntilAllPeriodsEnd() {
        db.enableTenantQueries();
        long now = System.currentTimeMillis();
        var first = seedTask(2, now - 7200000, now - 3600000);
        var later = tasks.findById(first.getId()).block(); later.setId("later"); later.setPeriodNo(2);
        later.setStartTime(now - 60000); later.setEndTime(now + 3600000); later.setRoleId("post2"); tasks.insert(later).block();
        var absent = tasks.findById(first.getId()).block(); absent.setId("absent"); absent.setPointId("absent-point");
        absent.setTaskGroupId("absent-group"); absent.setStartTime(now - 10800000); tasks.insert(absent).block();
        var absentRole = tasks.findById(absent.getId()).block(); absentRole.setId("absent-role2");
        absentRole.setTaskGroupId("absent-role-group"); absentRole.setRoleId("post2");
        absentRole.setStartTime(now - 10800001); tasks.insert(absentRole).block();
        var zero = tasks.findById(first.getId()).block(); zero.setId("zero"); zero.setPointId("zero-point");
        zero.setTaskGroupId("zero-group"); zero.setRequiredCount(0); zero.setStartTime(now - 10800002); tasks.insert(zero).block();
        var people = mock(SaasPersonDirectory.class); when(people.telephones(List.of())).thenReturn(Mono.just(Map.of()));
        var calendar = new PatrolCalendarService(rosters, tasks, people);
        var before = calendar.day(LocalDate.now()).contextWrite(identity).block();
        assertEquals(3, before.pointCount()); assertEquals(1, before.completedPointCount());
        assertEquals(1, before.absentPointCount()); assertEquals(0, before.missedPointCount());
        // 同一点位从未巡变成部分巡更后，才进入漏检口径；另一个点位的缺勤数保持不变。
        tasks.createUpdate().set(PatrolTaskEntity::getCompletedCount, 1)
                .where(PatrolTaskEntity::getId, first.getId()).execute().block();
        var after = calendar.day(LocalDate.now()).contextWrite(identity).block();
        assertEquals(1, after.missedPointCount()); assertEquals(1, after.absentPointCount());
        var day = calendar.month(java.time.YearMonth.now()).contextWrite(identity).block().days().get(LocalDate.now().getDayOfMonth() - 1);
        assertEquals(after.completedPointCount(), day.completedPointCount());
        assertEquals(after.missedPointCount(), day.missedPointCount());
        assertEquals(after.absentPointCount(), day.absentPointCount());
        var month = calendar.month(java.time.YearMonth.now()).contextWrite(identity).block();
        assertEquals(after.missedPointCount(), month.summary().missedPointCount());
        assertEquals(after.absentPointCount(), month.summary().absentPointCount());
        assertEquals(after.pointCount(), month.summary().pointCount());
    }

    @Test
    void overviewUsesBusinessDateSnapshotFiltersAndAuthoritativePlanPermissionsForEveryRead() {
        db.enableTenantQueries();
        var day = LocalDate.now().minusDays(2);
        seedTask(2, System.currentTimeMillis() - 3600000, System.currentTimeMillis() - 1000, day);
        var own = tasks.findById("task").block(); own.setId("overview-task"); own.setPeriodNo(2);
        own.setStartTime(own.getStartTime() + 1); own.setProjectId("project"); own.setCategoryId("category");
        own.setLevelId("level"); own.setCompletedCount(1); tasks.insert(own).block();
        seedPlan("south-plan", "south");
        var foreign = tasks.findById("overview-task").block(); foreign.setId("foreign"); foreign.setPlanId("south-plan");
        foreign.setTaskGroupId("foreign-group"); foreign.setPointId("foreign-point"); tasks.insert(foreign).block();
        var fact = new PatrolExecutionEntity(); fact.setId("fact"); fact.setTaskId("overview-task"); fact.setPlanId("plan");
        fact.setPointId("point"); fact.setProjectId("project"); fact.setUserId("alice"); fact.setUserName("Alice");
        fact.setRequestId("r"); fact.setReceiveTime(System.currentTimeMillis()); fact.setResult(PatrolExecutionEntity.CheckResult.fail);
        executions.insert(fact).block();
        var otherFact = new PatrolExecutionEntity(); otherFact.setId("foreign-fact"); otherFact.setTaskId("foreign");
        otherFact.setPlanId("south-plan"); otherFact.setPointId("foreign-point"); otherFact.setProjectId("project");
        otherFact.setUserId("secret"); otherFact.setUserName("Secret"); otherFact.setRequestId("r2"); otherFact.setReceiveTime(System.currentTimeMillis());
        executions.insert(otherFact).block();
        var service = new org.jetlinks.pro.patrol.service.statistics.PatrolOverviewService(tasks, executions);
        var range = new org.jetlinks.pro.patrol.service.statistics.PatrolOverview.Range(day, day, List.of("category"), List.of("level"), "project");
        var result = service.aggregate(range).contextWrite(identity).block();
        assertEquals(2, result.report().total().expected()); assertEquals(1, result.report().total().overdue());
        assertEquals(1, result.report().total().abnormalPoints()); assertEquals(1, result.report().people().size());
        assertEquals("alice", result.report().people().get(0).userId());
        var detail = service.detail(new org.jetlinks.pro.patrol.service.statistics.PatrolOverview.DetailRequest(range, "foreign-point"))
                .contextWrite(identity).block();
        assertTrue(detail.periods().isEmpty()); assertTrue(detail.executions().isEmpty());
        var page = service.drill(new org.jetlinks.pro.patrol.service.statistics.PatrolOverview.Drill(range,
                org.jetlinks.pro.patrol.service.statistics.PatrolOverview.Metric.overdue, 0, 1)).contextWrite(identity).block();
        assertEquals(1, page.getTotal()); assertEquals("point", page.getData().get(0).pointId());
        var next = service.drill(new org.jetlinks.pro.patrol.service.statistics.PatrolOverview.Drill(range,
                org.jetlinks.pro.patrol.service.statistics.PatrolOverview.Metric.overdue, 1, 1)).contextWrite(identity).block();
        assertTrue(next.getData().isEmpty());
        var wrongCategory = new org.jetlinks.pro.patrol.service.statistics.PatrolOverview.Range(day, day, List.of("absent"), List.of(), "project");
        assertEquals(0, service.aggregate(wrongCategory).contextWrite(identity).block().report().total().expected());
        var wrongProject = new org.jetlinks.pro.patrol.service.statistics.PatrolOverview.Range(day, day, List.of(), List.of(), "other");
        assertTrue(service.aggregate(wrongProject).contextWrite(identity).block().points().isEmpty());
        var legacy = tasks.findById(own.getId()).block(); legacy.setId("legacy"); legacy.setPeriodNo(3);
        legacy.setStartTime(legacy.getStartTime() + 1); legacy.setBelongDate(null); tasks.insert(legacy).block();
        assertEquals(4, service.aggregate(range).contextWrite(identity).block().report().total().expected());
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
    void statisticsTotalsDeduplicatePointsAcrossPostsAndWeightRatesWithinAssetScope() {
        db.enableTenantQueries();
        seedTask(2);
        tasks.createUpdate().set(PatrolTaskEntity::getCompletedCount, 1)
                .where(PatrolTaskEntity::getId, "task").execute().block();
        var another = tasks.findById("task").block();
        another.setId("another-post"); another.setRoleId("post2"); another.setRoleName("Second post");
        seedPlan("second-plan", "north");
        another.setPlanId("second-plan"); another.setTaskGroupId("group2");
        another.setRequiredCount(4); another.setCompletedCount(4);
        tasks.insert(another).block();
        seedPlan("south-plan", "south");
        var foreign = tasks.findById("task").block(); foreign.setId("foreign");
        foreign.setTaskGroupId("foreign"); foreign.setPlanId("south-plan"); foreign.setRequiredCount(100);
        tasks.insert(foreign).block();
        var service = new PatrolStatisticsService(tasks);
        var range = new PatrolStatisticsService.Range(LocalDate.now(), LocalDate.now(), List.of());
        var report = service.report(range, true).contextWrite(identity).block();
        assertEquals(2, report.rows().size());
        assertEquals(1, report.total().pointCount());
        assertEquals(6, report.total().expected()); assertEquals(5, report.total().completed());
        assertEquals(83.33, report.total().coverage());
        assertEquals(2, report.total().taskCount()); assertEquals(1, report.total().completedTasks());
        assertEquals(50, report.total().qualification());
        var empty = service.report(new PatrolStatisticsService.Range(range.from(), range.to(), List.of("absent")), false)
                .contextWrite(identity).block();
        assertTrue(empty.rows().isEmpty()); assertEquals(0, empty.total().pointCount());
        assertEquals(0, empty.total().coverage());
    }

    @Test
    void rosterIsScopedToApplicationAndNoScheduleDoesNotRequireOne() {
        seedTask(3);
        var roster = new PatrolRosterEntity();
        roster.setId("other-app");
        roster.setName("Other app");
        roster.setScopeKey("another-app"); roster.setProjectId("project"); roster.setProjectName("Project");
        roster.setRoleId("post");
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
                        null, null, null);
        assertNotNull(workflow.submit(requiredSubmission).contextWrite(identity).block());
        roster.setId("same-app");
        roster.setScopeKey("app"); roster.setProjectId("project"); roster.setProjectName("Project");
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
        assertNotNull(workflow.submit(submission("current-shift")).contextWrite(identity).block());
        assertEquals(1, tasks.findById("required-task").block().getCompletedCount());
        assertEquals(2, tasks.findById("task").block().getCompletedCount());
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
                List.of(new PatrolExecutionEntity.Answer("pressure", "0.5")), null, null, null)).contextWrite(identity).block();
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
                List.of(new PatrolExecutionEntity.Answer("pressure", "0.3")), null, null, null);
        StepVerifier.create(workflow.submit(missing).contextWrite(identity)).expectError().verify();
        var photo = new org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity();
        photo.setId("photo"); photo.setName("Photo"); photo.setScopeKey("other-app"); photo.setProjectId("project"); photo.setProjectName("Project");
        photo.setCreatorId("alice"); photo.setExtension("tenantId", "south");
        photo.setPurpose(org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity.Purpose.evidence);
        photo.setPlatformFileId("platform-file"); photo.setInternalUrl("internal");
        photo.setMediaType("image/png"); photo.setLength(100);
        attachments.insert(photo).block();
        var submission = new PatrolExecutionWorkflow.Submission("task", "image-result",
                List.of(new PatrolExecutionEntity.Answer("pressure", "0.3", "photo")), null, null, null);
        StepVerifier.create(workflow.submit(submission).contextWrite(identity)).expectError().verify();
        photo.setId("other-app-photo"); photo.setScopeKey("another-app"); photo.setProjectId("project"); photo.setProjectName("Project"); photo.setExtension("tenantId", "north");
        attachments.insert(photo).block();
        var otherApp = new PatrolExecutionWorkflow.Submission("task", "other-app-result",
                List.of(new PatrolExecutionEntity.Answer("pressure", "0.3", "other-app-photo")), null, null, null);
        StepVerifier.create(workflow.submit(otherApp).contextWrite(identity)).expectError().verify();
        // scopeKey 不可通过业务编辑移动；测试用新 ID 分别建立合法范围的不同用途资产。
        photo.setId("local-photo"); photo.setScopeKey("app"); photo.setProjectId("project"); photo.setProjectName("Project"); photo.setCreatorId("bob");
        attachments.insert(photo).block();
        var local = new PatrolExecutionWorkflow.Submission("task", "local-result",
                List.of(new PatrolExecutionEntity.Answer("pressure", "0.3", "local-photo")), null, null, null);
        StepVerifier.create(workflow.submit(local).contextWrite(identity)).expectError().verify();
        photo.setId("example-photo"); photo.setCreatorId("alice");
        photo.setPurpose(org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity.Purpose.example);
        attachments.insert(photo).block();
        var example = new PatrolExecutionWorkflow.Submission("task", "example-result",
                List.of(new PatrolExecutionEntity.Answer("pressure", "0.3", "example-photo")), null, null, null);
        StepVerifier.create(workflow.submit(example).contextWrite(identity)).expectError().verify();
        assertEquals(0, tasks.findById("task").block().getCompletedCount());
        assertEquals(0, executions.createQuery().count().block());
        photo.setId("valid-photo"); photo.setPurpose(org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity.Purpose.evidence);
        attachments.insert(photo).block();
        var valid = new PatrolExecutionWorkflow.Submission("task", "valid-result",
                List.of(new PatrolExecutionEntity.Answer("pressure", "0.3", "valid-photo")), null, null, null);
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
        assertEquals(1, tasks.createQuery().count().block());
        assertEquals(1, generationRepository.createQuery().count().block());
        daily.generate(LocalDate.now().plusDays(2)).block();
        assertEquals(1, generationRepository.createQuery().count().block());
    }

    @Test
    void scheduledGenerationUsesPlanSnapshotWithoutInventingTenantContext() {
        var interactiveDirectory = mock(PatrolRoleService.class);
        var scopedPlans = spy(plans);
        doReturn(Mono.deferContextual(context -> {
            assertEquals(null, org.jetlinks.pro.tenant.holder.TenantHolder.readTenantId(context));
            return plans.findById("plan");
        }))
                .when(scopedPlans).findById("plan");
        var scheduled = new PatrolTaskGenerator(scopedPlans, points, interactiveDirectory, standards, tasks,
                new PatrolLevelService(db.repository(org.jetlinks.pro.patrol.entity.PatrolLevelEntity.class,
                        org.jetlinks.pro.patrol.entity.PatrolLevelEntity::new)), categories, generationRepository,
                TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
        scheduled.generateScheduled(plans.findById("plan").block(), LocalDate.now()).block();
        assertEquals(1, tasks.createQuery().count().block());
        assertEquals("巡更员", tasks.createQuery().fetch().blockFirst().getRoleName());
        org.mockito.Mockito.verifyNoInteractions(interactiveDirectory);
    }

    @Test
    void scheduledPlatformApplicationDoesNotInventTenantContext() {
        var plan = plans.findById("plan").block();
        plan.setScopeKey("app"); plan.setProjectId("project"); plan.setProjectName("Project");
        plans.updateById("plan", plan).block();
        var scopedPlans = spy(plans);
        doReturn(Mono.deferContextual(context -> {
            assertEquals(null, org.jetlinks.pro.tenant.holder.TenantHolder.readTenantId(context));
            return plans.findById("plan");
        })).when(scopedPlans).findById("plan");
        var scheduled = new PatrolTaskGenerator(scopedPlans, points, roles, standards, tasks,
                new PatrolLevelService(db.repository(org.jetlinks.pro.patrol.entity.PatrolLevelEntity.class,
                        org.jetlinks.pro.patrol.entity.PatrolLevelEntity::new)), categories, generationRepository,
                TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
        scheduled.generateScheduled(plan, LocalDate.now()).block();
        assertEquals(1, tasks.createQuery().count().block());
    }

    PatrolRosterImportService rosterImporter(PatrolRosterService target) {
        var scope = mock(org.jetlinks.pro.patrol.integration.PatrolScope.class);
        when(scope.current()).thenReturn(Mono.just(new org.jetlinks.pro.patrol.integration.PatrolScope.Current("app")));
        var people = mock(SaasPersonDirectory.class);
        when(people.assertMembers(List.of("alice"))).thenReturn(Mono.empty());
        when(people.query(any())).thenReturn(Mono.just(org.hswebframework.web.api.crud.entity.PagerResult.of(1,
                List.of(new SaasPersonDirectory.Person("alice", "Alice")))));
        var validator = new PatrolConfigurationValidator(null, points, standards, roles, people, scope, attachments, null, null);
        return new PatrolRosterImportService(target, roles, validator, people, scope,
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

    PatrolPointAssignmentService pointAssignments() { return pointAssignments(plans); }

    PatrolPointAssignmentService pointAssignments(PatrolPlanService target) {
        var scope = mock(org.jetlinks.pro.patrol.integration.PatrolScope.class);
        when(scope.current()).thenReturn(Mono.just(new org.jetlinks.pro.patrol.integration.PatrolScope.Current("app")));
        var levels = new PatrolLevelService(db.repository(org.jetlinks.pro.patrol.entity.PatrolLevelEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolLevelEntity::new));
        var validator = new PatrolConfigurationValidator(categories, points, standards, roles,
                mock(SaasPersonDirectory.class), scope, attachments, levels, null);
        return new PatrolPointAssignmentService(points, target, validator, scope,
                TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
    }

    void secondAssignmentPost() {
        var post = new PatrolRole();
        post.setId("post2"); post.setName("Evening"); post.setScopeKey("app"); post.setProjectId("project"); post.setProjectName("Project");
        post.setUserIds(List.of("alice")); post.setExtension("tenantId", "north");
        roles.put(post);
    }

    void ownNewAssignments() {
        // 模拟生产环境的租户实体事件；资产权限仍使用真实 TenantAssetsHolder 验证。
        plans.createQuery().fetch().concatMap(row -> {
            row.setExtension("tenantId", "north"); return plans.updateById(row.getId(), row);
        }).then().block();
    }

    @Test
    void assigningPostsRetainsRulesAndNewPostsRemainUnconfigured() {
        secondAssignmentPost();
        var original = plans.findById("plan").block();
        pointAssignments().assign(List.of("point"), List.of("post", "post2")).contextWrite(identity).block();
        var retained = plans.findById("plan").block();
        assertEquals(original.getCreatorName(), retained.getCreatorName());
        assertEquals("Alice", retained.getModifierName());
        assertEquals("Alice", points.findById("point").block().getModifierName());
        assertEquals(original.getRule(), retained.getRule());
        assertEquals(original.getEffectiveTo(), retained.getEffectiveTo());
        assertEquals("standard", retained.getStandardId());
        var added = plans.createQuery().where(PatrolPlanEntity::getRoleId, "post2").fetch().blockFirst();
        org.junit.jupiter.api.Assertions.assertNull(added.getStandardId());
        org.junit.jupiter.api.Assertions.assertNull(added.getRule());
        assertTrue(added.isEnabled());
        assertEquals("Alice", added.getCreatorName()); assertEquals("Alice", added.getModifierName());
        ownNewAssignments();
        assertEquals(2, pointAssignments().detail(List.of("point")).contextWrite(identity).block().get(0).assignments().size());
        new PatrolDailyGeneration(plans, generator).generate(LocalDate.now()).block();
        assertEquals(1, tasks.createQuery().count().block());
        assertEquals(1, generationRepository.createQuery().count().block());
    }

    void assignmentLevel() {
        var level = new org.jetlinks.pro.patrol.entity.PatrolLevelEntity();
        level.setId("level"); level.setName("Daily rule"); level.setScopeKey("app");
        level.setProjectId("project"); level.setProjectName("Project"); level.setExtension("tenantId", "north");
        level.setRule(plans.findById("plan").block().getRule());
        new PatrolLevelService(db.repository(org.jetlinks.pro.patrol.entity.PatrolLevelEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolLevelEntity::new)).insert(level).block();
    }

    @Test
    void combinedConfigurationCreatesNewRoleAndRulesWithoutPreassigning() {
        secondAssignmentPost(); assignmentLevel();
        var original = generator.generate("plan", LocalDate.now()).contextWrite(identity).collectList().block();
        pointAssignments().configureAll(List.of("point"), List.of(
                new PatrolPointAssignmentService.Rule("post", "level", "standard"),
                new PatrolPointAssignmentService.Rule("post2", "level", "standard")), "category")
                .contextWrite(identity).block();
        var rows = plans.createQuery().where(PatrolPlanEntity::isEnabled, true).fetch().collectList().block();
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(row -> "level".equals(row.getLevelId()) && "standard".equals(row.getStandardId())));
        assertEquals("plan", rows.stream().filter(row -> "post".equals(row.getRoleId())).findFirst().orElseThrow().getId());
        assertEquals(original.get(0).getId(), tasks.createQuery().fetch().blockFirst().getId());
        assertEquals(original.get(0).getRuleVersion(), tasks.createQuery().fetch().blockFirst().getRuleVersion());
        assertEquals(1, generationRepository.createQuery().count().block());
    }

    @Test
    void combinedConfigurationCanReplaceRolesForUnassignedPoint() {
        secondAssignmentPost(); assignmentLevel();
        var point = new PatrolPointEntity(); point.setId("point2"); point.setName("Pump");
        point.setCode("P002"); point.setCategoryId("category"); point.setScopeKey("app");
        point.setProjectId("project"); point.setProjectName("Project"); point.setExtension("tenantId", "north");
        points.insert(point).block();
        pointAssignments().configureAll(List.of("point", "point2"),
                List.of(new PatrolPointAssignmentService.Rule("post2", "level", "standard")), "category")
                .contextWrite(identity).block();
        assertTrue(!plans.findById("plan").block().isEnabled());
        var rows = plans.createQuery().where(PatrolPlanEntity::isEnabled, true).fetch().collectList().block();
        assertEquals(2, rows.size());
        assertEquals(Set.of("point", "point2"), rows.stream().map(PatrolPlanEntity::getPointId).collect(java.util.stream.Collectors.toSet()));
        assertTrue(rows.stream().allMatch(row -> "post2".equals(row.getRoleId()) && "level".equals(row.getLevelId())));
    }

    @Test
    void combinedConfigurationSavesConfiguredAndUnconfiguredRolesTogether() {
        secondAssignmentPost(); assignmentLevel();
        pointAssignments().configureAll(List.of("point"), List.of(
                new PatrolPointAssignmentService.Rule("post", "level", "standard"),
                new PatrolPointAssignmentService.Rule("post2", " ", null)), "category")
                .contextWrite(identity).block();
        assertEquals("level", plans.findById("plan").block().getLevelId());
        var added = plans.createQuery().where(PatrolPlanEntity::getRoleId, "post2").fetch().blockFirst();
        assertTrue(added.isEnabled());
        org.junit.jupiter.api.Assertions.assertNull(added.getStandardId());
        org.junit.jupiter.api.Assertions.assertNull(added.getLevelId());
        org.junit.jupiter.api.Assertions.assertNull(added.getRule());
        new PatrolDailyGeneration(plans, generator).generate(LocalDate.now()).block();
        assertEquals(1, tasks.createQuery().count().block());
    }

    @Test
    void clearingBothRuleFieldsPersistsEmptyConfigurationAndKeepsHistory() {
        assignmentLevel();
        pointAssignments().configureAll(List.of("point"), List.of(
                new PatrolPointAssignmentService.Rule("post", "level", "standard")), "category")
                .contextWrite(identity).block();
        var day = LocalDate.now();
        var original = generator.generate("plan", day).contextWrite(identity).collectList().block();
        pointAssignments().configureAll(List.of("point"), List.of(
                new PatrolPointAssignmentService.Rule("post", null, "")), "category")
                .contextWrite(identity).block();
        var plan = plans.findById("plan").block();
        assertTrue(plan.isEnabled());
        org.junit.jupiter.api.Assertions.assertNull(plan.getLevelId());
        org.junit.jupiter.api.Assertions.assertNull(plan.getStandardId());
        org.junit.jupiter.api.Assertions.assertNull(plan.getRule());
        new PatrolDailyGeneration(plans, generator).generate(day.plusDays(1)).block();
        assertEquals(1, tasks.createQuery().count().block());
        assertEquals(original.get(0).getRuleVersion(), tasks.createQuery().fetch().blockFirst().getRuleVersion());
    }

    @Test
    void incompleteCombinedRuleRejectsWithoutWritingAnyPointOrPlan() {
        assignmentLevel();
        var before = points.findById("point").block().getModifyTime();
        for (var rule : List.of(new PatrolPointAssignmentService.Rule("post", "level", null),
                new PatrolPointAssignmentService.Rule("post", null, "standard"))) {
            StepVerifier.create(pointAssignments().configureAll(List.of("point"), List.of(rule), "category")
                    .contextWrite(identity)).expectErrorMessage("error.patrol.assignment.incomplete").verify();
        }
        assertEquals(before, points.findById("point").block().getModifyTime());
        org.junit.jupiter.api.Assertions.assertNull(plans.findById("plan").block().getLevelId());
        assertEquals("standard", plans.findById("plan").block().getStandardId());
    }

    @Test
    void combinedConfigurationInsertFailureRollsBackRoleRemovalAndRules() {
        secondAssignmentPost(); assignmentLevel();
        var before = points.findById("point").block().getModifyTime();
        var failing = spy(plans);
        doReturn(Mono.error(new IllegalStateException("insert failed"))).when(failing).insertBatch(any());
        StepVerifier.create(pointAssignments(failing).configureAll(List.of("point"),
                List.of(new PatrolPointAssignmentService.Rule("post2", "level", "standard")), "category")
                .contextWrite(identity)).expectErrorMessage("insert failed").verify();
        assertEquals(before, points.findById("point").block().getModifyTime());
        assertTrue(plans.findById("plan").block().isEnabled());
        org.junit.jupiter.api.Assertions.assertNull(plans.findById("plan").block().getLevelId());
        assertEquals(1, plans.createQuery().count().block());
    }

    @Test
    void combinedConfigurationRejectsForeignPointWithoutChangingRoles() {
        secondAssignmentPost(); assignmentLevel();
        var point = points.findById("point").block();
        point.setExtension("tenantId", "south"); points.updateById("point", point).block();
        StepVerifier.create(pointAssignments().configureAll(List.of("point"),
                List.of(new PatrolPointAssignmentService.Rule("post2", "level", "standard")), "category")
                .contextWrite(identity)).expectError().verify();
        assertEquals(1, plans.createQuery().count().block());
        assertTrue(plans.findById("plan").block().isEnabled());
    }

    @Test
    void ruleConfigurationHasNoArtificialExpiryAndPreservesGeneratedHistory() {
        var day = LocalDate.now();
        var original = generator.generate("plan", day).contextWrite(identity).collectList().block();
        var level = new org.jetlinks.pro.patrol.entity.PatrolLevelEntity();
        level.setId("level"); level.setName("New rule"); level.setScopeKey("app"); level.setProjectId("project"); level.setProjectName("Project");
        level.setExtension("tenantId", "north");
        level.setRule(new PatrolRule(Map.of(), Map.of(day, List.of(new PatrolRule.Window(480, 720, 60, 2)),
                day.plusYears(3), List.of(new PatrolRule.Window(480, 720, 60, 2))), 0));
        new PatrolLevelService(db.repository(org.jetlinks.pro.patrol.entity.PatrolLevelEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolLevelEntity::new)).insert(level).block();
        pointAssignments().configure(List.of("point"), List.of(new PatrolPointAssignmentService.Rule("post", "level", "standard")))
                .contextWrite(identity).block();
        var updated = plans.findById("plan").block();
        org.junit.jupiter.api.Assertions.assertNull(updated.getEffectiveFrom());
        org.junit.jupiter.api.Assertions.assertNull(updated.getEffectiveTo());
        assertEquals("level", updated.getLevelId());
        assertEquals(original.get(0).getId(), generator.generate("plan", day).contextWrite(identity).blockFirst().getId());
        assertEquals(original.get(0).getRequiredCount(), tasks.findById(original.get(0).getId()).block().getRequiredCount());
        new PatrolDailyGeneration(plans, generator).generate(day.plusYears(3)).block();
        assertEquals(2, tasks.createQuery().count().block());
        assertEquals(8, tasks.createQuery().where(PatrolTaskEntity::getBusinessDate, day.plusYears(3).toString()).fetch().blockFirst().getRequiredCount());
    }

    @Test
    void removingAndReselectingPostKeepsItsIdentityAndTasks() {
        secondAssignmentPost();
        var original = generator.generate("plan", LocalDate.now()).contextWrite(identity).collectList().block();
        var service = pointAssignments();
        service.assign(List.of("point"), List.of("post2")).contextWrite(identity).block();
        assertTrue(!plans.findById("plan").block().isEnabled());
        assertEquals(original.get(0).getId(), tasks.createQuery().fetch().blockFirst().getId());
        ownNewAssignments();
        var selected = service.detail(List.of("point")).contextWrite(identity).block().get(0).assignments();
        assertEquals(1, selected.size()); assertEquals("post2", selected.get(0).getRoleId());
        service.assign(List.of("point"), List.of("post")).contextWrite(identity).block();
        assertTrue(plans.findById("plan").block().isEnabled());
        assertEquals(2, plans.createQuery().count().block());
        assertEquals("standard", plans.findById("plan").block().getStandardId());
        assertEquals(1, tasks.createQuery().count().block());
        assertEquals(1, generationRepository.createQuery().count().block());
    }

    @Test
    void differingPostsRejectWholeBatchAndRollbackPointAudit() {
        var another = new PatrolPointEntity(); another.setId("point2"); another.setName("Pump");
        another.setCode("P002"); another.setCategoryId("category"); another.setScopeKey("app"); another.setProjectId("project"); another.setProjectName("Project");
        another.setExtension("tenantId", "north"); points.insert(another).block();
        var before = points.findById("point").block().getModifyTime();
        StepVerifier.create(pointAssignments().configure(List.of("point", "point2"),
                List.of(new PatrolPointAssignmentService.Rule("post", "level", "standard"))).contextWrite(identity))
                .expectErrorMatches(error -> error instanceof org.hswebframework.web.exception.BusinessException
                        && error.getMessage().contains("posts_differ")).verify();
        assertEquals(before, points.findById("point").block().getModifyTime());
        assertEquals(1, plans.createQuery().count().block());
        org.junit.jupiter.api.Assertions.assertNull(plans.findById("plan").block().getLevelId());
    }

    @Test
    void foreignPointOrPlanRejectsAssignmentWithoutPartialWrites() {
        var service = pointAssignments();
        var point = new PatrolPointEntity(); point.setId("foreign"); point.setName("Foreign");
        point.setCode("F001"); point.setCategoryId("category"); point.setScopeKey("other-app"); point.setProjectId("project"); point.setProjectName("Project");
        point.setExtension("tenantId", "south"); points.insert(point).block();
        var before = points.findById("point").block().getModifyTime();
        StepVerifier.create(service.assign(List.of("point", "foreign"), List.of("post")).contextWrite(identity)).expectError().verify();
        assertEquals(before, points.findById("point").block().getModifyTime());
        var plan = plans.findById("plan").block(); plan.setExtension("tenantId", "south"); plans.updateById("plan", plan).block();
        StepVerifier.create(service.assign(List.of("point"), List.of("post")).contextWrite(identity)).expectError().verify();
        assertEquals(before, points.findById("point").block().getModifyTime());
        assertEquals(1, plans.createQuery().count().block());
    }

    @Test
    void assignmentInsertFailureRollsBackExistingPlanAndPointChanges() {
        secondAssignmentPost();
        var before = points.findById("point").block().getModifyTime();
        var failing = spy(plans);
        doReturn(Mono.error(new IllegalStateException("insert failed"))).when(failing).insertBatch(any());
        StepVerifier.create(pointAssignments(failing).assign(List.of("point"), List.of("post", "post2"))
                .contextWrite(identity)).expectErrorMessage("insert failed").verify();
        assertEquals(before, points.findById("point").block().getModifyTime());
        assertEquals("Night patrol", plans.findById("plan").block().getName());
        assertEquals(1, plans.createQuery().count().block());
    }

    PatrolPointTransferService pointTransfer(PatrolPointService target) {
        var scope = mock(org.jetlinks.pro.patrol.integration.PatrolScope.class);
        when(scope.current()).thenReturn(Mono.just(new org.jetlinks.pro.patrol.integration.PatrolScope.Current("app")));
        return new PatrolPointTransferService(target, categories,
                new PatrolTagService(db.repository(org.jetlinks.pro.patrol.entity.PatrolTagEntity.class,
                        org.jetlinks.pro.patrol.entity.PatrolTagEntity::new)), roles, plans, scope,
                TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
    }

    @Test
    void pointImportValidatesCategoryAccessAndNamesBeforeAnyWrite() throws Exception {
        db.enableTenantQueries();
        var service = pointTransfer(points);
        assertTrue(service.preview(PatrolPointWorkbookTest.workbook("category", "Building", 1))
                .contextWrite(identity).block().valid());
        var mismatch = service.preview(PatrolPointWorkbookTest.workbook("category", "Wrong", 1))
                .contextWrite(identity).block();
        assertEquals(List.of("categoryMismatch"), mismatch.rows().get(0).errors());
        var foreign = new PatrolCategoryEntity(); foreign.setId("foreign"); foreign.setName("Hidden");
        foreign.setScopeKey("app"); foreign.setProjectId("project"); foreign.setProjectName("Project"); foreign.setExtension("tenantId", "south"); categories.insert(foreign).block();
        assertEquals(List.of("categoryUnavailable"), service.preview(PatrolPointWorkbookTest.workbook("foreign", "Hidden", 1))
                .contextWrite(identity).block().rows().get(0).errors());
        StepVerifier.create(service.importPoints(PatrolPointWorkbookTest.workbook("foreign", "Hidden", 1)).contextWrite(identity))
                .expectError().verify();
        assertEquals(1, points.createQuery().count().block());
    }

    @Test
    void pointImportRejectsOtherProjectsAndDisabledCategoriesWithoutWriting() throws Exception {
        db.enableTenantQueries();
        var category = categories.findById("category").block();
        category.setProjectId("other-project"); categories.updateById("category", category).block();
        var bytes = PatrolPointWorkbookTest.workbook("category", "Building", 1);
        var service = pointTransfer(points);
        assertEquals(List.of("categoryProjectMismatch"), service.preview(bytes).contextWrite(identity).block().rows().get(0).errors());
        StepVerifier.create(service.importPoints(bytes).contextWrite(identity)).expectError().verify();
        category.setProjectId("project"); category.setEnabled(false); categories.updateById("category", category).block();
        assertEquals(List.of("categoryDisabled"), service.preview(bytes).contextWrite(identity).block().rows().get(0).errors());
        StepVerifier.create(service.importPoints(bytes).contextWrite(identity)).expectError().verify();
        assertEquals(1, points.createQuery().count().block());
    }

    @Test
    void pointImportRequiresSelectedProjectAndRejectsRepeatedWorkbookAtomically() throws Exception {
        db.enableTenantQueries();
        var service = pointTransfer(points);
        var context = org.jetlinks.pro.patrol.integration.ProjectTestContexts.project("app");
        var noSelection = identity.put(org.jetlinks.pro.patrol.integration.PatrolProjectContext.class,
                new org.jetlinks.pro.patrol.integration.PatrolProjectContext(context.tree(), null, "app"));
        var bytes = PatrolPointWorkbookTest.workbook("category", "Building", 2);
        StepVerifier.create(service.preview(bytes).contextWrite(noSelection))
                .expectErrorMessage("error.patrol.project_required").verify();
        StepVerifier.create(service.template().contextWrite(noSelection))
                .expectErrorMessage("error.patrol.project_required").verify();
        assertEquals(2, service.importPoints(bytes).contextWrite(identity).block());
        // 与指派流程相同，补上该测试夹具未启动的租户实体事件，仍由真实 holder 校验读取权限。
        points.createQuery().not("id", "point").fetch().concatMap(row -> {
            row.setExtension("tenantId", "north"); return points.updateById(row.getId(), row);
        }).then().block();
        var preview = service.preview(PatrolPointWorkbookTest.workbook("category", "Building", 3)).contextWrite(identity).block();
        assertEquals(List.of("existingPoint"), preview.rows().get(0).errors());
        assertEquals(List.of("existingPoint"), preview.rows().get(1).errors());
        assertTrue(preview.rows().get(2).errors().isEmpty());
        StepVerifier.create(service.importPoints(PatrolPointWorkbookTest.workbook("category", "Building", 3))
                .contextWrite(identity)).expectError().verify();
        assertEquals(3, points.createQuery().count().block());
        // 同名但位置不同不应误拦截。
        var imported = points.createQuery().where("name", "Point-0").fetchOne().block();
        imported.setLocation("Floor 2"); points.updateById(imported.getId(), imported).block();
        assertTrue(service.preview(PatrolPointWorkbookTest.workbook("category", "Building", 1)).contextWrite(identity).block().valid());
    }

    @Test
    void pointTemplateShowsAuthorizedProjectCategoryPathsOnly() throws Exception {
        db.enableTenantQueries();
        var child = new PatrolCategoryEntity(); child.setId("child"); child.setName("Room"); child.setParentId("category");
        child.setProjectId("project"); child.setProjectName("Project"); child.setScopeKey("app");
        child.setExtension("tenantId", "north"); categories.insert(child).block();
        var hidden = new PatrolCategoryEntity(); hidden.setId("hidden"); hidden.setName("Hidden");
        hidden.setProjectId("project"); hidden.setScopeKey("app"); hidden.setExtension("tenantId", "south"); categories.insert(hidden).block();
        var other = new PatrolCategoryEntity(); other.setId("other"); other.setName("Other project");
        other.setProjectId("other-project"); other.setScopeKey("app"); other.setExtension("tenantId", "north"); categories.insert(other).block();
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(
                pointTransfer(points).template().contextWrite(identity).block()))) {
            var sheet = book.getSheet("分类参考");
            assertEquals(2, sheet.getLastRowNum());
            assertEquals("child", sheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals("Building / Room", sheet.getRow(2).getCell(2).getStringCellValue());
            assertEquals("Project", sheet.getRow(2).getCell(3).getStringCellValue());
        }
    }

    @Test
    void fullThousandPointImportCreatesUniqueCodesAuditAndNoAssignments() throws Exception {
        db.enableTenantQueries();
        var service = pointTransfer(points);
        long started = System.nanoTime();
        assertEquals(1000, service.importPoints(PatrolPointWorkbookTest.workbook("category", "Building", 1000))
                .contextWrite(identity).block(Duration.ofSeconds(30)));
        var imported = points.createQuery().not("id", "point").fetch().collectList().block();
        assertEquals(1000, imported.size());
        assertEquals(1000, imported.stream().map(PatrolPointEntity::getCode).distinct().count());
        assertTrue(imported.stream().allMatch(row -> row.getScopeKey().equals("app") && row.getCreatorId().equals("alice")
                && "Alice".equals(row.getCreatorName()) && "Alice".equals(row.getModifierName())
                && row.getTagIds().isEmpty() && row.getNfcCode().isEmpty() && row.isEnabled()));
        assertEquals(1, plans.createQuery().count().block());
        System.out.println("1000-point import elapsed ms=" + (System.nanoTime() - started) / 1_000_000);
    }

    @Test
    void pointImportFailureRollsBackEarlierBatchRows() throws Exception {
        db.enableTenantQueries();
        var failing = spy(points);
        doReturn(points.insertBatch(Mono.just(List.of(newPointForRollback())))
                .then(Mono.<Integer>error(new IllegalStateException("insert failed"))))
                .when(failing).insertBatch(any(org.reactivestreams.Publisher.class));
        StepVerifier.create(pointTransfer(failing).importPoints(PatrolPointWorkbookTest.workbook("category", "Building", 1))
                .contextWrite(identity)).expectErrorMessage("insert failed").verify();
        assertEquals(1, points.createQuery().count().block());
    }

    private PatrolPointEntity newPointForRollback() {
        var row = new PatrolPointEntity(); row.setId("rollback-point"); row.setName("Rollback"); row.setCode("ROLLBACK");
        row.setCategoryId("category"); row.setScopeKey("app"); row.setProjectId("project"); row.setProjectName("Project"); return row;
    }

    @Test
    void pointExportKeepsFiltersIgnoresPageAndHidesUnauthorizedAssets() throws Exception {
        db.enableTenantQueries();
        var foreign = new PatrolPointEntity(); foreign.setId("foreign"); foreign.setName("Gate"); foreign.setCode("HIDDEN");
        foreign.setCategoryId("category"); foreign.setScopeKey("app"); foreign.setProjectId("project"); foreign.setProjectName("Project"); foreign.setExtension("tenantId", "south"); points.insert(foreign).block();
        var query = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery().is("name", "Gate").getParam();
        query.setPageIndex(8); query.setPageSize(1);
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(
                pointTransfer(points).export(query).contextWrite(identity).block()))) {
            assertEquals(1, book.getSheetAt(0).getLastRowNum());
            assertEquals("P001", book.getSheetAt(0).getRow(1).getCell(1).getStringCellValue());
            assertEquals("Building", book.getSheetAt(0).getRow(1).getCell(2).getStringCellValue());
            assertEquals("Security", book.getSheetAt(0).getRow(1).getCell(3).getStringCellValue());
        }
        var empty = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery().is("name", "Other").getParam();
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(
                pointTransfer(points).export(empty).contextWrite(identity).block()))) {
            assertEquals(0, book.getSheetAt(0).getLastRowNum());
        }
    }

    PatrolConfigTransferService configTransfer() {
        var scope = mock(org.jetlinks.pro.patrol.integration.PatrolScope.class);
        when(scope.current()).thenReturn(Mono.just(new org.jetlinks.pro.patrol.integration.PatrolScope.Current("app")));
        return new PatrolConfigTransferService(scope);
    }

    @Test
    void configExportResetsPageKeepsFiltersAndEnforcesAssetsAndCurrentApplication() throws Exception {
        db.enableTenantQueries();
        var tags = new PatrolTagService(db.repository(org.jetlinks.pro.patrol.entity.PatrolTagEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolTagEntity::new));
        for (int i = 0; i < 5; i++) {
            var tag = new org.jetlinks.pro.patrol.entity.PatrolTagEntity(); tag.setId("tag" + i);
            tag.setName(i == 4 ? "Other" : "Match"); tag.setDescription("Row" + i);
            tag.setScopeKey(i == 3 ? "another-app" : "app"); tag.setProjectId("project"); tag.setProjectName("Project");
            tag.setExtension("tenantId", i == 2 ? "south" : "north"); tags.insert(tag).block();
        }
        var query = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery().is("name", "Match").getParam();
        query.setPageIndex(99); query.setPageSize(1);
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(
                configTransfer().export(tags, org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolTag, query).contextWrite(identity).block()))) {
            assertEquals(2, book.getSheetAt(0).getLastRowNum());
            var descriptions = java.util.stream.IntStream.rangeClosed(1, 2)
                    .mapToObj(i -> book.getSheetAt(0).getRow(i).getCell(3).getStringCellValue()).collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of("Row0", "Row1"), descriptions);
        }
        var denied = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery().is("id", "tag2").getParam();
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(
                configTransfer().export(tags, org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolTag, denied).contextWrite(identity).block()))) {
            assertEquals(0, book.getSheetAt(0).getLastRowNum());
        }
        var standardsQuery = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery().is("id", "standard").getParam();
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(
                configTransfer().export(standards, org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolStandard, standardsQuery).contextWrite(identity).block()))) {
            assertEquals(1, book.getSheetAt(0).getLastRowNum()); assertNotNull(book.getSheet("检查项目"));
        }
    }

    @Test
    void configExportRejectsTooManyResultsInsteadOfReturningTruncatedWorkbook() {
        db.enableTenantQueries();
        var tags = new PatrolTagService(db.repository(org.jetlinks.pro.patrol.entity.PatrolTagEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolTagEntity::new));
        var rows = java.util.stream.IntStream.rangeClosed(0, PatrolConfigTransferService.LIMIT).mapToObj(index -> {
            var tag = new org.jetlinks.pro.patrol.entity.PatrolTagEntity(); tag.setId("tag" + index); tag.setName("Tag" + index);
            tag.setScopeKey("app"); tag.setProjectId("project"); tag.setProjectName("Project"); tag.setExtension("tenantId", "north"); return tag;
        }).toList();
        tags.insertBatch(Flux.fromIterable(rows).buffer(200)).block();
        StepVerifier.create(configTransfer().export(tags, org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolTag,
                new org.hswebframework.web.api.crud.entity.QueryParamEntity()).contextWrite(identity))
                .expectErrorMatches(error -> error.getMessage().equals("error.patrol.config.export_limit")).verify();
    }

    PatrolConfigHistoryService configHistory() {
        var scope = mock(org.jetlinks.pro.patrol.integration.PatrolScope.class);
        when(scope.current()).thenReturn(Mono.just(new org.jetlinks.pro.patrol.integration.PatrolScope.Current("app")));
        return new PatrolConfigHistoryService(db.repository(org.jetlinks.pro.patrol.entity.PatrolConfigHistoryEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolConfigHistoryEntity::new), scope,
                TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
    }

    org.jetlinks.pro.patrol.entity.PatrolTagEntity historyTag(String id, String name) {
        var tag = new org.jetlinks.pro.patrol.entity.PatrolTagEntity();
        tag.setId(id); tag.setName(name); tag.setScopeKey("app"); tag.setProjectId("project"); tag.setProjectName("Project"); tag.setExtension("tenantId", "north");
        tag.setCreatorId("alice"); tag.setModifierId("alice"); tag.setCreateTime(100L); tag.setModifyTime(100L);
        return tag;
    }

    @Test
    void configHistoryPreservesActorBusinessSnapshotsAndRejectsStaleWriter() {
        db.enableTenantQueries();
        var tags = new PatrolTagService(db.repository(org.jetlinks.pro.patrol.entity.PatrolTagEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolTagEntity::new));
        var history = configHistory(); var type = org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolTag;
        var old = historyTag("tag", "Before"); old.setDescription("Before note"); tags.insert(old).block();
        var next = historyTag("tag", "After"); next.setDescription(null); next.setEnabled(false);
        history.update(tags, type, old, next).contextWrite(identity).block();
        var page = history.history(type, "tag", new org.hswebframework.web.api.crud.entity.QueryParamEntity())
                .contextWrite(identity).block();
        assertEquals(1, page.getTotal()); var event = page.getData().get(0);
        assertEquals("update", event.getAction()); assertEquals("Alice", event.getActorName());
        assertEquals("Before", event.getBeforeValue().get("name")); assertEquals("After", event.getAfterValue().get("name"));
        assertEquals("Before note", event.getBeforeValue().get("description"));
        assertEquals(false, event.getAfterValue().get("enabled")); assertEquals(101L, event.getCreateTime());
        assertTrue(!event.getAfterValue().containsKey("extensions") && !event.getAfterValue().containsKey("scopeKey"));
        assertEquals(null, tags.findById("tag").block().getDescription());
        StepVerifier.create(history.update(tags, type, old, historyTag("tag", "Stale")).contextWrite(identity))
                .expectErrorMatches(error -> error.getMessage().equals("error.patrol.config.changed")).verify();
        assertEquals("After", tags.findById("tag").block().getName()); assertEquals(1, history.createQuery().count().block());
    }

    @Test
    void configHistoryAppendFailureRollsBackConfigurationAndVersion() {
        var tags = new PatrolTagService(db.repository(org.jetlinks.pro.patrol.entity.PatrolTagEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolTagEntity::new));
        var history = spy(configHistory());
        doReturn(Mono.error(new IllegalStateException("history write failed"))).when(history)
                .insert(any(org.jetlinks.pro.patrol.entity.PatrolConfigHistoryEntity.class));
        var old = historyTag("tag", "Before"); tags.insert(old).block();
        StepVerifier.create(history.update(tags, org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolTag,
                old, historyTag("tag", "After")).contextWrite(identity)).expectError().verify();
        var saved = tags.findById("tag").block(); assertEquals("Before", saved.getName()); assertEquals(100L, saved.getModifyTime());
        assertEquals(0, history.createQuery().count().block());
    }

    @Test
    void configHistoryUsesReadAssetAndApplicationBeforeStablePagination() {
        db.enableTenantQueries(); var history = configHistory(); var type = org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolTag;
        var tags = new PatrolTagService(db.repository(org.jetlinks.pro.patrol.entity.PatrolTagEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolTagEntity::new));
        var tag = historyTag("tag", "Tag"); tags.insert(tag).block();
        for (int i = 0; i < 55; i++) { tag.setModifyTime((long) i); history.created(type, tag).contextWrite(identity).block(); }
        tag.setScopeKey("another-app"); tag.setProjectId("project"); tag.setProjectName("Project"); history.created(type, tag).contextWrite(identity).block();
        var page0 = new org.hswebframework.web.api.crud.entity.QueryParamEntity(); page0.setPageSize(500); page0.setPaging(false);
        var first = history.history(type, "tag", page0).contextWrite(identity).block();
        assertEquals(55, first.getTotal()); assertEquals(50, first.getData().size()); assertEquals(54L, first.getData().get(0).getCreateTime());
        var page1 = new org.hswebframework.web.api.crud.entity.QueryParamEntity(); page1.setPageIndex(1); page1.setPageSize(50);
        var last = history.history(type, "tag", page1).contextWrite(identity).block(); assertEquals(5, last.getData().size());
        var foreign = historyTag("foreign", "Foreign"); foreign.setExtension("tenantId", "south"); tags.insert(foreign).block();
        StepVerifier.create(history.history(type, "foreign", new org.hswebframework.web.api.crud.entity.QueryParamEntity()).contextWrite(identity))
                .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
        // 调用方 OR 条件不能请求其他对象的历史。
        var injected = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery().is("entityId", "foreign").or("entityId", "tag").getParam();
        assertEquals(55, history.history(type, "tag", injected).contextWrite(identity).block().getTotal());
    }

    @Test
    void levelHistoryPersistsAdvancedOnlyRuleAsSnapshotAndDoesNotBackfillCreation() {
        db.enableTenantQueries(); var history = configHistory();
        var levels = new PatrolLevelService(db.repository(org.jetlinks.pro.patrol.entity.PatrolLevelEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolLevelEntity::new));
        var before = new org.jetlinks.pro.patrol.entity.PatrolLevelEntity(); before.setId("level"); before.setName("Level");
        before.setScopeKey("app"); before.setProjectId("project"); before.setProjectName("Project"); before.setExtension("tenantId", "north");
        before.setRule(new PatrolRule(Map.of(1, List.of(new PatrolRule.Window(480, 720, 60, 1))), Map.of(), 15));
        levels.insert(before).block();
        var after = new org.jetlinks.pro.patrol.entity.PatrolLevelEntity(); after.setId("level"); after.setName("Level updated");
        after.setScopeKey("app"); after.setProjectId("project"); after.setProjectName("Project"); after.setModifierId("alice"); after.setModifyTime(100L);
        after.setRule(new PatrolRule(Map.of(), Map.of(LocalDate.of(2026, 11, 5), List.of()), 0));
        history.update(levels, org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolLevel, before, after).contextWrite(identity).block();
        var rows = history.history(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolLevel, "level",
                new org.hswebframework.web.api.crud.entity.QueryParamEntity()).contextWrite(identity).block().getData();
        assertEquals(1, rows.size()); assertEquals("update", rows.get(0).getAction());
        var oldRule = (Map<?, ?>) rows.get(0).getBeforeValue().get("rule");
        var newRule = (Map<?, ?>) rows.get(0).getAfterValue().get("rule");
        assertEquals(1, ((Map<?, ?>) oldRule.get("weekly")).size());
        assertEquals(Map.of("2026-11-05", List.of()), newRule.get("dates"));
        assertTrue(levels.findById("level").block().getRule().weekly().isEmpty());
    }

    @Test
    void levelControllerCreationRecordsTrustedActorAndWhitelistedSnapshotInSameTransaction() {
        var levels = new PatrolLevelService(db.repository(org.jetlinks.pro.patrol.entity.PatrolLevelEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolLevelEntity::new));
        var history = configHistory();
        var validator = mock(PatrolConfigurationValidator.class);
        when(validator.validate(any())).thenAnswer(invocation -> {
            org.jetlinks.pro.patrol.entity.PatrolConfigEntity entity = invocation.getArgument(0);
            entity.setScopeKey("app"); entity.setProjectId("project"); entity.setProjectName("Project"); return Mono.empty();
        }); // 此测试只替换既有配置校验，写入口、绑定和历史 hook 均使用生产实现。
        var controller = new org.jetlinks.pro.patrol.web.PatrolLevelController(levels, validator,
                configTransfer(), history, configDeletion());
        var level = new org.jetlinks.pro.patrol.entity.PatrolLevelEntity(); level.setName("New level");
        level.setId("client-id"); level.setCreatorId("untrusted"); level.setModifierId("untrusted");
        level.setRule(new PatrolRule(Map.of(), Map.of(LocalDate.of(2026, 11, 5), List.of()), 0));
        level.setExtension("untrusted", "private");
        var saved = controller.create(level).as(TransactionalOperator.create(new R2dbcTransactionManager(db.connections))::transactional)
                .contextWrite(identity).block();
        assertTrue(!saved.getId().equals("client-id")); assertEquals("alice", saved.getCreatorId());
        var events = history.createQuery().fetch().collectList().block(); assertEquals(1, events.size());
        var event = events.get(0); assertEquals(saved.getId(), event.getEntityId()); assertEquals("create", event.getAction());
        assertEquals("Alice", event.getActorName()); assertEquals(null, event.getBeforeValue());
        assertEquals(Set.of("name", "enabled", "description", "rule"), event.getAfterValue().keySet());
        // 日志追加失败时，包括配置插入在内的创建事务回滚。
        var failing = spy(history);
        doReturn(Mono.error(new IllegalStateException("history write failed"))).when(failing)
                .insert(any(org.jetlinks.pro.patrol.entity.PatrolConfigHistoryEntity.class));
        var rejected = new org.jetlinks.pro.patrol.entity.PatrolLevelEntity(); rejected.setName("Rejected"); rejected.setRule(level.getRule());
        var broken = new org.jetlinks.pro.patrol.web.PatrolLevelController(levels, validator, configTransfer(), failing, configDeletion());
        StepVerifier.create(broken.create(rejected).as(TransactionalOperator.create(new R2dbcTransactionManager(db.connections))::transactional)
                .contextWrite(identity)).expectError().verify();
        assertEquals(1, levels.createQuery().count().block()); assertEquals(1, history.createQuery().count().block());
    }

    @Test
    void configurationAuditNamesUseAuthenticatedSnapshotsAndPreserveTheOriginalCreator() {
        var levels = new PatrolLevelService(db.repository(org.jetlinks.pro.patrol.entity.PatrolLevelEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolLevelEntity::new));
        var warnings = new PatrolWarningService(db.repository(org.jetlinks.pro.patrol.entity.PatrolWarningEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolWarningEntity::new));
        var validator = mock(PatrolConfigurationValidator.class);
        when(validator.validate(any())).thenAnswer(invocation -> {
            org.jetlinks.pro.patrol.entity.PatrolConfigEntity entity = invocation.getArgument(0);
            entity.setScopeKey("app"); entity.setProjectId("project"); entity.setProjectName("Project"); entity.setExtension("tenantId", "north"); return Mono.empty();
        });
        when(validator.validate(any(), any())).thenAnswer(invocation -> {
            org.jetlinks.pro.patrol.entity.PatrolConfigEntity entity = invocation.getArgument(0);
            entity.setScopeKey("app"); entity.setProjectId("project"); entity.setProjectName("Project"); entity.setExtension("tenantId", "north"); return Mono.empty();
        });
        var transaction = TransactionalOperator.create(new R2dbcTransactionManager(db.connections));
        var controller = new org.jetlinks.pro.patrol.web.PatrolLevelController(levels, validator,
                configTransfer(), configHistory(), configDeletion());
        var level = new org.jetlinks.pro.patrol.entity.PatrolLevelEntity(); level.setName("Name audit");
        level.setCreatorName("Forged creator"); level.setModifierName("Forged modifier");
        level.setRule(new PatrolRule(Map.of(), Map.of(LocalDate.of(2026, 11, 5), List.of()), 0));
        var saved = controller.create(level).as(transaction::transactional).contextWrite(identity).block();
        assertEquals("Alice", saved.getCreatorName()); assertEquals("Alice", saved.getModifierName());
        Authentication auth = identity.get(Authentication.class);
        ((SimpleUser) auth.getUser()).setName("Alice renamed");
        var edit = levels.findById(saved.getId()).block();
        edit.setCreatorName("Rewritten creator"); edit.setModifierName("Spoofed modifier");
        controller.update(saved.getId(), edit).as(transaction::transactional).contextWrite(identity).block();
        var updated = levels.findById(saved.getId()).block();
        assertEquals("Alice", updated.getCreatorName()); assertEquals("Alice renamed", updated.getModifierName());
        assertEquals("alice", updated.getCreatorId()); assertEquals("alice", updated.getModifierId());
        // 已部署记录没有创建人姓名，普通配置更新同样不能用当前姓名补造历史。
        var warning = new org.jetlinks.pro.patrol.entity.PatrolWarningEntity();
        warning.setId("legacy-warning"); warning.setName("Legacy"); warning.setScopeKey("app"); warning.setProjectId("project"); warning.setProjectName("Project");
        warning.setTriggerParentCode("route"); warning.setTriggerChildCode("route_missed_point");
        warning.setExtension("tenantId", "north"); warning.setCreatorId("alice");
        warnings.insert(warning).block();
        var warningEdit = warnings.findById(warning.getId()).block();
        warningEdit.setCreatorName("Forged history"); warningEdit.setModifierName("Forged modifier");
        new org.jetlinks.pro.patrol.web.PatrolWarningController(warnings, validator, configDeletion())
                .update(warning.getId(), warningEdit).as(transaction::transactional).contextWrite(identity).block();
        var actual = warnings.findById(warning.getId()).block();
        org.junit.jupiter.api.Assertions.assertNull(actual.getCreatorName());
        assertEquals("Alice renamed", actual.getModifierName());
    }

    PatrolConfigDeletionService configDeletion() {
        var scope = mock(org.jetlinks.pro.patrol.integration.PatrolScope.class);
        when(scope.current()).thenReturn(Mono.just(new org.jetlinks.pro.patrol.integration.PatrolScope.Current("app")));
        return new PatrolConfigDeletionService(
                new PatrolLevelService(db.repository(org.jetlinks.pro.patrol.entity.PatrolLevelEntity.class,
                        org.jetlinks.pro.patrol.entity.PatrolLevelEntity::new)), standards,
                new PatrolWarningService(db.repository(org.jetlinks.pro.patrol.entity.PatrolWarningEntity.class,
                        org.jetlinks.pro.patrol.entity.PatrolWarningEntity::new)), plans, categories, points,
                db.repository(org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity.class, org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity::new), scope,
                TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
    }

    PatrolStandardEntity unusedStandard(String id, String scope, String tenant) {
        var standard = new PatrolStandardEntity(); standard.setId(id); standard.setName("Unused " + id);
        standard.setScopeKey(scope); standard.setProjectId("project"); standard.setProjectName("Project"); standard.setExtension("tenantId", tenant);
        standard.setItems(standards.findById("standard").block().getItems());
        standards.insert(standard).block(); return standard;
    }

    PatrolCategoryEntity unusedCategory(String id, String parent, String scopeKey) {
        var category = new PatrolCategoryEntity();
        category.setId(id); category.setName(id); category.setParentId(parent);
        category.setScopeKey(scopeKey); category.setProjectId("project"); category.setProjectName("Project");
        category.setExtension("tenantId", "north");
        categories.insert(category).block();
        return category;
    }

    @Test
    void categoryDeleteRemovesOnlyUnreferencedLeafFromQueriesAndRetainsStoredRow() {
        db.enableTenantQueries();
        unusedCategory("parent", "", "app");
        unusedCategory("leaf", "parent", "app");
        var deletion = configDeletion();
        StepVerifier.create(deletion.delete(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolCategory, "parent").contextWrite(identity))
                .expectErrorMessage("error.patrol.category.has_children").verify();
        deletion.delete(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolCategory, "leaf").contextWrite(identity).block();
        assertTrue(categories.findById("leaf").blockOptional().isEmpty());
        assertNotNull(categories.getRepository().findById("leaf").block().getDeletedTime());
        assertEquals("parent", categories.getRepository().findById("leaf").block().getParentId());
        assertNotNull(categories.findById("parent").block());
        var query = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
                .is("id", "leaf").or("id", "parent").getParam();
        assertEquals(List.of("parent"), categories.query(query).map(PatrolCategoryEntity::getId).collectList().block());
        assertEquals(1, categories.queryPager(query).block().getTotal());
        deletion.delete(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolCategory, "parent").contextWrite(identity).block();
        assertTrue(categories.findById("parent").blockOptional().isEmpty());
    }

    @Test
    void categoryDeleteRejectsLegacyPointAndStoppedMultiCategoryReferences() {
        db.enableTenantQueries();
        var deletion = configDeletion();
        StepVerifier.create(deletion.delete(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolCategory, "category").contextWrite(identity))
                .expectErrorMessage("error.patrol.category.referenced").verify();
        unusedCategory("linked-category", "", "app");
        var relation = new org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity();
        relation.setId("link"); relation.setPointId("point"); relation.setCategoryId("linked-category");
        relation.setScopeKey("app"); relation.setEnabled(false);
        db.repository(org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity::new).insert(relation).block();
        StepVerifier.create(deletion.delete(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolCategory, "linked-category").contextWrite(identity))
                .expectErrorMessage("error.patrol.category.referenced").verify();
        assertNotNull(categories.findById("linked-category").block());
    }

    @Test
    void categoryDeleteRejectsAssetsOutsideTenantAndApplication() {
        db.enableTenantQueries();
        unusedCategory("other-app-category", "", "other-app");
        StepVerifier.create(configDeletion().delete(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolCategory, "other-app-category").contextWrite(identity))
                .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
        assertEquals(null, categories.getRepository().findById("other-app-category").block().getDeletedTime());
    }

    @Test
    void configurationDeleteKeepsRowsAndHistoryButHidesAllPublicReadFormsAndExport() throws Exception {
        db.enableTenantQueries();
        unusedStandard("unused", "app", "north");
        var before = standards.findById("unused").block();
        var task = seedTask(2); workflow.submit(submission("one")).contextWrite(identity).block();
        configDeletion().delete(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolStandard, "unused").contextWrite(identity).block();
        assertTrue(standards.findById("unused").blockOptional().isEmpty());
        assertTrue(standards.findById(Mono.just("unused")).blockOptional().isEmpty());
        assertEquals(List.of("standard"), standards.findById(List.of("unused", "standard")).map(PatrolStandardEntity::getId).collectList().block());
        assertTrue(standards.findById(Flux.just("unused")).collectList().block().isEmpty());
        var stored = standards.getRepository().findById("unused").block();
        assertNotNull(stored.getDeletedTime()); assertEquals(before.getItems(), stored.getItems());
        assertEquals(before.isEnabled(), stored.isEnabled()); // 逻辑删除不是停用。
        var hostile = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery().is("id", "unused").or("id", "standard").getParam();
        assertEquals(List.of("standard"), standards.query(hostile).map(PatrolStandardEntity::getId).collectList().block());
        assertEquals(1, standards.count(hostile).block());
        assertEquals(1, standards.queryPager(hostile).block().getTotal());
        var bytes = configTransfer().export(standards, org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolStandard, hostile).contextWrite(identity).block();
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
            assertEquals(1, workbook.getSheetAt(0).getLastRowNum());
            assertEquals("Safety", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
        }
        assertEquals(1, executions.createQuery().count().block());
        assertEquals(1, tasks.findById(task.getId()).block().getCompletedCount());
        assertEquals(task.getStandardSnapshot(), tasks.findById(task.getId()).block().getStandardSnapshot());
    }

    @Test
    void configurationDeleteRejectsExistingReferencesEvenWhenRelationshipIsStopped() {
        db.enableTenantQueries();
        var deletion = configDeletion();
        StepVerifier.create(deletion.delete(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolStandard, "standard").contextWrite(identity))
                .expectErrorMessage("error.patrol.config.referenced").verify();
        plans.createUpdate().set("enabled", false).where("id", "plan").execute().block();
        StepVerifier.create(deletion.delete(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolStandard, "standard").contextWrite(identity))
                .expectErrorMessage("error.patrol.config.referenced").verify();
        assertNotNull(standards.findById("standard").block());
        assertEquals(null, standards.getRepository().findById("standard").block().getDeletedTime());
    }

    @Test
    void configurationDeleteRequiresAssetPermissionAndCurrentApplication() {
        db.enableTenantQueries();
        unusedStandard("foreign", "other-app", "south");
        unusedStandard("other-app", "other-app", "north");
        StepVerifier.create(configDeletion().delete(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolStandard, "foreign").contextWrite(identity))
                .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
        StepVerifier.create(configDeletion().delete(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolStandard, "other-app").contextWrite(identity))
                .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
        assertEquals(null, standards.getRepository().findById("foreign").block().getDeletedTime());
        assertEquals(null, standards.getRepository().findById("other-app").block().getDeletedTime());
    }

    @Test
    void configurationDeleteAndReferenceWriteUseSameTransactionLock() {
        db.enableTenantQueries(); unusedStandard("unused", "app", "north");
        var acquired = reactor.core.publisher.Sinks.<Void>one();
        var deleting = reactor.core.publisher.Sinks.<Void>one();
        var lockedStandard = spy(standards);
        doReturn(standards.findForUpdate("unused").doOnSubscribe(ignored -> deleting.tryEmitEmpty()))
                .when(lockedStandard).findForUpdate("unused");
        var scope = mock(org.jetlinks.pro.patrol.integration.PatrolScope.class);
        when(scope.current()).thenReturn(Mono.just(new org.jetlinks.pro.patrol.integration.PatrolScope.Current("app")));
        var deletion = new PatrolConfigDeletionService(null, lockedStandard, null, plans, categories, points,
                db.repository(org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity.class, org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity::new), scope,
                TransactionalOperator.create(new R2dbcTransactionManager(db.connections)));
        var writer = standards.findForUpdate("unused").flatMap(value -> {
            acquired.tryEmitEmpty();
            return deleting.asMono().publishOn(reactor.core.scheduler.Schedulers.boundedElastic()).then(plans.createUpdate().set("standardId", "unused").where("id", "plan").execute());
        }).as(TransactionalOperator.create(new R2dbcTransactionManager(db.connections))::transactional);
        var remover = acquired.asMono().publishOn(reactor.core.scheduler.Schedulers.boundedElastic()).then(deletion.delete(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolStandard, "unused"))
                .onErrorResume(org.hswebframework.web.exception.BusinessException.class, error -> {
                    assertEquals("error.patrol.config.referenced", error.getMessage()); return Mono.empty();
                });
        Mono.when(writer.subscribeOn(reactor.core.scheduler.Schedulers.parallel()),
                remover.subscribeOn(reactor.core.scheduler.Schedulers.parallel())).contextWrite(identity).block(Duration.ofSeconds(10));
        assertEquals("unused", plans.findById("plan").block().getStandardId());
        assertEquals(null, standards.getRepository().findById("unused").block().getDeletedTime());
    }

    @Test
    void levelReferenceGuardsAndWarningSoftDeletePreserveTheirOwnBusinessData() {
        db.enableTenantQueries();
        var levels = new PatrolLevelService(db.repository(org.jetlinks.pro.patrol.entity.PatrolLevelEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolLevelEntity::new));
        var level = new org.jetlinks.pro.patrol.entity.PatrolLevelEntity();
        level.setId("level"); level.setName("Day patrol"); level.setScopeKey("app"); level.setProjectId("project"); level.setProjectName("Project"); level.setExtension("tenantId", "north");
        level.setRule(plans.findById("plan").block().getRule()); levels.insert(level).block();
        plans.createUpdate().set("levelId", "level").where("id", "plan").execute().block();
        StepVerifier.create(configDeletion().delete(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolLevel, "level").contextWrite(identity))
                .expectErrorMessage("error.patrol.config.referenced").verify();
        assertEquals(level.getRule(), levels.findById("level").block().getRule());
        var warnings = new PatrolWarningService(db.repository(org.jetlinks.pro.patrol.entity.PatrolWarningEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolWarningEntity::new));
        var warning = new org.jetlinks.pro.patrol.entity.PatrolWarningEntity();
        warning.setId("warning"); warning.setName("Missed point"); warning.setScopeKey("app"); warning.setProjectId("project"); warning.setProjectName("Project"); warning.setExtension("tenantId", "north");
        warning.setTriggerParentCode("route"); warning.setTriggerChildCode("route_missed_point"); warnings.insert(warning).block();
        configDeletion().delete(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolWarning, "warning").contextWrite(identity).block();
        assertTrue(warnings.findById("warning").blockOptional().isEmpty());
        assertEquals("route_missed_point", warnings.getRepository().findById("warning").block().getTriggerChildCode());
    }

    @Test
    void softDeleteRollsBackWithCallerTransactionAndCannotBeClearedByClientJson() throws Exception {
        db.enableTenantQueries(); unusedStandard("unused", "app", "north");
        StepVerifier.create(configDeletion().delete(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolStandard, "unused")
                .then(Mono.error(new IllegalStateException("transaction fails")))
                .as(TransactionalOperator.create(new R2dbcTransactionManager(db.connections))::transactional).contextWrite(identity))
                .expectErrorMessage("transaction fails").verify();
        assertNotNull(standards.findById("unused").block());
        assertEquals(null, standards.getRepository().findById("unused").block().getDeletedTime());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var input = mapper.readValue("{\"name\":\"Client\",\"deletedTime\":123}", PatrolStandardEntity.class);
        assertEquals(null, input.getDeletedTime());
        configDeletion().delete(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolStandard, "unused").contextWrite(identity).block();
        var deleted = standards.getRepository().findById("unused").block();
        assertTrue(!mapper.readTree(mapper.writeValueAsString(deleted)).has("deletedTime"));
    }

    @Test
    void fullHundredPointSelectionUsesOneAtomicBatchWithoutDroppingRows() {
        var ids = new java.util.ArrayList<String>();
        var rows = new java.util.ArrayList<PatrolPointEntity>();
        for (int index = 0; index < 100; index++) {
            var point = new PatrolPointEntity();
            point.setId("batch-point-" + index); point.setCode("B" + index); point.setName("Batch " + index);
            point.setCategoryId("category"); point.setScopeKey("app"); point.setProjectId("project"); point.setProjectName("Project"); point.setExtension("tenantId", "north");
            ids.add(point.getId()); rows.add(point);
        }
        points.insertBatch(Mono.just(rows)).block();
        long started = System.nanoTime();
        pointAssignments().assign(ids, List.of("post")).contextWrite(identity).block(Duration.ofSeconds(30));
        assertEquals(100, plans.createQuery().in(PatrolPlanEntity::getPointId, ids).count().block());
        assertEquals(100, points.createQuery().in(PatrolPointEntity::getId, ids).notNull(PatrolPointEntity::getModifyTime).count().block());
        System.out.println("100-point assignment elapsed ms=" + (System.nanoTime() - started) / 1_000_000);
    }

    @Test
    void projectConstraintFiltersBeforePagingAndCannotBeBypassedByOrOrOldAssetGrant() {
        var hidden = new PatrolPointEntity(); hidden.setId("hidden-project-point"); hidden.setName("Other project");
        hidden.setCode("P999"); hidden.setCategoryId("category"); hidden.setScopeKey("app");
        hidden.setProjectId("other-project"); hidden.setExtension("tenantId", "north"); points.insert(hidden).block();
        var organizationOwned = new PatrolPointEntity(); organizationOwned.setId("old-organization-point");
        organizationOwned.setName("Legacy organization"); organizationOwned.setCode("P998");
        organizationOwned.setCategoryId("category"); organizationOwned.setScopeKey("app");
        organizationOwned.setOwnerOrganizationId("project"); organizationOwned.setExtension("tenantId", "north");
        points.insert(organizationOwned).block();
        // Existing ordinary grants deliberately include both projects and legacy organization rows.
        var original = mock(org.jetlinks.pro.assets.AssetsHolder.class);
        when(original.injectQuery(any(org.hswebframework.web.api.crud.entity.QueryParamEntity.class),
                org.mockito.ArgumentMatchers.eq("patrolPoint"), org.mockito.ArgumentMatchers.eq("id")))
                .thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(original.filter(org.mockito.ArgumentMatchers.eq("patrolPoint"), anyCollection(),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.any(org.jetlinks.pro.assets.AssetPermission[].class)))
                .thenAnswer(call -> Flux.fromIterable(call.getArgument(1)));
        var holder = new org.jetlinks.pro.patrol.asset.PatrolProjectAssetsHolder(original,
                org.jetlinks.pro.patrol.integration.ProjectTestContexts.project("app"), supplier);
        var query = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
                .is("id", "point").or().is("id", hidden.getId()).or().is("id", organizationOwned.getId())
                .orderByAsc("id").getParam();
        query.setPageSize(1);
        var scoped = holder.injectQuery(query, "patrolPoint", "id").block();
        var page = points.queryPager(scoped).block();
        assertEquals(1, page.getTotal());
        assertEquals("point", page.getData().get(0).getId());
        holder.assertPermission0(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolPoint,
                List.of("point"), org.jetlinks.pro.assets.crud.CrudAssetPermission.save).block();
        StepVerifier.create(holder.assertPermission0(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolPoint,
                List.of("point", hidden.getId()), org.jetlinks.pro.assets.crud.CrudAssetPermission.save))
                .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
        StepVerifier.create(holder.assertPermission0(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolPoint,
                List.of(organizationOwned.getId()), org.jetlinks.pro.assets.crud.CrudAssetPermission.read))
                .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
        var onlyOrganization = new org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo();
        onlyOrganization.setId("project"); onlyOrganization.setName("No project label");
        var organizationOnly = new org.jetlinks.pro.patrol.asset.PatrolProjectAssetsHolder(original,
                new org.jetlinks.pro.patrol.integration.PatrolProjectContext(List.of(onlyOrganization), null, "app"), supplier);
        assertEquals(0, points.count(organizationOnly.injectQuery(new org.hswebframework.web.api.crud.entity.QueryParamEntity(),
                "patrolPoint", "id").block()).block());
        var empty = new org.jetlinks.pro.patrol.asset.PatrolProjectAssetsHolder(original,
                new org.jetlinks.pro.patrol.integration.PatrolProjectContext(List.of(), null, "app"), supplier);
        assertEquals(0, points.count(empty.injectQuery(new org.hswebframework.web.api.crud.entity.QueryParamEntity(),
                "patrolPoint", "id").block()).block());
    }

    @Test
    void projectScopeNeverIncludesInvisibleAncestorsOrAllowsNonProjectSelection() {
        var group = new org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo();
        group.setId("D"); group.setName("D"); group.setParentId("invisible-A");
        var project = new org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo();
        project.setId("E"); project.setName("E"); project.setOrganizationType(
                org.jetlinks.pro.property.base.organization.OrganizationType.project); project.setChildren(List.of());
        group.setChildren(List.of(project));
        var context = new org.jetlinks.pro.patrol.integration.PatrolProjectContext(List.of(group), null, "app");
        assertEquals(Set.of("E"), context.projects());
        assertEquals(Set.of("D", "E"), context.organizations());
        org.junit.jupiter.api.Assertions.assertThrows(org.hswebframework.web.authorization.exception.AccessDenyException.class,
                () -> context.requireProject("D"));
        org.junit.jupiter.api.Assertions.assertThrows(org.hswebframework.web.authorization.exception.AccessDenyException.class,
                () -> context.requireProject("B"));
    }

    @Test
    void plannedTasksAndExecutionsKeepProjectSnapshot() {
        var generated = generator.generate("plan", LocalDate.now()).contextWrite(identity).collectList().block();
        assertTrue(!generated.isEmpty());
        assertTrue(generated.stream().allMatch(task -> "project".equals(task.getProjectId())));
        var group = generationRepository.findById(generated.get(0).getTaskGroupId()).block();
        assertEquals("project", group.getProjectId());
        tasks.deleteById(Flux.fromIterable(generated).map(PatrolTaskEntity::getId)).block();
        var task = seedTask(1); task.setProjectId("project"); task.setProjectName("Project");
        tasks.updateById(task.getId(), task).block();
        var execution = workflow.submit(submission("project-snapshot")).contextWrite(identity).block();
        assertEquals("project", execution.getProjectId());
        assertEquals("Project", execution.getProjectName());
    }

    @Test
    void allProjectsViewRejectsCrossProjectAndLegacyOrganizationReferences() {
        var root = new org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo();
        root.setId("company"); root.setName("Company");
        var project = new org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo();
        project.setId("project"); project.setName("Project"); project.setOrganizationType(
                org.jetlinks.pro.property.base.organization.OrganizationType.project);
        var other = new org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo();
        other.setId("other"); other.setName("Other"); other.setOrganizationType(
                org.jetlinks.pro.property.base.organization.OrganizationType.project);
        root.setChildren(List.of(project, other));
        var context = new org.jetlinks.pro.patrol.integration.PatrolProjectContext(List.of(root), null, "app");
        var auth = identity.put(org.jetlinks.pro.patrol.integration.PatrolProjectContext.class, context);
        var point = points.findById("point").block();
        categories.createUpdate().set("projectId", "other").where("id", "category").execute().block();
        StepVerifier.create(categoryValidator().validate(point).contextWrite(auth))
                .expectError(org.hswebframework.web.exception.BusinessException.class).verify();
        categories.createUpdate().setNull("projectId").set("ownerOrganizationId", "company")
                .where("id", "category").execute().block();
        StepVerifier.create(categoryValidator().validate(point).contextWrite(auth))
                .expectError(org.hswebframework.web.exception.BusinessException.class).verify();
        var shared = categories.findById("category").block();
        var edited = categories.findById("category").block(); edited.setName("Legacy updated");
        StepVerifier.create(categoryValidator().validate(edited, shared).contextWrite(auth.put(
                org.jetlinks.pro.patrol.integration.PatrolProjectContext.class, context.forProject("project"))))
                .expectErrorMatches(error -> error instanceof org.hswebframework.web.exception.BusinessException business
                        && "error.patrol.project_immutable".equals(business.getMessage())).verify();
        assertTrue(!context.canReference(null, "project"));
        assertTrue(!context.canReference("project", null));
        assertTrue(!context.canReference("project", "other"));
        assertTrue(context.canReference("project", "project"));
    }

    @Test
    void everyBusinessConfigurationRequiresProjectAndCannotUseOrganizationAsOwner() {
        var projectContext = org.jetlinks.pro.patrol.integration.ProjectTestContexts.project("app");
        var allProjects = new org.jetlinks.pro.patrol.integration.PatrolProjectContext(projectContext.tree(), null, "app");
        var all = identity.put(org.jetlinks.pro.patrol.integration.PatrolProjectContext.class, allProjects);
        var configs = List.<org.jetlinks.pro.patrol.entity.PatrolConfigEntity>of(
                new org.jetlinks.pro.patrol.entity.PatrolLevelEntity(), new PatrolStandardEntity(),
                new PatrolCategoryEntity(), new org.jetlinks.pro.patrol.entity.PatrolTagEntity(),
                new PatrolPointEntity());
        for (var config : configs) {
            config.setOwnerOrganizationId("project");
            StepVerifier.create(PatrolOwnership.resolve(config, null, points).contextWrite(all))
                    .expectErrorMatches(error -> error instanceof org.hswebframework.web.exception.BusinessException business
                            && business.getStatus() == 400 && "error.patrol.project_required".equals(business.getMessage())).verify();
            config.setProjectId("project");
            PatrolOwnership.resolve(config, null, points).contextWrite(all).block();
            assertEquals("project", config.getProjectId());
            assertEquals("Project", config.getProjectName());
            assertEquals(null, config.getOwnerOrganizationId());
        }
        var example = new org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity();
        example.setPurpose(org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity.Purpose.example);
        example.setOwnerOrganizationId("project");
        StepVerifier.create(PatrolOwnership.assignSelected(example).contextWrite(all))
                .expectError(org.hswebframework.web.exception.BusinessException.class).verify();
        PatrolOwnership.assignSelected(example).contextWrite(identity).block();
        assertEquals("project", example.getProjectId());
        assertEquals(null, example.getOwnerOrganizationId());
        var ordinaryEdit = new org.jetlinks.pro.patrol.entity.PatrolLevelEntity(); ordinaryEdit.setProjectId("other");
        var root = new org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo();
        root.setId("other"); root.setName("Other");
        root.setOrganizationType(org.jetlinks.pro.property.base.organization.OrganizationType.project);
        var both = new org.jetlinks.pro.patrol.integration.PatrolProjectContext(
                List.of(projectContext.tree().get(0), root), null, "app");
        StepVerifier.create(PatrolOwnership.resolve(ordinaryEdit, configs.get(0), points).contextWrite(
                identity.put(org.jetlinks.pro.patrol.integration.PatrolProjectContext.class, both)))
                .expectErrorMatches(error -> error instanceof org.hswebframework.web.exception.BusinessException business
                        && "error.patrol.project_immutable".equals(business.getMessage())).verify();
    }

    @Test
    void createUsesAutomaticApplicationBindingWithRealScopedHolder() {
        var bindManager = mock(org.jetlinks.pro.assets.AssetBindManager.class);
        var bindings = new java.util.ArrayList<org.jetlinks.pro.assets.AssetBindRequest>();
        when(bindManager.bindAssets(any(org.reactivestreams.Publisher.class)))
                .thenAnswer(call -> Flux.<org.jetlinks.pro.assets.AssetBindRequest>from(call.getArgument(0))
                        .doOnNext(bindings::add).then());
        var materializers = AssetsHolderHolder.registerScopeMaterializers(
                new org.jetlinks.pro.assets.scope.AssetScopeMaterializers(List.of(),
                        new org.jetlinks.pro.assets.impl.DimensionAssetScopeMaterializer(mock(AssetManager.class), bindManager)));
        try {
            var auth = new SimpleAuthentication();
            var user = new SimpleUser(); user.setId("alice"); user.setName("Alice"); auth.setUser(user);
            var activation = org.jetlinks.pro.assets.scope.AssetScopeActivation.activate(auth,
                    org.hswebframework.web.authorization.simple.SimpleDimension.of("app", "Patrol",
                            org.hswebframework.web.authorization.simple.SimpleDimensionType.of("business_application"), Map.of()),
                    Map.of("*", org.jetlinks.pro.assets.scope.AssetScopeStrategy.SELECT_AND_BIND));
            var context = activation.write().apply(identity.put(org.jetlinks.pro.assets.AssetsHolder.class,
                    org.jetlinks.pro.assets.AssetsHolderProvider.compose(List.of())));
            var tags = new PatrolTagService(db.repository(org.jetlinks.pro.patrol.entity.PatrolTagEntity.class,
                    org.jetlinks.pro.patrol.entity.PatrolTagEntity::new));
            var controller = new org.jetlinks.pro.patrol.web.PatrolTagController(tags, categoryValidator(), null);
            var input = new org.jetlinks.pro.patrol.entity.PatrolTagEntity(); input.setName("Allowed project tag");
            var result = controller.create(input).contextWrite(context).block();
            assertEquals("project", result.getProjectId());
            assertEquals(1, bindings.size());
            assertEquals("business_application", bindings.get(0).getTargetType());
            assertEquals("app", bindings.get(0).getTargetId());
            assertEquals(List.of(result.getId()), bindings.get(0).getAssetIdList());
            var denied = new org.jetlinks.pro.patrol.entity.PatrolTagEntity();
            denied.setName("Foreign project tag"); denied.setProjectId("foreign");
            StepVerifier.create(controller.create(denied).contextWrite(context))
                    .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
            assertEquals(1, tags.createQuery().count().block());
            assertEquals(1, bindings.size());
        } finally { materializers.dispose(); }
    }

    @Test
    void legacyProjectAssignmentIsExplicitAndDoesNotChangeHistoricalCounts() {
        var old = new org.jetlinks.pro.patrol.entity.PatrolTagEntity();
        old.setId("legacy-tag"); old.setName("Old tag"); old.setScopeKey("app");
        old.setOwnerOrganizationId("old-company");
        var tags = new PatrolTagService(db.repository(org.jetlinks.pro.patrol.entity.PatrolTagEntity.class,
                org.jetlinks.pro.patrol.entity.PatrolTagEntity::new));
        tags.insert(old).block();
        var permissions = mock(org.hswebframework.web.authorization.Authentication.class);
        when(permissions.hasPermission("patrol-tag", "save")).thenReturn(true);
        var holder = mock(org.jetlinks.pro.assets.AssetsHolder.class);
        when(holder.assertPermission0(org.mockito.ArgumentMatchers.eq(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolTag),
                anyCollection(), org.mockito.ArgumentMatchers.any(org.jetlinks.pro.assets.AssetPermission[].class)))
                .thenReturn(Mono.empty());
        var assets = mock(PatrolAssetSupplier.class);
        doReturn(tags).when(assets).service(org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolTag);
        var controller = new org.jetlinks.pro.patrol.web.PatrolProjectMigrationController(assets,
                categoryValidator(), tasks, new PatrolTaskGroupService(generationRepository, new org.hswebframework.web.crud.query.DefaultQueryHelper(db.operator()), tasks), executions);
        var context = Context.of(org.hswebframework.web.authorization.Authentication.class, permissions,
                org.jetlinks.pro.assets.AssetsHolder.class, holder,
                org.jetlinks.pro.patrol.integration.PatrolProjectContext.class,
                org.jetlinks.pro.patrol.integration.ProjectTestContexts.project("app"));
        var request = new org.jetlinks.pro.patrol.web.PatrolProjectMigrationController.Assignment(
                org.jetlinks.pro.patrol.asset.PatrolAssetType.patrolTag, List.of("legacy-tag"), "project");
        assertEquals(1, controller.assign(request).contextWrite(context).block());
        assertEquals("project", tags.findById("legacy-tag").block().getProjectId());
        assertEquals(null, tags.findById("legacy-tag").block().getOwnerOrganizationId());
        StepVerifier.create(controller.assign(request).contextWrite(context))
                .expectErrorMatches(error -> error instanceof org.hswebframework.web.exception.BusinessException business
                        && business.getStatus() == 400 && "error.patrol.project_immutable".equals(business.getMessage())).verify();
        StepVerifier.create(controller.assign(new org.jetlinks.pro.patrol.web.PatrolProjectMigrationController.Assignment(
                request.type(), request.ids(), "not-visible")).contextWrite(context)).expectError(
                org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
        when(permissions.hasPermission("patrol-tag", "save")).thenReturn(false);
        StepVerifier.create(controller.assign(request).contextWrite(context))
                .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
    }

    @Test
    void evidenceCannotBeReusedAcrossProjectsEvenWhenBothAssetsAreReadable() {
        var photo = new org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity();
        photo.setId("cross-project-photo"); photo.setName("Photo"); photo.setScopeKey("app");
        photo.setProjectId("other"); photo.setCreatorId("alice"); photo.setExtension("tenantId", "north");
        photo.setPurpose(org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity.Purpose.evidence);
        photo.setPlatformFileId("platform-file"); photo.setInternalUrl("internal");
        photo.setMediaType("image/png"); photo.setLength(100); attachments.insert(photo).block();
        StepVerifier.create(attachments.assertReferences(List.of(photo.getId()), photo.getPurpose(), "app", "alice",
                "project").contextWrite(identity))
                .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
        photo.setId("same-project-photo"); photo.setProjectId("project"); attachments.insert(photo).block();
        attachments.assertReferences(List.of(photo.getId()), photo.getPurpose(), "app", "alice", "project")
                .contextWrite(identity).block();
    }

}
