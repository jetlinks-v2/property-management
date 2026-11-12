package org.jetlinks.pro.patrol.service;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity;
import org.jetlinks.pro.patrol.entity.PatrolRole;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.jetlinks.pro.patrol.integration.PatrolScope;
import org.jetlinks.pro.patrol.integration.SaasPersonDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 H2 与 TenantAssetsHolder 覆盖手机查询范围；仅替代外部成员目录。 */
class PatrolMobileServiceTest {
    PatrolWorkflowTest fixture;
    PatrolScope scope;
    SaasPersonDirectory people;
    PatrolMobileService mobile;

    @BeforeEach
    void setup() {
        fixture = new PatrolWorkflowTest();
        fixture.setup();
        fixture.db.enableTenantQueries();
        scope = mock(PatrolScope.class);
        people = mock(SaasPersonDirectory.class);
        when(scope.current()).thenReturn(Mono.just(new PatrolScope.Current("app")));
        when(people.assertMembers(List.of("alice"))).thenReturn(Mono.empty());
        mobile = service(scope);
    }

    PatrolMobileService service(PatrolScope source) {
        return new PatrolMobileService(source, people, fixture.tasks, fixture.points,
                fixture.executions, fixture.workflow);
    }

    @Test
    void nfcCodeOnlyMatchesVisibleTaskPointsAndKeepsExecutionChecks() {
        fixture.seedTask(2);
        fixture.points.createUpdate().set("nfcCode", "NFC-DEMO-01").where("id", "point").execute().block();
        assertEquals(List.of("task"), mobile.tasks(LocalDate.now(), false, " NFC-DEMO-01 ")
                .contextWrite(fixture.identity).block().stream().map(row -> row.task().getId()).toList());
        assertTrue(mobile.tasks(LocalDate.now(), false, "UNKNOWN").contextWrite(fixture.identity).block().isEmpty());
        var hidden = new org.jetlinks.pro.patrol.entity.PatrolPointEntity();
        hidden.setId("hidden-point"); hidden.setName("Hidden"); hidden.setCategoryId("category");
        hidden.setProjectId("other-project"); hidden.setScopeKey("other-app"); hidden.setCode("HIDDEN"); hidden.setNfcCode("HIDDEN-NFC");
        fixture.points.insert(hidden).block();
        assertTrue(mobile.tasks(LocalDate.now(), false, "HIDDEN-NFC").contextWrite(fixture.identity).block().isEmpty());
        fixture.roles.get("post").setUserIds(List.of("bob"));
        var unassigned = mobile.tasks(LocalDate.now(), false, "NFC-DEMO-01").contextWrite(fixture.identity).block();
        assertEquals(1, unassigned.size());
        assertFalse(unassigned.get(0).executable());
        assertEquals("error.patrol.execution.unassigned", unassigned.get(0).reason());
        assertTrue(mobile.tasks(LocalDate.now(), true, "NFC-DEMO-01").contextWrite(fixture.identity).block().isEmpty());
        StepVerifier.create(mobile.tasks(LocalDate.now(), false, " ").contextWrite(fixture.identity)).expectError().verify();
    }

    @AfterEach
    void close() { fixture.close(); }

    @Test
    void assignedTasksSubmitAndReplayThenAppearInPersonalHistory() {
        fixture.seedTask(1);
        var rows = mobile.tasks(LocalDate.now()).contextWrite(fixture.identity).block();
        assertEquals(1, rows.size()); assertTrue(rows.get(0).executable());
        var first = fixture.workflow.submit(fixture.submission("mobile-1")).contextWrite(fixture.identity).block();
        var replay = fixture.workflow.submit(fixture.submission("mobile-1")).contextWrite(fixture.identity).block();
        assertEquals(first.getId(), replay.getId());
        assertEquals(1, fixture.tasks.findById("task").block().getCompletedCount());
        var completed = mobile.tasks(LocalDate.now()).contextWrite(fixture.identity).block().get(0);
        assertFalse(completed.executable());
        assertEquals("error.patrol.execution.completed", completed.reason());
        var history = mobile.history(new QueryParamEntity()).contextWrite(fixture.identity).block();
        assertEquals(1, history.getTotal()); assertEquals(first.getId(), history.getData().get(0).getId());
    }

    @Test
    void assignedPostIsRequiredAndOutsideWindowIsVisibleButNotExecutable() {
        var task = fixture.seedTask(1);
        task.setId("future-task"); task.setTaskGroupId("future-group");
        // Keep the future period in the requested end-date even when tests run late at night.
        long end = LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai"))
                .toInstant().toEpochMilli() - 1;
        task.setStartTime(System.currentTimeMillis() + (end - System.currentTimeMillis()) / 2);
        task.setEndTime(end);
        fixture.tasks.insert(task).block();
        var rows = mobile.tasks(LocalDate.now()).contextWrite(fixture.identity).block();
        var future = rows.stream().filter(row -> "future-task".equals(row.task().getId())).findFirst().orElseThrow();
        assertFalse(future.executable());
        assertEquals("error.patrol.execution.outside_window", future.reason());
        fixture.roles.get("post").setUserIds(List.of("bob"));
        assertTrue(mobile.tasks(LocalDate.now()).contextWrite(fixture.identity).block().isEmpty());
        var all = mobile.tasks(LocalDate.now(), false).contextWrite(fixture.identity).block();
        assertEquals(2, all.size());
        assertTrue(all.stream().noneMatch(PatrolMobileService.MobileTask::executable));
        assertTrue(all.stream().allMatch(row -> "error.patrol.execution.unassigned".equals(row.reason())));
        fixture.roles.get("post").setEnabled(false);
        assertTrue(mobile.tasks(LocalDate.now()).contextWrite(fixture.identity).block().isEmpty());
    }

    @Test
    void translatedExceptionsKeepStableReasonsAndMineFilter() {
        fixture.seedTask(1);
        var workflow = mock(PatrolExecutionWorkflow.class);
        var source = new PatrolMobileService(scope, people, fixture.tasks, fixture.points,
                fixture.executions, workflow);
        for (String code : List.of("error.patrol.execution.completed", "error.patrol.execution.unassigned")) {
            var translated = new org.hswebframework.web.exception.BusinessException(code) {
                @Override public String getMessage() { return "Localized display message"; }
            };
            when(workflow.listEligibility(any(), eq("alice"), any()))
                    .thenReturn(Mono.just(task -> Mono.error(translated)));
            var all = source.tasks(LocalDate.now(), false).contextWrite(fixture.identity).block();
            assertEquals(code, all.get(0).reason());
            assertFalse(all.get(0).executable());
            if (code.endsWith("unassigned"))
                assertTrue(source.tasks(LocalDate.now()).contextWrite(fixture.identity).block().isEmpty());
        }
    }

    @Test
    void selectedProjectFiltersTasksAndPersonalHistoryWithRealProjectHolder() {
        fixture.seedTask(2);
        var own = fixture.workflow.submit(fixture.submission("project-history")).contextWrite(fixture.identity).block();
        fixture.seedPlan("other-plan", "north");
        fixture.plans.createUpdate().set(org.jetlinks.pro.patrol.entity.PatrolPlanEntity::getProjectId, "other-project")
                .where(org.jetlinks.pro.patrol.entity.PatrolPlanEntity::getId, "other-plan").execute().block();
        var other = fixture.tasks.findById("task").block();
        other.setId("other-task"); other.setTaskGroupId("other-group"); other.setPlanId("other-plan");
        other.setProjectId("other-project"); fixture.tasks.insert(other).block();
        var record = copyRecord(own, "other-history", "other-plan", "alice");
        record.setTaskId("other-task"); record.setProjectId("other-project"); fixture.executions.insert(record).block();
        var holder = new org.jetlinks.pro.patrol.asset.PatrolProjectAssetsHolder(
                new org.jetlinks.pro.tenant.assets.TenantAssetsHolder(java.util.Set.of("north"), mock(org.jetlinks.pro.assets.AssetManager.class)),
                org.jetlinks.pro.patrol.integration.ProjectTestContexts.project("app"), fixture.supplier);
        var identity = fixture.identity.put(org.jetlinks.pro.assets.AssetsHolder.class, holder);
        assertEquals(List.of("task"), mobile.tasks(LocalDate.now()).contextWrite(identity).block().stream()
                .map(row -> row.task().getId()).toList());
        assertEquals(List.of(own.getId()), mobile.history(new QueryParamEntity()).contextWrite(identity).block()
                .getData().stream().map(PatrolExecutionEntity::getId).toList());
    }

    @Test
    void applicationAndAssetBoundariesApplyEvenWhenPlanIdsAreKnown() {
        fixture.seedTask(1);
        // 同一业务 scope 中伪造外项目计划，仍必须被 AssetsHolder 排除。
        fixture.seedPlan("foreign", "south");
        var foreign = fixture.plans.findById("foreign").block();
        foreign.setScopeKey("app"); fixture.plans.updateById("foreign", foreign).block();
        var task = fixture.tasks.findById("task").block();
        task.setId("foreign-task"); task.setTaskGroupId("foreign-group"); task.setPlanId("foreign"); fixture.tasks.insert(task).block();
        var rows = mobile.tasks(LocalDate.now()).contextWrite(fixture.identity).block();
        assertEquals(List.of("task"), rows.stream().map(row -> row.task().getId()).toList());
        assertEquals(List.of("task"), mobile.tasks(LocalDate.now(), false).contextWrite(fixture.identity).block()
                .stream().map(row -> row.task().getId()).toList());
        when(scope.current()).thenReturn(Mono.just(new PatrolScope.Current("another-app")));
        assertTrue(mobile.tasks(LocalDate.now()).contextWrite(fixture.identity).block().isEmpty());
    }

    @Test
    void personalHistoryNestsCallerOrFiltersAndClampsPageSize() {
        fixture.seedTask(3);
        var own = fixture.workflow.submit(fixture.submission("own")).contextWrite(fixture.identity).block();
        var other = copyRecord(own, "other-user", "plan", "bob"); fixture.executions.insert(other).block();
        fixture.seedPlan("foreign", "south");
        var foreign = copyRecord(own, "foreign-record", "foreign", "alice"); fixture.executions.insert(foreign).block();
        var query = QueryParamEntity.newQuery().is("id", "other-user").or("id", "foreign-record").or("id", own.getId()).getParam();
        query.setPageSize(10000); query.setPaging(false);
        var history = mobile.history(query).contextWrite(fixture.identity).block();
        assertEquals(1, history.getTotal()); assertEquals(own.getId(), history.getData().get(0).getId());
        assertEquals(10000, query.getPageSize()); // 不改调用方对象。
    }

    @Test
    void historyCategoryAndLevelFiltersUseTaskSnapshotsBeforePagingAndKeepPersonalScope() {
        var task = fixture.seedTask(3);
        fixture.workflow.submit(fixture.submission("without-filter")).contextWrite(fixture.identity).block();
        task.setId("matched-task"); task.setPeriodNo(2); task.setStartTime(task.getStartTime() + 1);
        task.setCategoryId("category-snapshot"); task.setCategoryName("Original category");
        task.setLevelId("level-snapshot"); task.setLevelName("Original level"); task.setPointName("Water pump");
        fixture.tasks.insert(task).block();
        var saved = fixture.workflow.submit(new PatrolExecutionWorkflow.Submission(task.getId(), "matched",
                List.of(new PatrolExecutionEntity.Answer("lock", "normal")), "checked", null, null))
                .contextWrite(fixture.identity).block();
        var other = copyRecord(saved, "other-user-matched", "plan", "bob"); other.setTaskId(task.getId());
        fixture.executions.insert(other).block();
        fixture.seedPlan("foreign", "south");
        var foreign = copyRecord(saved, "foreign-matched", "foreign", "alice"); foreign.setTaskId(task.getId());
        fixture.executions.insert(foreign).block();
        var query = QueryParamEntity.newQuery().is("categoryId", "category-snapshot")
                .is("levelId", "level-snapshot").like("pointName", "%Water%")
                .gte("receiveTime", saved.getReceiveTime() - 1).lt("receiveTime", saved.getReceiveTime() + 1).getParam();
        query.setPageSize(1);
        var page = mobile.history(query).contextWrite(fixture.identity).block();
        assertEquals(1, page.getTotal()); assertEquals(saved.getId(), page.getData().get(0).getId());
        assertEquals("Original category", page.getData().get(0).getCategoryName());
        assertEquals("Original level", page.getData().get(0).getLevelName());
        query.toNestQuery().is("levelId", "different");
        assertEquals(0, mobile.history(query).contextWrite(fixture.identity).block().getTotal());
    }

    @Test
    void unrelatedHistoricalPlansDoNotPreventTodayTasksOrPersonalHistory() {
        fixture.seedTask(1);
        var records = new java.util.ArrayList<org.jetlinks.pro.patrol.entity.PatrolPlanEntity>();
        var source = fixture.plans.findById("plan").block();
        for (int index = 0; index < 1001; index++) {
            var plan = new org.jetlinks.pro.patrol.entity.PatrolPlanEntity();
            org.springframework.beans.BeanUtils.copyProperties(source, plan);
            plan.setId("archived-" + index); plan.setEnabled(false);
            records.add(plan);
        }
        fixture.plans.insertBatch(Mono.just(records)).block();
        assertEquals(List.of("task"), mobile.tasks(LocalDate.now()).contextWrite(fixture.identity).block()
                .stream().map(row -> row.task().getId()).toList());
        fixture.workflow.submit(fixture.submission("history-after-import")).contextWrite(fixture.identity).block();
        assertEquals(1, mobile.history(new QueryParamEntity()).contextWrite(fixture.identity).block().getTotal());
    }

    private PatrolExecutionEntity copyRecord(PatrolExecutionEntity source, String id, String plan, String user) {
        var row = new PatrolExecutionEntity(); row.setId(id); row.setTaskId("task"); row.setPlanId(plan);
        row.setPointId("point"); row.setUserId(user); row.setRequestId(id); row.setReceiveTime(source.getReceiveTime());
        row.setAnswers(source.getAnswers()); row.setStandardSnapshot(source.getStandardSnapshot()); return row;
    }

    @Test
    void missingTrustedScopeOrProjectMembershipFailsClosed() {
        fixture.seedTask(1);
        StepVerifier.create(service(new PatrolScope()).tasks(LocalDate.now()).contextWrite(fixture.identity))
                .expectError().verify();
        when(people.assertMembers(List.of("alice"))).thenReturn(Mono.error(new IllegalStateException("not a project member")));
        StepVerifier.create(mobile.tasks(LocalDate.now()).contextWrite(fixture.identity)).expectError().verify();
        StepVerifier.create(mobile.history(new QueryParamEntity()).contextWrite(fixture.identity)).expectError().verify();
    }
}
