package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.*;
import org.hswebframework.web.authorization.simple.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.web.MonthClosingScheduleController;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.*;
import reactor.util.context.Context;

import java.time.*;
import java.util.*;

@SpringBootTest(
        classes = MonthClosingSchedulePersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MonthClosingSchedulePersistenceTest extends MonthClosingPersistenceTest {
    @Autowired MonthClosingScheduleService schedule;
    @Autowired MonthClosingAutomaticService automatic;
    @Autowired MonthClosingScheduleController controller;
    @Autowired ReactiveRepository<MonthClosingScheduleEntity, String> plans;
    @Autowired ReactiveRepository<MonthClosingAutomaticRunEntity, String> runs;
    @Autowired ReactiveRepository<MonthClosingScheduleLogEntity, String> scheduleLogs;
    CarryForwardAuthorityTest fixture;
    SimpleAuthentication executor;
    MonthClosingJob job;
    final Instant now = Instant.parse("2024-02-01T01:00:00Z");

    @BeforeEach
    void task() {
        plans.createUpdate().set("enabled", false).where("enabled", true).execute().block(WAIT);
        fixture = new CarryForwardAuthorityTest();
        fixture.holders();
        fixture.rights.put("saved", Set.of("room", "other-room"));
        executor = fixture.auth("saved");
        executor.setPermissions(
                List.of(
                        SimplePermission.builder()
                                .id("property-finance-month-closing")
                                .actions(Set.of("save", "close"))
                                .build()));
        job =
                new MonthClosingJob(
                        plans,
                        automatic,
                        new MonthClosingJobProperties(),
                        new BillingJobProperties(),
                        id -> Mono.just(executor));
    }

    @AfterEach
    void cleanup() {
        job.stop();
        fixture.close();
    }

    Context context() {
        return allowed().put(Authentication.class, executor);
    }

    MonthClosingScheduleService.View save(Long version, boolean enabled) {
        return schedule.save(
                        project,
                        new MonthClosingScheduleService.Input(
                                version, enabled, "MONTH_END", null, "月结调度设置"))
                .contextWrite(context())
                .block(WAIT);
    }

    MonthClosingScheduleEntity plan() {
        return plans.createQuery().where("projectId", project).fetchOne().block(WAIT);
    }

    String januaryStatus() {
        return calendar.period(project, "2024-01").contextWrite(allowed()).block(WAIT).getStatus();
    }

    @Test
    void defaultsNeverCreateDirectoryOrConfigAndEnabledEmptyProjectDoesNotCreateCalendar() {
        var view = schedule.get(project).contextWrite(context()).block(WAIT);
        assertFalse(view.enabled());
        assertNull(view.version());
        assertNull(plan());
        save(null, true);
        assertEquals(0, job.poll(now).block(WAIT).succeeded());
        assertEquals(
                0, accountingPeriods.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, runs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void completeEndDayIsPreservedThenOneCloseIsAtomicAndRepeatedScansDoNotDuplicate() {
        year();
        save(null, true);
        assertEquals(0, job.poll(Instant.parse("2024-01-31T15:59:59Z")).block(WAIT).succeeded());
        assertEquals("OPEN", januaryStatus());
        var result =
                job.poll(now)
                        .contextWrite(
                                org.jetlinks.pro.tenant.holder.TenantHolder.makeCurrent("wrong"))
                        .block(WAIT);
        assertEquals(1, result.succeeded(), job.getLastError());
        assertEquals("CLOSED", januaryStatus());
        assertEquals(0, job.poll(now).block(WAIT).succeeded());
        assertEquals(1, runs.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                1, closingOperations.createQuery().where("projectId", project).count().block(WAIT));
        var run = runs.createQuery().where("projectId", project).fetchOne().block(WAIT);
        assertEquals("2024-01", run.getAccountMonth());
        assertEquals("SUCCEEDED", run.getStatus());
        assertNotNull(run.getOperationId());
        assertEquals("user", run.getModifierId());
    }

    @Test
    void twoNodesSerializeOnDurablePlanAndSecondDoesNotCloseAnotherPeriodInSameTick() {
        year();
        save(null, true);
        var other =
                new MonthClosingJob(
                        plans,
                        automatic,
                        new MonthClosingJobProperties(),
                        new BillingJobProperties(),
                        id -> Mono.just(executor));
        var results = Flux.merge(job.poll(now), other.poll(now)).collectList().block(WAIT);
        assertEquals(1, results.stream().mapToInt(MonthClosingJob.Summary::succeeded).sum());
        assertEquals(1, runs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void revokedCurrentPermissionRecordsFailureAndWaitsOneHourBeforeRetry() {
        year();
        save(null, true);
        executor.setPermissions(List.of());
        assertEquals(1, job.poll(now).block(WAIT).failed());
        assertEquals("OPEN", januaryStatus());
        assertEquals("error.property_finance_month_schedule_authority_invalid", job.getLastError());
        assertEquals(0, job.poll(now.plusSeconds(3599)).block(WAIT).failed());
        assertEquals(1, job.poll(now.plusSeconds(3600)).block(WAIT).failed());
        assertEquals(2, runs.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                0, closingOperations.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void unhandedPaymentCannotBeAutoClosedAndFailureRollsBackAllFlags() {
        paid("auto-unhanded", "9");
        january();
        save(null, true);
        var due = Instant.parse("2024-03-01T01:00:00Z");
        assertEquals(1, job.poll(due).block(WAIT).failed());
        assertEquals(
                "OPEN",
                calendar.period(project, "2024-02")
                        .contextWrite(allowed())
                        .block(WAIT)
                        .getStatus());
        assertEquals(
                1, closingOperations.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                "FAILED",
                runs.createQuery().where("projectId", project).fetchOne().block(WAIT).getStatus());
    }

    @Test
    void staleConcurrentEditsConflictAndDisablingDoesNotRequireClosePermission() {
        var input = new MonthClosingScheduleService.Input(null, true, "DAY", 5, "首次调度");
        var result =
                Flux.merge(
                                schedule.save(project, input).materialize(),
                                schedule.save(project, input).materialize())
                        .collectList()
                        .contextWrite(context())
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
        executor.setPermissions(
                List.of(
                        SimplePermission.builder()
                                .id("property-finance-month-closing")
                                .actions(Set.of("save"))
                                .build()));
        assertFalse(save(0L, false).enabled());
        assertEquals(0, job.poll(now).block(WAIT).succeeded());
        assertEquals(2, scheduleLogs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void manualReopenRemainsOpenUntilScheduleIsExplicitlySavedAgain() {
        year();
        save(null, true);
        assertEquals(1, job.poll(now).block(WAIT).succeeded());
        closing.submit(project, "2024-01", "REOPEN", submission("2024-01", "REOPEN"), END)
                .contextWrite(allowed())
                .block(WAIT);
        assertEquals(1, job.poll(now.plusSeconds(60)).block(WAIT).failed());
        assertEquals("error.property_finance_month_schedule_reopened", job.getLastError());
        save(0L, true);
        assertEquals(1, job.poll(now.plusSeconds(61)).block(WAIT).succeeded());
    }

    @Test
    void httpReturnsSafeViewsRejectsMalformedAndForcesProjectOutsideClientOr() {
        var client =
                WebTestClient.bindToController(controller)
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) -> chain.filter(exchange).contextWrite(context()))
                        .build();
        var root = "/property/finance/projects/" + project + "/month-closing";
        client.put()
                .uri(root + "/schedule")
                .bodyValue(Map.of("enabled", true, "mode", "DAY", "day", 31, "reason", "bad"))
                .exchange()
                .expectStatus()
                .isBadRequest();
        client.put()
                .uri(root + "/schedule")
                .bodyValue(
                        Map.of(
                                "enabled",
                                true,
                                "mode",
                                "DAY",
                                "day",
                                5,
                                "reason",
                                "HTTP",
                                "executorSnapshot",
                                "fake",
                                "nextAttemptAt",
                                999999))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(true)
                .jsonPath("$.executorSnapshot")
                .doesNotExist()
                .jsonPath("$.nextAttemptAt")
                .doesNotExist();
        assertNotEquals("fake", plan().getExecutorSnapshot());
        assertEquals(0L, plan().getNextAttemptAt());
        year();
        job.poll(now.plusSeconds(5 * 86400)).block(WAIT);
        var q = new QueryParamEntity();
        q.toQuery().where("projectId", "other").or("projectId", project);
        client.post()
                .uri(root + "/automatic-runs/_query")
                .bodyValue(q)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1)
                .jsonPath("$.data[0].tenantId")
                .doesNotExist();
        client.post()
                .uri("/property/finance/projects/other/month-closing/automatic-runs/_query")
                .bodyValue(q)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(0);
        client.post().uri(root + "/schedule/_execute").exchange().expectStatus().isNotFound();
    }

    @Test
    void finalTaskJournalFailureRollsBackFinancialCloseBeforeFailureSummaryIsPersisted()
            throws Exception {
        var payment = paid("auto-rollback", "9");
        var handover = act(confirm(request(handoverInput(payment))).batch(), "APPROVE").batch();
        january();
        save(null, true);
        var due = Instant.parse("2024-03-01T01:00:00Z");
        var constraint = "auto_fail_" + UUID.randomUUID().toString().replace("-", "");
        var db = HouseSettingPersistenceTest.DB;
        assertEquals(
                0,
                db.execInContainer(
                                "psql",
                                "-U",
                                "postgres",
                                "-d",
                                "setting_test",
                                "-c",
                                "ALTER TABLE pm_fin_month_auto_run ADD CONSTRAINT "
                                        + constraint
                                        + " CHECK (project_id <> '"
                                        + project
                                        + "' OR status <> 'SUCCEEDED')")
                        .getExitCode());
        try {
            assertEquals(1, job.poll(due).block(WAIT).failed());
            assertEquals(
                    "OPEN",
                    calendar.period(project, "2024-02")
                            .contextWrite(allowed())
                            .block(WAIT)
                            .getStatus());
            assertFalse(handovers.findById(handover.getId()).block(WAIT).getMonthClosed());
            assertEquals(
                    payment.getAmount(),
                    payments.findById(payment.getId()).block(WAIT).getAmount());
            assertEquals(
                    1,
                    closingOperations
                            .createQuery()
                            .where("projectId", project)
                            .count()
                            .block(WAIT));
            assertEquals(
                    "FAILED",
                    runs.createQuery()
                            .where("projectId", project)
                            .fetchOne()
                            .block(WAIT)
                            .getStatus());
            assertEquals(
                    "2024-01-31",
                    controls.createQuery()
                            .where("projectId", project)
                            .fetchOne()
                            .block(WAIT)
                            .getClosedThrough());
        } finally {
            db.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "setting_test",
                    "-c",
                    "ALTER TABLE pm_fin_month_auto_run DROP CONSTRAINT " + constraint);
        }
    }

    @Test
    void customCalendarDoesNotUseLabelMonthForTheSchedulingDate() {
        calendar.create(
                        project,
                        new org.jetlinks.pro.property.finance.calculation.AccountingCalendarRules
                                .Input(2024, "CUSTOM", 10))
                .contextWrite(allowed())
                .block(WAIT);
        schedule.save(
                        project,
                        new MonthClosingScheduleService.Input(null, true, "DAY", 5, "自定义期末之后执行"))
                .contextWrite(context())
                .block(WAIT);
        assertEquals(0, job.poll(Instant.parse("2024-02-05T01:00:00Z")).block(WAIT).succeeded());
        assertEquals(0, job.poll(Instant.parse("2024-03-04T15:59:59Z")).block(WAIT).succeeded());
        assertEquals(1, job.poll(Instant.parse("2024-03-05T01:00:00Z")).block(WAIT).succeeded());
        assertEquals(
                "2024-02-09",
                controls.createQuery()
                        .where("projectId", project)
                        .fetchOne()
                        .block(WAIT)
                        .getClosedThrough());
    }

    @Test
    void currentSpaceRevocationCannotBeBypassedBySavedOrAmbientHolder() {
        bill("revoke-space");
        january();
        save(null, true);
        fixture.rights.put("saved", Set.of("other-room"));
        assertEquals(
                1,
                job.poll(Instant.parse("2024-03-01T01:00:00Z"))
                        .contextWrite(context())
                        .block(WAIT)
                        .failed());
        assertFalse(
                bills.createQuery()
                        .where("projectId", project)
                        .fetchOne()
                        .block(WAIT)
                        .getFinancialClosed());
        assertEquals(
                "OPEN",
                calendar.period(project, "2024-02")
                        .contextWrite(allowed())
                        .block(WAIT)
                        .getStatus());
    }

    @Test
    void enablingNeedsClosePermissionButFreshQueryAndDisablingNeverImpersonateAnExecutor() {
        executor.setPermissions(
                List.of(
                        SimplePermission.builder()
                                .id("property-finance-month-closing")
                                .actions(Set.of("save"))
                                .build()));
        reactor.test.StepVerifier.create(
                        schedule.save(
                                        project,
                                        new MonthClosingScheduleService.Input(
                                                null, true, "MONTH_END", null, "no close"))
                                .contextWrite(context()))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        assertNull(plan());
        assertEquals(0, save(null, false).version());
        reactor.test.StepVerifier.create(schedule.get(project)).expectError().verify(WAIT);
    }

    @Test
    void finalConfigurationAuditFailureRollsBackNewScheduleAndControl() throws Exception {
        var db = HouseSettingPersistenceTest.DB;
        var constraint = "schedule_fail_" + UUID.randomUUID().toString().replace("-", "");
        assertEquals(
                0,
                db.execInContainer(
                                "psql",
                                "-U",
                                "postgres",
                                "-d",
                                "setting_test",
                                "-c",
                                "ALTER TABLE pm_fin_month_schedule_log ADD CONSTRAINT "
                                        + constraint
                                        + " CHECK (project_id <> '"
                                        + project
                                        + "')")
                        .getExitCode());
        try {
            reactor.test.StepVerifier.create(
                            schedule.save(
                                            project,
                                            new MonthClosingScheduleService.Input(
                                                    null, true, "MONTH_END", null, "审计回滚"))
                                    .contextWrite(context()))
                    .expectError()
                    .verify(WAIT);
            assertNull(plan());
            assertEquals(0, controls.createQuery().where("projectId", project).count().block(WAIT));
        } finally {
            db.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "setting_test",
                    "-c",
                    "ALTER TABLE pm_fin_month_schedule_log DROP CONSTRAINT " + constraint);
        }
    }

    @Test
    void scanCrossesPageBoundaryAndLifecycleExposesMBeanWithoutAnonymousHttp() throws Exception {
        String snapshot =
                FinanceJobAuthority.capture(executor, MonthClosingScheduleService.AUTHORITY_ERROR)
                        .contextWrite(context())
                        .block(WAIT);
        for (int i = 0; i < 33; i++) {
            var plan = new MonthClosingScheduleEntity();
            plan.setId(UUID.randomUUID().toString());
            InvoiceCompanyService.init(plan, executor);
            plan.setProjectId(UUID.randomUUID().toString());
            plan.setTenantId("");
            plan.setEnabled(true);
            plan.setMode("MONTH_END");
            plan.setNextAttemptAt(0L);
            plan.setExecutorSnapshot(snapshot);
            plans.insert(plan).block(WAIT);
        }
        assertEquals(33, job.poll(now).block(WAIT).skipped());
        var disabledProperties = new MonthClosingJobProperties();
        disabledProperties.setEnabled(false);
        var disabled =
                new MonthClosingJob(
                        plans,
                        automatic,
                        disabledProperties,
                        new BillingJobProperties(),
                        id -> Mono.just(executor));
        disabled.start();
        assertFalse(disabled.isRunning());
        disabled.stop();
        job.start();
        assertTrue(job.isRunning());
        var objectName =
                new javax.management.ObjectName(
                        "org.jetlinks:type=PropertyManagement,name=MonthClosingJob");
        assertTrue(
                java.lang.management.ManagementFactory.getPlatformMBeanServer()
                        .isRegistered(objectName));
        job.stop();
        assertFalse(
                java.lang.management.ManagementFactory.getPlatformMBeanServer()
                        .isRegistered(objectName));
    }

    @Test
    void unavailableFailureJournalIsVisibleAndDoesNotStarveOtherProjects() throws Exception {
        year();
        save(null, true);
        var other = new MonthClosingScheduleEntity();
        org.springframework.beans.BeanUtils.copyProperties(plan(), other);
        other.setId(UUID.randomUUID().toString());
        other.setProjectId(UUID.randomUUID().toString());
        plans.insert(other).block(WAIT);
        executor.setPermissions(List.of());
        var db = HouseSettingPersistenceTest.DB;
        var constraint = "failed_record_" + UUID.randomUUID().toString().replace("-", "");
        assertEquals(
                0,
                db.execInContainer(
                                "psql",
                                "-U",
                                "postgres",
                                "-d",
                                "setting_test",
                                "-c",
                                "ALTER TABLE pm_fin_month_auto_run ADD CONSTRAINT "
                                        + constraint
                                        + " CHECK (project_id <> '"
                                        + project
                                        + "')")
                        .getExitCode());
        try {
            assertEquals(2, job.poll(now).block(WAIT).failed());
            assertEquals(0, runs.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(
                    1,
                    runs.createQuery()
                            .where("projectId", other.getProjectId())
                            .count()
                            .block(WAIT));
            assertEquals("OPEN", januaryStatus());
            assertTrue(job.getFailureCount() >= 2);
        } finally {
            db.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "setting_test",
                    "-c",
                    "ALTER TABLE pm_fin_month_auto_run DROP CONSTRAINT " + constraint);
        }
    }

    @Configuration
    @Import({
        MonthClosingPersistenceTest.Config.class,
        MonthClosingScheduleService.class,
        MonthClosingAutomaticService.class,
        MonthClosingScheduleController.class
    })
    static class Config {}
}
