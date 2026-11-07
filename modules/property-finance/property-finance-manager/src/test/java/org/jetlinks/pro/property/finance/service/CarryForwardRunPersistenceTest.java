package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.fastjson.JSON;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.core.publisher.*;
import reactor.test.StepVerifier;

import java.time.*;
import java.util.*;

/** 真实数据库验证租约、游标和失败重试，金融写入沿用单笔结转事务。 */
@SpringBootTest(
        classes = CarryForwardRunPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CarryForwardRunPersistenceTest extends CarryForwardApplicationPersistenceTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        HouseSettingPersistenceTest.database(r);
    }

    @Autowired CarryForwardRunStore store;
    @Autowired CarryForwardRunService runner;
    @Autowired ReactiveRepository<CarryForwardRunEntity, String> runs;
    @Autowired ReactiveRepository<CarryForwardWorkEntity, String> works;
    final Instant now = Instant.parse("2024-02-15T06:00:00Z");

    <T> T authorized(Mono<T> action) {
        return action.contextWrite(fixture.fixture.allowed()).block(WAIT);
    }

    CarryForwardRunEntity execute() {
        return authorized(runner.executeDue(project, plan.getId(), now));
    }

    @Test
    void automaticRunSkipsBalanceFromAnotherStandard() {
        var a = topup("150.00");
        accounts.createUpdate().set("standardId", "other-standard")
                .where("id", a.getId()).execute().block(WAIT);
        var result = execute();
        assertEquals("SUCCEEDED", result.getStatus());
        assertEquals(1, result.getSkipped());
        money("150.00", accounts.findById(a.getId()).block(WAIT).getBalance());
        money("100.00", currentBill().getArrears());
        money("0.00", currentBill().getFeeReductionAmount());
        assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void unfinishedLegacyRunWithNoStandardCannotExpandItsScan() {
        var run = authorized(runner.prepare(project, plan.getId(), now));
        input.setStandardIds(List.of());
        runs.createUpdate().set("configJson", JSON.toJSONString(input))
                .where("id", run.getId()).execute().block(WAIT);
        var owner = store.claim(run.getId()).block(WAIT);
        StepVerifier.create(store.stagePage(run.getId(), owner, 2))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 400)).verify(WAIT);
        assertEquals(0, works.createQuery().where("runId", run.getId()).count().block(WAIT));
        money("100.00", currentBill().getArrears());
    }

    @Test
    void selectedStandardFiltersScannedBillsAndDirectApplicationWithoutChangingFunds() {
        var a = topup("150.00");
        input.setStandardIds(List.of("other-standard"));
        plans.createUpdate()
                .set("configJson", JSON.toJSONString(input))
                .where("id", plan.getId())
                .execute()
                .block(WAIT);
        var result = execute();
        assertEquals("SUCCEEDED", result.getStatus());
        assertEquals(0, works.createQuery().where("runId", result.getId()).count().block(WAIT));
        StepVerifier.create(
                        service.apply(project, request(a.getId()), DATE)
                                .contextWrite(fixture.fixture.allowed()))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 409))
                .verify(WAIT);
        StepVerifier.create(
                        service.apply(project, request(null), DATE)
                                .contextWrite(fixture.fixture.allowed()))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 409))
                .verify(WAIT);
        money("150.00", accounts.findById(a.getId()).block(WAIT).getBalance());
        money("100.00", currentBill().getArrears());
        input.setStandardIds(List.of(bill.getStandardId()));
        plans.createUpdate()
                .set("configJson", JSON.toJSONString(input))
                .where("id", plan.getId())
                .execute()
                .block(WAIT);
        money("90.00", run(request(a.getId())).getAmount());
        money("60.00", accounts.findById(a.getId()).block(WAIT).getBalance());
    }

    @Test
    void competingInstancesUseOneSlotAndCollectOnlyOnce() {
        var a = topup("150.00");
        var results =
                Flux.merge(
                                runner.executeDue(project, plan.getId(), now),
                                runner.executeDue(project, plan.getId(), now))
                        .contextWrite(fixture.fixture.allowed())
                        .collectList()
                        .block(WAIT);
        assertEquals(results.get(0).getId(), results.get(1).getId());
        var result = execute();
        assertEquals("SUCCEEDED", result.getStatus());
        money("60.00", accounts.findById(a.getId()).block(WAIT).getBalance());
        assertEquals(1, runs.createQuery().where("planId", plan.getId()).count().block(WAIT));
        assertEquals(1, items.createQuery().where("planId", plan.getId()).count().block(WAIT));
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void interruptionAfterMoneyCommittedReplaysPersistedWorkWithoutDoubleDebit() {
        var a = topup("150.00");
        var run = authorized(runner.prepare(project, plan.getId(), now));
        var owner = store.claim(run.getId()).block(WAIT);
        var page = store.stagePage(run.getId(), owner, 2).block(WAIT);
        assertEquals(1, page.size());
        var work = page.get(0);
        var accountIds = store.accounts(run, work).block(WAIT);
        assertEquals(List.of(a.getId()), accountIds);
        service.apply(
                        project,
                        new CarryForwardApplyService.Request(
                                plan.getId(),
                                plan.getVersion(),
                                run.getId(),
                                bill.getId(),
                                a.getId(),
                                run.getSlot()),
                        DATE)
                .contextWrite(fixture.fixture.allowed())
                .block(WAIT);
        runs.createUpdate().set("leaseUntil", 0L).where("id", run.getId()).execute().block(WAIT);
        var recovered = authorized(runner.resume(project, run.getId()));
        assertEquals("SUCCEEDED", recovered.getStatus());
        money("60.00", accounts.findById(a.getId()).block(WAIT).getBalance());
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals("SUCCEEDED", works.findById(work.getId()).block(WAIT).getStatus());
        StepVerifier.create(store.heartbeat(run.getId(), owner))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 409))
                .verify(WAIT);
    }

    @Test
    void keysetPagesDoNotLoseBillsWhenEarlierBillsStopMatchingAndNewBillsArrive() {
        for (int i = 0; i < 4; i++) {
            var row = fixture.fixture.bill("page" + i);
            bills.createUpdate()
                    .set("subjectId", bill.getSubjectId())
                    .set("standardId", bill.getStandardId())
                    .where("id", row.getId())
                    .execute()
                    .block(WAIT);
        }
        var run = authorized(runner.prepare(project, plan.getId(), now));
        var owner = store.claim(run.getId()).block(WAIT);
        var seen = new HashSet<String>();
        for (int page = 0; page < 3; page++) {
            var rows = store.stagePage(run.getId(), owner, 2).block(WAIT);
            for (var w : rows) {
                assertTrue(seen.add(w.getReceivableId()));
                bills.createUpdate()
                        .set("arrears", new java.math.BigDecimal("0.00"))
                        .where("id", w.getReceivableId())
                        .execute()
                        .block(WAIT);
            }
            if (page == 0) {
                var later = fixture.fixture.bill("created-later");
                bills.createUpdate()
                        .set("subjectId", bill.getSubjectId())
                        .set("standardId", bill.getStandardId())
                        .set("createTime", run.getCreateTime() + 10_000)
                        .where("id", later.getId())
                        .execute()
                        .block(WAIT);
            }
        }
        assertEquals(5, seen.size());
        assertTrue(store.stagePage(run.getId(), owner, 2).block(WAIT).isEmpty());
        assertEquals(5, works.createQuery().where("runId", run.getId()).count().block(WAIT));
    }

    @Test
    void sqlFailureIsSeparateFromFinancialFactAndExplicitRetryPreservesRequest() throws Exception {
        var a = topup("150.00");
        String constraint = "carry_work_" + UUID.randomUUID().toString().replace("-", "");
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
                                "ALTER TABLE pm_fin_carry_item ADD CONSTRAINT "
                                        + constraint
                                        + " CHECK (receivable_id <> '"
                                        + bill.getId()
                                        + "')")
                        .getExitCode());
        CarryForwardRunEntity first;
        try {
            first = execute();
            assertEquals("PARTIAL", first.getStatus());
            assertEquals(1, first.getFailed());
            assertEquals(0, items.createQuery().where("runId", first.getId()).count().block(WAIT));
            money("150.00", accounts.findById(a.getId()).block(WAIT).getBalance());
            var w = works.createQuery().where("runId", first.getId()).fetchOne().block(WAIT);
            assertEquals("FAILED", w.getStatus());
            assertEquals("error.property_finance_carry_run_failed", w.getErrorCode());
            assertFalse(JSON.toJSONString(w).contains(constraint));
        } finally {
            HouseSettingPersistenceTest.DB.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "setting_test",
                    "-c",
                    "ALTER TABLE pm_fin_carry_item DROP CONSTRAINT " + constraint);
        }
        var retry = authorized(runner.retry(project, first.getId()));
        assertEquals(first.getId(), retry.getId());
        assertEquals("SUCCEEDED", retry.getStatus());
        assertEquals(0, retry.getFailed());
        money("60.00", accounts.findById(a.getId()).block(WAIT).getBalance());
    }

    @Test
    void changedOrDisabledPlanCancelsUnfinishedRunInsteadOfUsingOldDiscount() {
        var run = authorized(runner.prepare(project, plan.getId(), now));
        plans.createUpdate()
                .set("version", plan.getVersion() + 1)
                .where("id", plan.getId())
                .execute()
                .block(WAIT);
        var result = authorized(runner.resume(project, run.getId()));
        assertEquals("CANCELLED", result.getStatus());
        assertEquals("error.property_finance_carry_plan_changed", result.getErrorCode());
        assertEquals(0, items.createQuery().where("runId", run.getId()).count().block(WAIT));
    }

    @Test
    void noBalanceFullRemissionRunsWithoutInventingCashAndNoBalancePartialIsSkipped() {
        var skipped = execute();
        assertEquals("SUCCEEDED", skipped.getStatus());
        assertEquals(1, skipped.getSkipped());
        money("100.00", currentBill().getArrears());
        input.setRemissionValue(new java.math.BigDecimal("200.00"));
        plans.createUpdate()
                .set("configJson", JSON.toJSONString(input))
                .where("id", plan.getId())
                .execute()
                .block(WAIT);
        var full = authorized(runner.executeDue(project, plan.getId(), now.plusSeconds(86400)));
        assertEquals("SUCCEEDED", full.getStatus());
        assertEquals(1, full.getSucceeded());
        money("0.00", currentBill().getArrears());
        assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void allSelectedCustomerBalancesCanPayOneBillWithoutSplittingIt() {
        sharedCustomers();
        topup("30.00");
        var secondInput = fixture.input("TOPUP", "70.00");
        secondInput.setRelationshipId("second-link");
        fixture.run(secondInput);
        var run = execute();
        assertEquals("SUCCEEDED", run.getStatus());
        money("0.00", currentBill().getArrears());
        money("90.00", currentBill().getPaidAmount());
        money("10.00", totalBalance());
        assertEquals(2, items.createQuery().where("runId", run.getId()).count().block(WAIT));
        assertEquals("SUCCEEDED", authorized(runner.resume(project, run.getId())).getStatus());
        money("90.00", currentBill().getPaidAmount());
    }

    @Test
    void settlementStopsBeforeAttemptingAnotherSelectedAccount() {
        sharedCustomers();
        topup("150.00");
        var second = fixture.input("TOPUP", "200.00");
        second.setRelationshipId("second-link");
        fixture.run(second);
        var run = execute();
        assertEquals("SUCCEEDED", run.getStatus());
        assertEquals(0, run.getFailed());
        money("0.00", currentBill().getArrears());
        money("260.00", totalBalance());
        assertEquals(1, items.createQuery().where("runId", run.getId()).count().block(WAIT));
    }

    private java.math.BigDecimal totalBalance() {
        return accounts.createQuery()
                .where("projectId", project)
                .fetch()
                .map(FundAccountEntity::getBalance)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                .block(WAIT);
    }

    private void sharedCustomers() {
        var base = fixture.setting.context("room");
        var customers = new ArrayList<>(base.customers());
        customers.add(
                new org.jetlinks.pro.property.base.archive.PropertyHouseCustomer(
                        "second-link",
                        "second",
                        "second-user",
                        "person",
                        "张女士",
                        "456",
                        "tenant",
                        1L,
                        1L,
                        1L));
        org.mockito.Mockito.when(
                        archive.context(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(
                        Mono.just(
                                new org.jetlinks.pro.property.base.archive.PropertyHouseContext(
                                        base.house(), customers)));
        var snapshot = JSON.parseObject(bill.getSnapshotJson());
        snapshot.put(
                "customers",
                customers.stream()
                        .map(
                                c ->
                                        Map.of(
                                                "customerId",
                                                c.customerId(),
                                                "relationshipId",
                                                c.relationshipId(),
                                                "name",
                                                c.name()))
                        .toList());
        bills.createUpdate()
                .set("snapshotJson", snapshot.toJSONString())
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
    }

    @Test
    void candidateInsertFailureRollsBackTheScanningCursor() throws Exception {
        var run = authorized(runner.prepare(project, plan.getId(), now));
        var owner = store.claim(run.getId()).block(WAIT);
        var constraint = "carry_page_" + UUID.randomUUID().toString().replace("-", "");
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
                                "ALTER TABLE pm_fin_carry_work ADD CONSTRAINT "
                                        + constraint
                                        + " CHECK (run_id <> '"
                                        + run.getId()
                                        + "')")
                        .getExitCode());
        try {
            StepVerifier.create(store.stagePage(run.getId(), owner, 2))
                    .expectErrorMatches(e -> causedBy(e, constraint))
                    .verify(WAIT);
            assertNull(runs.findById(run.getId()).block(WAIT).getCursorId());
            assertEquals(0, works.createQuery().where("runId", run.getId()).count().block(WAIT));
        } finally {
            HouseSettingPersistenceTest.DB.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "setting_test",
                    "-c",
                    "ALTER TABLE pm_fin_carry_work DROP CONSTRAINT " + constraint);
        }
        assertEquals(1, store.stagePage(run.getId(), owner, 2).block(WAIT).size());
    }

    @Test
    void resultWriteFailureLeavesPendingWorkAndRecoveryDoesNotDebitAgain() throws Exception {
        var a = topup("150.00");
        var run = authorized(runner.prepare(project, plan.getId(), now));
        var constraint = "carry_outcome_" + UUID.randomUUID().toString().replace("-", "");
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
                                "ALTER TABLE pm_fin_carry_work ADD CONSTRAINT "
                                        + constraint
                                        + " CHECK (run_id <> '"
                                        + run.getId()
                                        + "' OR status <> 'SUCCEEDED')")
                        .getExitCode());
        try {
            StepVerifier.create(
                            runner.resume(project, run.getId())
                                    .contextWrite(fixture.fixture.allowed()))
                    .expectErrorMatches(e -> causedBy(e, constraint))
                    .verify(WAIT);
            money("60.00", accounts.findById(a.getId()).block(WAIT).getBalance());
            assertEquals(
                    "PENDING",
                    works.createQuery()
                            .where("runId", run.getId())
                            .fetchOne()
                            .block(WAIT)
                            .getStatus());
            assertEquals("RUNNING", runs.findById(run.getId()).block(WAIT).getStatus());
        } finally {
            HouseSettingPersistenceTest.DB.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "setting_test",
                    "-c",
                    "ALTER TABLE pm_fin_carry_work DROP CONSTRAINT " + constraint);
        }
        runs.createUpdate().set("leaseUntil", 0L).where("id", run.getId()).execute().block(WAIT);
        assertEquals("SUCCEEDED", authorized(runner.resume(project, run.getId())).getStatus());
        money("60.00", accounts.findById(a.getId()).block(WAIT).getBalance());
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void notYetDueDoesNotCreateRunAndCallerWithoutAuthenticationCannotResume() {
        assertNull(
                authorized(
                        runner.prepare(
                                project, plan.getId(), Instant.parse("2024-02-15T04:59:59Z"))));
        assertEquals(0, runs.createQuery().where("planId", plan.getId()).count().block(WAIT));
        var run = authorized(runner.prepare(project, plan.getId(), now));
        StepVerifier.create(runner.resume(project, run.getId())).expectError().verify(WAIT);
        fixture.rejects(runner.resume("another-project", run.getId()), 404);
    }

    @Configuration
    @Import({
        CarryForwardApplicationPersistenceTest.Config.class,
        CarryForwardRunStore.class,
        CarryForwardRunService.class
    })
    static class Config {}
}
