package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.core.publisher.*;

import java.util.*;

/** 生命周期扫描使用真实数据库/金融事务；认证远端与基础资料命令用fixture替代，资产拒绝另有真实holder测试。 */
@SpringBootTest(
        classes = CarryForwardJobPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CarryForwardJobPersistenceTest extends CarryForwardRunPersistenceTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        HouseSettingPersistenceTest.database(r);
    }

    CarryForwardAuthorityTest authorityFixture;
    Authentication executor;
    CarryForwardJob job;

    @BeforeEach
    void scheduler() {
        // 共享PG fixture保留了前例记录；全局扫描只启用本例，避免将旧未授权计划误算成本例失败。
        plans.createUpdate()
                .set("enabled", false)
                .and("id", "not", plan.getId())
                .execute()
                .block(WAIT);
        runs.createUpdate()
                .set("status", "CANCELLED")
                .and("planId", "not", plan.getId())
                .execute()
                .block(WAIT);
        authorityFixture = new CarryForwardAuthorityTest();
        authorityFixture.holders();
        authorityFixture.rights.put("saved", Set.of("room", "other-room"));
        executor = authorityFixture.auth("saved");
        String encoded = CarryForwardAuthority.capture(executor).block(WAIT);
        plans.createUpdate()
                .set("executorSnapshot", encoded)
                .set("tenantId", "saved-tenant")
                .where("id", plan.getId())
                .execute()
                .block(WAIT);
        job =
                new CarryForwardJob(
                        plans,
                        runs,
                        runner,
                        new CarryForwardJobProperties(),
                        id -> Mono.just(executor));
    }

    @AfterEach
    void closeJob() {
        job.stop();
        authorityFixture.close();
    }

    @Test
    void startupScansTodayDueSlotAndUsesPersistedTenantInsteadOfAmbientHttpContext() {
        topup("150.00");
        var tenant = new java.util.concurrent.atomic.AtomicReference<String>();
        when(archive.context(anyString(), anyString(), anyBoolean()))
                .thenAnswer(
                        call ->
                                org.jetlinks.pro.tenant.holder.TenantHolder.currentId()
                                        .doOnNext(tenant::set)
                                        .thenReturn(fixture.setting.context(call.getArgument(1))));
        var summary =
                job.poll(now)
                        .contextWrite(
                                org.jetlinks.pro.tenant.holder.TenantHolder.makeCurrent(
                                        "wrong-tenant"))
                        .block(WAIT);
        assertEquals(
                0,
                summary.failed(),
                () ->
                        works.createQuery()
                                .where("projectId", project)
                                .fetch()
                                .map(w -> w.getStatus() + ":" + w.getErrorCode())
                                .collectList()
                                .block(WAIT)
                                .toString());
        var run = runs.createQuery().where("planId", plan.getId()).fetchOne().block(WAIT);
        assertEquals("SUCCEEDED", run.getStatus());
        assertEquals("saved-tenant", run.getTenantId());
        assertEquals("saved-tenant", tenant.get());
        assertEquals("user", run.getModifierId());
        money("0.00", currentBill().getArrears());
        assertEquals(0, job.poll(now).block(WAIT).failed());
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
        assertTrue(job.getSuccessCount() > 0);
    }

    @Test
    void newJobInstanceResumesDurablePendingPagesBeyondOneBatch() {
        input.setRemissionValue(new java.math.BigDecimal("200.00"));
        plans.createUpdate()
                .set("configJson", com.alibaba.fastjson.JSON.toJSONString(input))
                .where("id", plan.getId())
                .execute()
                .block(WAIT);
        for (int i = 0; i < 34; i++) {
            var row = fixture.fixture.bill("job-page-" + i);
            bills.createUpdate()
                    .set("subjectId", bill.getSubjectId())
                    .set("standardId", bill.getStandardId())
                    .where("id", row.getId())
                    .execute()
                    .block(WAIT);
        }
        var first = job.poll(now).block(WAIT);
        assertEquals(0, first.failed());
        var run = runs.createQuery().where("planId", plan.getId()).fetchOne().block(WAIT);
        assertEquals("PENDING", run.getStatus());
        assertEquals(
                32,
                items.createQuery().where("runId", run.getId()).count().block(WAIT),
                () ->
                        "summary="
                                + first
                                + ", status="
                                + run.getStatus()
                                + ", work="
                                + works.createQuery()
                                        .where("projectId", project)
                                        .fetch()
                                        .map(w -> w.getStatus() + ":" + w.getErrorCode())
                                        .collectList()
                                        .block(WAIT));
        var restarted =
                new CarryForwardJob(
                        plans,
                        runs,
                        runner,
                        new CarryForwardJobProperties(),
                        id -> Mono.just(executor));
        assertEquals(0, restarted.poll(now).block(WAIT).failed());
        assertEquals("SUCCEEDED", runs.findById(run.getId()).block(WAIT).getStatus());
        assertEquals(35, items.createQuery().where("runId", run.getId()).count().block(WAIT));
        assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void revokedFunctionOrDeletedUserNeverPostsAndMonitorRecordsFailure() {
        topup("150.00");
        ((org.hswebframework.web.authorization.simple.SimpleAuthentication) executor)
                .setPermissions(List.of());
        assertTrue(job.poll(now).block(WAIT).failed() > 0);
        assertTrue(job.getFailureCount() > 0);
        assertEquals("error.property_finance_carry_authority_invalid", job.getLastError());
        money("100.00", currentBill().getArrears());
        assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
        var deleted =
                new CarryForwardJob(
                        plans, runs, runner, new CarryForwardJobProperties(), id -> Mono.empty());
        assertTrue(deleted.poll(now).block(WAIT).failed() > 0);
    }

    @Test
    void revokedRealSpaceBindingPreventsScheduledPosting() {
        topup("150.00");
        authorityFixture.rights.put("saved", Set.of("other-room"));
        assertEquals(1, job.poll(now).block(WAIT).failed());
        assertEquals("error.property_finance_carry_run_failed", job.getLastError());
        money("100.00", currentBill().getArrears());
        assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
        var work = works.createQuery().where("projectId", project).fetchOne().block(WAIT);
        assertEquals("FAILED", work.getStatus());
        assertNotNull(work.getErrorCode());
    }

    @Test
    void disabledLifecycleDoesNotRegisterOrStartTimerAndStopIsIdempotent() throws Exception {
        var properties = new CarryForwardJobProperties();
        properties.setEnabled(false);
        var disabled =
                new CarryForwardJob(plans, runs, runner, properties, id -> Mono.just(executor));
        disabled.start();
        assertFalse(disabled.isRunning());
        disabled.stop();
        disabled.stop();
        job.start();
        assertTrue(job.isRunning());
        var name =
                new javax.management.ObjectName(
                        "org.jetlinks:type=PropertyManagement,name=CarryForwardJob");
        assertTrue(
                java.lang.management.ManagementFactory.getPlatformMBeanServer().isRegistered(name));
        job.stop();
        assertFalse(
                java.lang.management.ManagementFactory.getPlatformMBeanServer().isRegistered(name));
        assertFalse(job.isRunning());
    }

    @Configuration
    @Import(CarryForwardRunPersistenceTest.Config.class)
    static class Config {}
}
