package org.jetlinks.pro.patrol.service;

import org.jetlinks.pro.patrol.entity.*;
import org.jetlinks.pro.patrol.integration.PatrolScope;
import org.jetlinks.pro.patrol.integration.SaasPersonDirectory;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.crud.query.DefaultQueryHelper;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.junit.jupiter.api.*;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 SQL/事务/资产校验；仅替代外部应用成员与平台上下文来源。 */
class PatrolMissedServiceTest {
    PatrolWorkflowTest f;
    PatrolMissedService missed;
    ReactiveRepository<PatrolMissedItemEntity, String> items;
    ReactiveRepository<PatrolMissedEventEntity, String> events;
    PatrolScope scope;
    @BeforeEach void setup() {
        f = new PatrolWorkflowTest(); f.setup(); f.db.enableTenantQueries();
        items = f.db.repository(PatrolMissedItemEntity.class, PatrolMissedItemEntity::new);
        events = f.db.repository(PatrolMissedEventEntity.class, PatrolMissedEventEntity::new);
        scope = mock(PatrolScope.class);
        when(scope.current()).thenReturn(Mono.just(new PatrolScope.Current("north", "app")));
        var people = mock(SaasPersonDirectory.class);
        when(people.assertMembers(List.of("alice"))).thenReturn(Mono.empty());
        when(people.assertMembers(List.of("bob"))).thenReturn(Mono.empty());
        missed = new PatrolMissedService(items, events, f.tasks, f.plans, f.posts, f.rosters, scope, people,
                new DefaultQueryHelper(f.db.operator()), TransactionalOperator.create(new R2dbcTransactionManager(f.db.connections)));
    }
    @AfterEach void close() { f.close(); }
    PatrolTaskEntity ended(int required) {
        return f.seedTask(required, System.currentTimeMillis() - 3600000, System.currentTimeMillis() - 1000);
    }
    List<PatrolMissedItemEntity> group(PatrolTaskEntity task) {
        return missed.group(task.getTaskGroupId()).contextWrite(f.identity).block();
    }
    List<PatrolMissedService.Decision> decisions(List<PatrolMissedItemEntity> rows) {
        return rows.stream().map(row -> new PatrolMissedService.Decision(row.getId(), row.getApplicationId())).toList();
    }
    Context bob() {
        var auth = new SimpleAuthentication(); var user = new SimpleUser(); user.setId("bob"); user.setName("Bob"); auth.setUser(user);
        return Context.of(Authentication.class, auth);
    }
    @Test void materializesExactMissingOccurrencesAndApprovalDoesNotCreateExecution() {
        var task = ended(4);
        f.tasks.createUpdate().set(PatrolTaskEntity::getCompletedCount, 1)
                .where(PatrolTaskEntity::getId, task.getId()).execute().block();
        // 一次真实历史执行已完成，其余3次截止后申报；审核不新增执行事实。
        var execution = new PatrolExecutionEntity(); execution.setId("real-inspection");
        execution.setTaskId(task.getId()); execution.setTaskGroupId(task.getTaskGroupId());
        execution.setPlanId("plan"); execution.setPointId("point"); execution.setUserId("alice");
        execution.setRequestId("original"); execution.setReceiveTime(task.getStartTime() + 1000);
        execution.setAnswers(List.of(new PatrolExecutionEntity.Answer("lock", "normal")));
        f.executions.insert(execution).block();
        var rows = group(task);
        assertEquals(List.of(2, 3, 4), rows.stream().map(PatrolMissedItemEntity::getOccurrenceNo).toList());
        assertEquals(rows.stream().map(PatrolMissedItemEntity::getId).toList(), group(task).stream().map(PatrolMissedItemEntity::getId).toList());
        missed.apply(rows.stream().map(PatrolMissedItemEntity::getId).toList(), "  设备维护  ", true).contextWrite(f.identity).block();
        var pending = group(task); assertEquals("设备维护", pending.get(0).getReason());
        StepVerifier.create(missed.apply(List.of(rows.get(0).getId()), "again", true).contextWrite(f.identity))
                .expectErrorSatisfies(error -> assertEquals("error.patrol.missed.state", ((BusinessException) error).getI18nCode())).verify();
        assertEquals(3, missed.audit(decisions(pending), true, "确认").contextWrite(f.identity).block());
        assertEquals(4, f.tasks.findById(task.getId()).block().getCompletedCount());
        assertEquals(1, f.executions.createQuery().count().block());
        assertEquals(3, missed.audit(decisions(pending), true, "重试").contextWrite(f.identity).block());
        assertEquals(4, f.tasks.findById(task.getId()).block().getCompletedCount());
        assertEquals(6, events.createQuery().count().block());
        assertTrue(group(task).stream().allMatch(row -> row.getStatus() == PatrolMissedItemEntity.Status.approved));
        var history = missed.history(rows.get(0).getId(), true).contextWrite(f.identity).collectList().block();
        assertEquals(2, history.size()); assertEquals(List.of("apply", "approve"), history.stream().map(PatrolMissedEventEntity::getAction).toList());
        assertEquals("确认", history.get(1).getRemark());
    }
    @Test void rejectedAndWithdrawnCanReapplyWhileOldAuditCannotTouchNewApplication() {
        var task = ended(1); var row = group(task).get(0);
        missed.apply(List.of(row.getId()), "原因", true).contextWrite(f.identity).block();
        var first = decisions(group(task));
        StepVerifier.create(missed.audit(first, false, "  ").contextWrite(f.identity)).expectError(BusinessException.class).verify();
        missed.audit(first, false, "材料不完整").contextWrite(f.identity).block();
        assertEquals(0, f.tasks.findById(task.getId()).block().getCompletedCount());
        missed.apply(List.of(row.getId()), "补充材料", true).contextWrite(f.identity).block();
        var second = group(task).get(0); assertNotEquals(first.get(0).applicationId(), second.getApplicationId());
        StepVerifier.create(missed.audit(first, true, "").contextWrite(f.identity)).expectError(BusinessException.class).verify();
        StepVerifier.create(missed.withdraw(row.getId(), second.getApplicationId(), false).contextWrite(bob()))
                .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
        missed.withdraw(row.getId(), second.getApplicationId(), true).contextWrite(f.identity).block();
        missed.apply(List.of(row.getId()), "再次申请", true).contextWrite(f.identity).block();
        assertEquals(PatrolMissedItemEntity.Status.pending, group(task).get(0).getStatus());
        assertEquals(5, missed.history(row.getId(), true).contextWrite(f.identity).count().block());
    }
    @Test void mixedInvalidBatchRollsBackAllApprovalsAndCurrentApplicationScopeIsEnforced() {
        var task = ended(2); var rows = group(task);
        missed.apply(rows.stream().map(PatrolMissedItemEntity::getId).toList(), "原因", false).contextWrite(f.identity).block();
        var pending = group(task);
        var request = List.of(new PatrolMissedService.Decision(pending.get(0).getId(), pending.get(0).getApplicationId()),
                new PatrolMissedService.Decision(pending.get(1).getId(), "stale"));
        StepVerifier.create(missed.audit(request, true, "").contextWrite(f.identity)).expectError(BusinessException.class).verify();
        assertEquals(0, f.tasks.findById(task.getId()).block().getCompletedCount());
        assertTrue(group(task).stream().allMatch(row -> row.getStatus() == PatrolMissedItemEntity.Status.pending));
        assertEquals(2, events.createQuery().count().block());
        when(scope.current()).thenReturn(Mono.just(new PatrolScope.Current("north", "another-app")));
        assertTrue(missed.mine(LocalDate.now(), LocalDate.now()).contextWrite(f.identity).block().isEmpty());
        StepVerifier.create(missed.apply(List.of(pending.get(0).getId()), "x", true).contextWrite(f.identity))
                .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
    }
    @Test void rangeUsesMissedCreationDateRatherThanThePreviousDaysTaskEndDate() {
        var yesterday = LocalDate.now().minusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        var task = f.seedTask(1, yesterday + 3600000, yesterday + 7200000);
        missed.synchronizeEnded().block();
        assertEquals(1, missed.mine(LocalDate.now(), LocalDate.now()).contextWrite(f.identity).block().size());
        assertTrue(missed.mine(LocalDate.now().minusDays(1), LocalDate.now().minusDays(1)).contextWrite(f.identity).block().isEmpty());
        assertTrue(f.tasks.findById(task.getId()).block().getMissedInitialized());
    }
    @Test void concurrentSynchronizationAndReviewCannotDuplicateOccurrencesOrCredits() {
        var task = ended(2);
        Mono.when(missed.synchronizeEnded().subscribeOn(reactor.core.scheduler.Schedulers.parallel()),
                missed.synchronizeEnded().subscribeOn(reactor.core.scheduler.Schedulers.parallel())).block();
        var rows = group(task); assertEquals(2, rows.size());
        missed.apply(List.of(rows.get(0).getId()), "原因", false).contextWrite(f.identity).block();
        var pending = decisions(group(task).stream().filter(row -> row.getStatus() == PatrolMissedItemEntity.Status.pending).toList());
        var outcomes = Mono.zip(missed.audit(pending, true, "确认").contextWrite(f.identity)
                        .subscribeOn(reactor.core.scheduler.Schedulers.parallel()).materialize(),
                missed.audit(pending, true, "确认").contextWrite(f.identity)
                        .subscribeOn(reactor.core.scheduler.Schedulers.parallel()).materialize()).block();
        assertTrue(outcomes.getT1().isOnNext() || outcomes.getT2().isOnNext());
        assertEquals(1, f.tasks.findById(task.getId()).block().getCompletedCount());
        assertEquals(2, events.createQuery().count().block());
        assertEquals(0, f.executions.createQuery().count().block());
    }
    @Test void openPeriodsDoNotMaterializeMissedOccurrences() {
        var task = f.seedTask(2);
        assertTrue(group(task).isEmpty());
        assertEquals(0, items.createQuery().count().block());
    }
    @Test void foreignAssetsAndUnassignedUsersCannotListOrApplyMissedItems() {
        var task = ended(1); var own = group(task).get(0);
        assertTrue(missed.mine(LocalDate.now(), LocalDate.now()).contextWrite(bob()).block().isEmpty());
        StepVerifier.create(missed.apply(List.of(own.getId()), "x", true).contextWrite(bob()))
                .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
        f.seedPlan("foreign", "south");
        var foreign = f.tasks.findById(task.getId()).block(); foreign.setId("foreign"); foreign.setTaskGroupId("foreign"); foreign.setPlanId("foreign");
        f.tasks.insert(foreign).block();
        assertTrue(missed.group("foreign").contextWrite(f.identity).block().isEmpty());
        assertEquals(1, items.createQuery().count().block());
        assertEquals(1, missed.mine(LocalDate.now(), LocalDate.now()).contextWrite(f.identity).block().size());
    }
}
