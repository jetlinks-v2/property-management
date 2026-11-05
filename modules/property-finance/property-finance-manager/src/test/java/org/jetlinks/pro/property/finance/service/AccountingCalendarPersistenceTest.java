package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.InvoiceCompanyPersistenceTest.auth;
import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.calculation.AccountingCalendarRules;
import org.jetlinks.pro.property.finance.entity.AccountingCalendarOperationEntity;
import org.jetlinks.pro.property.finance.entity.AccountingPeriodEntity;
import org.jetlinks.pro.property.finance.entity.FinancialPeriodControlEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

/** 隔离PostgreSQL验证年度目录并发与整年回滚，不使用开发业务库。 */
@SpringBootTest(
        classes = AccountingCalendarPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AccountingCalendarPersistenceTest {
    static final Duration WAIT = Duration.ofSeconds(30);
    static final GenericContainer<?> DB =
            new GenericContainer<>("postgres:16-alpine")
                    .withEnv("POSTGRES_PASSWORD", "test-password")
                    .withEnv("POSTGRES_DB", "calendar_test")
                    .withExposedPorts(5432);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.start();
        registry.add(
                "spring.r2dbc.url",
                () -> "r2dbc:postgresql://127.0.0.1:" + DB.getMappedPort(5432) + "/calendar_test");
        registry.add("spring.r2dbc.username", () -> "postgres");
        registry.add("spring.r2dbc.password", () -> "test-password");
        registry.add("easyorm.dialect", () -> "postgres");
        registry.add("easyorm.default-schema", () -> "public");
    }

    @Autowired AccountingCalendarService service;
    @Autowired AccountingCalendarRules rules;
    @Autowired FinancialPeriodTestSupport periods;
    @Autowired ReactiveRepository<AccountingPeriodEntity, String> rows;
    @Autowired ReactiveRepository<AccountingCalendarOperationEntity, String> logs;
    @Autowired ReactiveRepository<FinancialPeriodControlEntity, String> controls;
    String project;

    @BeforeEach
    void project() {
        project = UUID.randomUUID().toString();
    }

    AccountingCalendarRules.Input input(int day) {
        return new AccountingCalendarRules.Input(2024, "CUSTOM", day);
    }

    @Test
    void queriesAndMissingDetailDoNotCreateCalendarOrControl() {
        assertTrue(service.year(project, 2024).contextWrite(auth()).block(WAIT).isEmpty());
        reject(service.period(project, "2024-01"), 404);
        reject(service.year("forbidden", 2024), 403);
        reject(service.create("forbidden", input(10)), 403);
        reject(service.create(project, new AccountingCalendarRules.Input(2024, "CUSTOM", 29)), 400);
        StepVerifier.create(service.year(project, 2024))
                .expectError(
                        org.hswebframework.web.authorization.exception.UnAuthorizedException.class)
                .verify(WAIT);
        assertEquals(0, rows.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, controls.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void createsActualCalendarAndIdenticalRequestKeepsStableRowsAndSingleAudit() {
        var created = service.create(project, input(10)).contextWrite(auth()).block(WAIT);
        assertEquals(12, created.size());
        var expected = rules.generate(input(10));
        for (int index = 0; index < 12; index++) {
            var row = created.get(index);
            assertEquals(expected.get(index).month(), row.getAccountMonth());
            assertEquals(expected.get(index).start().toString(), row.getStartDate());
            assertEquals(expected.get(index).end().toString(), row.getEndDate());
            assertEquals("OPEN", row.getStatus());
            assertEquals(0L, row.getVersion());
            assertEquals(
                    row.getId(),
                    service.period(project, row.getAccountMonth())
                            .contextWrite(auth())
                            .block(WAIT)
                            .getId());
        }
        var replay = service.create(project, input(10)).contextWrite(auth()).block(WAIT);
        assertEquals(
                created.stream().map(AccountingPeriodEntity::getId).toList(),
                replay.stream().map(AccountingPeriodEntity::getId).toList());
        assertEquals(1, logs.createQuery().where("projectId", project).count().block(WAIT));
        var audit = logs.createQuery().where("projectId", project).fetchOne().block(WAIT);
        assertEquals("CREATE", audit.getAction());
        var snapshot = com.alibaba.fastjson.JSON.parseArray(audit.getDetailJson());
        assertEquals(12, snapshot.size());
        assertEquals("2024-02-09", snapshot.getJSONObject(0).getString("end"));
        assertEquals(created.get(0).getId(), snapshot.getJSONObject(0).getString("id"));
        reject(service.create(project, input(20)), 409);
        assertEquals(12, rows.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void concurrentIdenticalCreationReturnsOneYearWithOneAudit() {
        var result =
                Flux.range(0, 8)
                        .flatMap(ignored -> service.create(project, input(20)), 8)
                        .collectList()
                        .contextWrite(auth())
                        .block(WAIT);
        assertEquals(8, result.size());
        var ids = result.get(0).stream().map(AccountingPeriodEntity::getId).toList();
        assertTrue(
                result.stream()
                        .allMatch(
                                year ->
                                        ids.equals(
                                                year.stream()
                                                        .map(AccountingPeriodEntity::getId)
                                                        .toList())));
        assertEquals(12, rows.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(1, logs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void concurrentDifferentCalendarsCannotOverwriteTheWinner() {
        var result =
                Flux.merge(
                                service.create(project, input(10)).materialize(),
                                service.create(project, input(20)).materialize())
                        .collectList()
                        .contextWrite(auth())
                        .block(WAIT);
        assertEquals(1, result.stream().filter(signal -> signal.isOnNext()).count());
        assertEquals(
                1,
                result.stream()
                        .filter(
                                signal ->
                                        signal.isOnError()
                                                && TaxItemPersistenceTest.status(
                                                        signal.getThrowable(), 409))
                        .count());
        assertEquals(12, rows.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(1, logs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void tenantAndProjectArePartOfEveryCalendarIdentity() {
        var first =
                service.create(project, input(10))
                        .contextWrite(auth())
                        .contextWrite(
                                org.jetlinks.pro.tenant.holder.TenantHolder.makeCurrent("one"))
                        .block(WAIT);
        var second =
                service.create(project, input(20))
                        .contextWrite(auth())
                        .contextWrite(
                                org.jetlinks.pro.tenant.holder.TenantHolder.makeCurrent("two"))
                        .block(WAIT);
        assertNotEquals(first.get(0).getId(), second.get(0).getId());
        assertTrue(service.year(project, 2024).contextWrite(auth()).block(WAIT).isEmpty());
        assertTrue(
                service.year("other-project", 2024)
                        .contextWrite(auth())
                        .contextWrite(
                                org.jetlinks.pro.tenant.holder.TenantHolder.makeCurrent("one"))
                        .block(WAIT)
                        .isEmpty());
        assertEquals(
                first.get(0).getId(),
                service.period(project, "2024-01")
                        .contextWrite(auth())
                        .contextWrite(
                                org.jetlinks.pro.tenant.holder.TenantHolder.makeCurrent("one"))
                        .block(WAIT)
                        .getId());
    }

    @Test
    void closedPeriodAllowsIdenticalRestorationButRejectsNewBackdatedCalendar() {
        var created = service.create(project, input(10)).contextWrite(auth()).block(WAIT);
        periods.close(project, LocalDate.of(2024, 12, 31)).contextWrite(auth()).block(WAIT);
        var replay = service.create(project, input(10)).contextWrite(auth()).block(WAIT);
        assertEquals(created.get(0).getId(), replay.get(0).getId());
        FinancialPeriodTestSupport.rejectsClosed(
                service.create(project, new AccountingCalendarRules.Input(2023, "NATURAL", null))
                        .contextWrite(auth()));
        assertTrue(service.year(project, 2023).contextWrite(auth()).block(WAIT).isEmpty());
        assertEquals(1, logs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void deletedOrDamagedPeriodCannotBeSilentlyRecreated() {
        var created = service.create(project, input(10)).contextWrite(auth()).block(WAIT);
        rows.createUpdate()
                .set("deleted", true)
                .where("id", created.get(0).getId())
                .execute()
                .block(WAIT);
        reject(service.create(project, input(10)), 409);
        rows.createUpdate()
                .set("deleted", false)
                .set("endDate", "2024-02-08")
                .where("id", created.get(0).getId())
                .execute()
                .block(WAIT);
        reject(service.create(project, input(10)), 409);
        assertEquals(12, rows.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(1, logs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void uniqueBusinessKeyRejectsAlternateIdAndMissingMonthCannotBeRepairedByCreate() {
        var created = service.create(project, input(10)).contextWrite(auth()).block(WAIT);
        var duplicate = new AccountingPeriodEntity();
        org.springframework.beans.BeanUtils.copyProperties(created.get(0), duplicate);
        duplicate.setId(UUID.randomUUID().toString());
        StepVerifier.create(rows.insert(duplicate)).expectError().verify(WAIT);
        assertEquals(12, rows.createQuery().where("projectId", project).count().block(WAIT));
        rows.deleteById(created.get(11).getId()).block(WAIT);
        reject(service.create(project, input(10)), 409);
        assertEquals(11, rows.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(1, logs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void auditFailureRollsBackAllPeriodsAndFirstControlInitialization() throws Exception {
        var constraint = "calendar_fail_" + UUID.randomUUID().toString().replace("-", "");
        var added =
                DB.execInContainer(
                        "psql",
                        "-U",
                        "postgres",
                        "-d",
                        "calendar_test",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-c",
                        "ALTER TABLE pm_fin_calendar_log ADD CONSTRAINT "
                                + constraint
                                + " CHECK (project_id <> '"
                                + project
                                + "')");
        assertEquals(0, added.getExitCode(), added.getStderr());
        try {
            StepVerifier.create(service.create(project, input(10)).contextWrite(auth()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(0, rows.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(0, logs.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(0, controls.createQuery().where("projectId", project).count().block(WAIT));
        } finally {
            var removed =
                    DB.execInContainer(
                            "psql",
                            "-U",
                            "postgres",
                            "-d",
                            "calendar_test",
                            "-c",
                            "ALTER TABLE pm_fin_calendar_log DROP CONSTRAINT " + constraint);
            assertEquals(0, removed.getExitCode(), removed.getStderr());
        }
    }

    void reject(Mono<?> action, int status) {
        StepVerifier.create(action.contextWrite(auth()))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, status))
                .verify(WAIT);
    }

    @Configuration
    @Import({
        StandardPersistenceTest.Config.class,
        AccountingCalendarRules.class,
        AccountingCalendarService.class
    })
    static class Config {}
}
