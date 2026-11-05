package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.HouseSettingPersistenceTest.DB;
import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.calculation.AccountingCalendarRules;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.*;

/** 月结与来源冻结在真实PG事务里发布，不以准备器成功代替结账成功。 */
@SpringBootTest(
        classes = MonthClosingPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MonthClosingPersistenceTest extends HandoverPersistenceTest {
    static final LocalDate END = LocalDate.of(2024, 2, 29);
    @Autowired MonthClosingService closing;
    @Autowired AccountingCalendarService calendar;
    @Autowired ReactiveRepository<AccountingPeriodEntity, String> accountingPeriods;
    @Autowired ReactiveRepository<FinancialPeriodControlEntity, String> controls;
    @Autowired ReactiveRepository<MonthClosingOperationEntity, String> closingOperations;
    @Autowired ReactiveRepository<MonthClosingMemberEntity, String> closingMembers;

    void year() {
        calendar.create(project, new AccountingCalendarRules.Input(2024, "NATURAL", 1))
                .contextWrite(allowed())
                .block(WAIT);
    }

    MonthClosingService.Submission submission(String month, String action) {
        var view = closing.preview(project, month, action, END).contextWrite(allowed()).block(WAIT);
        return new MonthClosingService.Submission(
                UUID.randomUUID().toString(), view.version(), view.token(), "财务核对");
    }

    MonthClosingService.Result close(String month) {
        return closing.submit(project, month, "CLOSE", submission(month, "CLOSE"), END)
                .contextWrite(allowed())
                .block(WAIT);
    }

    void january() {
        year();
        close("2024-01");
    }

    @Test
    void actualPeriodClosesAndReopensAllFactsWithoutChangingMoney() {
        var payment = paid("closing", "12.34");
        var batch = act(confirm(request(handoverInput(payment))).batch(), "APPROVE").batch();
        bills.createUpdate()
                .set("dueDate", "2024-02-15")
                .where("projectId", project)
                .execute()
                .block(WAIT);
        january();
        var request = submission("2024-02", "CLOSE");
        var result =
                closing.submit(project, "2024-02", "CLOSE", request, END)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("CLOSED", result.status());
        assertEquals("12.34", result.amount());
        assertEquals("12.34", result.cashAmount());
        assertTrue(handovers.findById(batch.getId()).block(WAIT).getMonthClosed());
        assertTrue(
                bills.createQuery()
                        .where("projectId", project)
                        .fetchOne()
                        .block(WAIT)
                        .getFinancialClosed());
        assertEquals(
                "2024-02-29",
                controls.createQuery()
                        .where("projectId", project)
                        .fetchOne()
                        .block(WAIT)
                        .getClosedThrough());
        assertEquals(
                result,
                closing.submit(project, "2024-02", "CLOSE", request, END.plusDays(1))
                        .contextWrite(allowed())
                        .block(WAIT));
        assertEquals(
                2, closingOperations.createQuery().where("projectId", project).count().block(WAIT));
        var reopened =
                closing.submit(project, "2024-02", "REOPEN", submission("2024-02", "REOPEN"), END)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("OPEN", reopened.status());
        assertFalse(handovers.findById(batch.getId()).block(WAIT).getMonthClosed());
        assertFalse(
                bills.createQuery()
                        .where("projectId", project)
                        .fetchOne()
                        .block(WAIT)
                        .getFinancialClosed());
        assertEquals("2024-01-31", reopened.closedThrough());
        assertEquals(
                payment.getAmount(), payments.findById(payment.getId()).block(WAIT).getAmount());
        // 反结不会抹掉原结账审计，原成功请求继续恢复原CLOSED结果。
        assertEquals(
                result,
                closing.submit(project, "2024-02", "CLOSE", request, END)
                        .contextWrite(allowed())
                        .block(WAIT));
    }

    @Test
    void chronologicalClosingAndLatestOnlyReopeningUseDatesRatherThanIds() {
        year();
        rejects(closing.preview(project, "2024-02", "CLOSE", END), 409);
        rejects(closing.preview(project, "2024-01", "CLOSE", LocalDate.of(2024, 1, 30)), 409);
        var first = close("2024-01");
        close("2024-02");
        rejects(closing.preview(project, "2024-01", "REOPEN", END), 409);
        var second =
                closing.submit(project, "2024-02", "REOPEN", submission("2024-02", "REOPEN"), END)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("2024-01-31", second.closedThrough());
        var empty =
                closing.submit(project, "2024-01", "REOPEN", submission("2024-01", "REOPEN"), END)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertNull(empty.closedThrough());
        assertEquals(first.version() + 1, empty.version());
    }

    @Test
    void unhandedPendingAndStalePreviewsCannotCloseAnything() {
        january();
        var stale = submission("2024-02", "CLOSE");
        var payment = paid("unhanded", "5");
        rejects(closing.submit(project, "2024-02", "CLOSE", stale, END), 409);
        var current = submission("2024-02", "CLOSE");
        rejects(closing.submit(project, "2024-02", "CLOSE", current, END), 409);
        var batch = confirm(request(handoverInput(payment))).batch();
        rejects(
                closing.submit(project, "2024-02", "CLOSE", submission("2024-02", "CLOSE"), END),
                409);
        assertFalse(handovers.findById(batch.getId()).block(WAIT).getMonthClosed());
        assertEquals(
                "OPEN",
                calendar.period(project, "2024-02")
                        .contextWrite(allowed())
                        .block(WAIT)
                        .getStatus());
        assertEquals(
                1, closingOperations.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void requirementForReceivableReviewIsEffectiveAndCannotBeBypassedByToken() {
        var row = bill("review");
        bills.createUpdate()
                .set("dueDate", "2024-02-15")
                .where("id", row.getId())
                .execute()
                .block(WAIT);
        january();
        var old = submission("2024-02", "CLOSE");
        controls.createUpdate()
                .set("requireReceivableReview", true)
                .where("projectId", project)
                .execute()
                .block(WAIT);
        rejects(closing.submit(project, "2024-02", "CLOSE", old, END), 409);
        rejects(
                closing.submit(project, "2024-02", "CLOSE", submission("2024-02", "CLOSE"), END),
                409);
        bills.createUpdate()
                .set("reviewState", "APPROVED")
                .where("id", row.getId())
                .execute()
                .block(WAIT);
        assertEquals("CLOSED", close("2024-02").status());
    }

    @Test
    void sameSuccessfulRequestHasOneJournalUnderConcurrencyAndChangedPayloadConflicts() {
        january();
        var request = submission("2024-02", "CLOSE");
        var results =
                Flux.range(0, 6)
                        .flatMap(i -> closing.submit(project, "2024-02", "CLOSE", request, END), 6)
                        .collectList()
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(6, results.size());
        assertTrue(results.stream().allMatch(results.get(0)::equals));
        assertEquals(
                2, closingOperations.createQuery().where("projectId", project).count().block(WAIT));
        rejects(
                closing.submit(
                        project,
                        "2024-02",
                        "CLOSE",
                        new MonthClosingService.Submission(
                                request.requestId(), request.version(), request.token(), "更改原因"),
                        END),
                409);
    }

    @Test
    void finalAuditFailureRollsBackBoundaryStatusMembersAndSourceFlags() throws Exception {
        var payment = paid("rollback-month", "8");
        var batch = act(confirm(request(handoverInput(payment))).batch(), "APPROVE").batch();
        january();
        var request = submission("2024-02", "CLOSE");
        var constraint = "closing_fail_" + UUID.randomUUID().toString().replace("-", "");
        var added =
                DB.execInContainer(
                        "psql",
                        "-U",
                        "postgres",
                        "-d",
                        "setting_test",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-c",
                        "ALTER TABLE pm_fin_month_operation ADD CONSTRAINT "
                                + constraint
                                + " CHECK (project_id <> '"
                                + project
                                + "' OR account_month <> '2024-02')");
        assertEquals(0, added.getExitCode(), added.getStderr());
        try {
            StepVerifier.create(
                            closing.submit(project, "2024-02", "CLOSE", request, END)
                                    .contextWrite(allowed()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(
                    "OPEN",
                    calendar.period(project, "2024-02")
                            .contextWrite(allowed())
                            .block(WAIT)
                            .getStatus());
            assertEquals(
                    "2024-01-31",
                    controls.createQuery()
                            .where("projectId", project)
                            .fetchOne()
                            .block(WAIT)
                            .getClosedThrough());
            assertFalse(handovers.findById(batch.getId()).block(WAIT).getMonthClosed());
            assertEquals(
                    0,
                    closingMembers.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(
                    1,
                    closingOperations
                            .createQuery()
                            .where("projectId", project)
                            .count()
                            .block(WAIT));
        } finally {
            assertEquals(
                    0,
                    DB.execInContainer(
                                    "psql",
                                    "-U",
                                    "postgres",
                                    "-d",
                                    "setting_test",
                                    "-c",
                                    "ALTER TABLE pm_fin_month_operation DROP CONSTRAINT "
                                            + constraint)
                            .getExitCode());
        }
    }

    @Test
    void reopeningPreservesLaterValidPaymentOfHistoricalDebt() {
        var row = bill("historic-debt");
        january();
        close("2024-02");
        var frozen = bills.findById(row.getId()).block(WAIT);
        var nextDay = END.plusDays(1);
        var input = input(frozen, "10");
        input.setPaidAt("2024-03-01T10:00:00");
        var quote = cashier.preview(project, input, nextDay).contextWrite(allowed()).block(WAIT);
        cashier.collect(
                        project,
                        new CashierService.Submission(
                                UUID.randomUUID().toString(), quote.token(), input),
                        nextDay)
                .contextWrite(allowed())
                .block(WAIT);
        var paid = bills.findById(row.getId()).block(WAIT);
        closing.submit(project, "2024-02", "REOPEN", submission("2024-02", "REOPEN"), nextDay)
                .contextWrite(allowed())
                .block(WAIT);
        var reopened = bills.findById(row.getId()).block(WAIT);
        assertFalse(reopened.getFinancialClosed());
        assertEquals(paid.getPaidAmount(), reopened.getPaidAmount());
        assertEquals(paid.getArrears(), reopened.getArrears());
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void everyHouseMustBeAuthorizedEvenWhenOnlyReceivablesExist() {
        var first = bill("authorized");
        var hidden = bill("hidden");
        bills.createUpdate()
                .set("spaceId", "hidden")
                .where("id", hidden.getId())
                .execute()
                .block(WAIT);
        january();
        var ctx = allowed();
        var holder =
                ctx.<org.jetlinks.pro.assets.AssetsHolder>get(
                        org.jetlinks.pro.assets.AssetsHolder.class);
        org.mockito.Mockito.when(
                        holder.assertPermission(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyCollection(),
                                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        call ->
                                call.getArgument(1, Collection.class).contains("hidden")
                                        ? reactor.core.publisher.Mono.error(
                                                FinanceScope.error("forbidden", 403))
                                        : reactor.core.publisher.Mono.empty());
        StepVerifier.create(closing.preview(project, "2024-02", "CLOSE", END).contextWrite(ctx))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 403))
                .verify(WAIT);
        assertFalse(bills.findById(first.getId()).block(WAIT).getFinancialClosed());
        assertFalse(bills.findById(hidden.getId()).block(WAIT).getFinancialClosed());
        assertEquals(
                1, closingOperations.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void successfulReplayAndReopenStillRequireFrozenHousePermission() {
        bill("replay-assets");
        january();
        var request = submission("2024-02", "CLOSE");
        closing.submit(project, "2024-02", "CLOSE", request, END)
                .contextWrite(allowed())
                .block(WAIT);
        var ctx = allowed();
        var holder =
                ctx.<org.jetlinks.pro.assets.AssetsHolder>get(
                        org.jetlinks.pro.assets.AssetsHolder.class);
        org.mockito.Mockito.when(
                        holder.assertPermission(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyCollection(),
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        reactor.core.publisher.Mono.error(FinanceScope.error("forbidden", 403)));
        StepVerifier.create(
                        closing.submit(project, "2024-02", "CLOSE", request, END).contextWrite(ctx))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 403))
                .verify(WAIT);
        StepVerifier.create(closing.preview(project, "2024-02", "REOPEN", END).contextWrite(ctx))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 403))
                .verify(WAIT);
        assertEquals(
                2, closingOperations.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void competingDifferentRequestsCannotPublishTwoClosingsForSamePeriod() {
        january();
        var first = submission("2024-02", "CLOSE");
        var second = submission("2024-02", "CLOSE");
        var attempts =
                Flux.merge(
                                closing.submit(project, "2024-02", "CLOSE", first, END)
                                        .materialize(),
                                closing.submit(project, "2024-02", "CLOSE", second, END)
                                        .materialize())
                        .collectList()
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(1, attempts.stream().filter(reactor.core.publisher.Signal::isOnNext).count());
        assertEquals(
                1,
                attempts.stream()
                        .filter(
                                s ->
                                        s.isOnError()
                                                && TaxItemPersistenceTest.status(
                                                        s.getThrowable(), 409))
                        .count());
        assertEquals(
                2, closingOperations.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void sharedHandoverRemainsClosedUntilEveryLaterOwningPeriodIsReopened() {
        var january = paid("prior-period", "2");
        payments.createUpdate()
                .set("paidAt", "2024-01-15T10:00:00")
                .where("id", january.getId())
                .execute()
                .block(WAIT);
        var february = paid("current-period", "3");
        var batch =
                act(confirm(request(handoverInput(january, february))).batch(), "APPROVE").batch();
        year();
        close("2024-01");
        assertTrue(handovers.findById(batch.getId()).block(WAIT).getMonthClosed());
        close("2024-02");
        closing.submit(project, "2024-02", "REOPEN", submission("2024-02", "REOPEN"), END)
                .contextWrite(allowed())
                .block(WAIT);
        assertTrue(handovers.findById(batch.getId()).block(WAIT).getMonthClosed());
        closing.submit(project, "2024-01", "REOPEN", submission("2024-01", "REOPEN"), END)
                .contextWrite(allowed())
                .block(WAIT);
        assertFalse(handovers.findById(batch.getId()).block(WAIT).getMonthClosed());
    }

    @Test
    void corruptedMembershipCannotBeSilentlyReplayedOrReopened() {
        bill("damaged-member");
        january();
        var request = submission("2024-02", "CLOSE");
        var closed =
                closing.submit(project, "2024-02", "CLOSE", request, END)
                        .contextWrite(allowed())
                        .block(WAIT);
        closingMembers
                .createUpdate()
                .set("deleted", true)
                .where("operationId", closed.operationId())
                .execute()
                .block(WAIT);
        rejects(closing.submit(project, "2024-02", "CLOSE", request, END), 409);
        rejects(closing.preview(project, "2024-02", "REOPEN", END), 409);
        assertEquals(
                "2024-02-29",
                controls.createQuery()
                        .where("projectId", project)
                        .fetchOne()
                        .block(WAIT)
                        .getClosedThrough());
    }

    @Test
    void overlappingOrIncompletePersistedCalendarCannotClose() {
        year();
        var february = calendar.period(project, "2024-02").contextWrite(allowed()).block(WAIT);
        accountingPeriods
                .createUpdate()
                .set("startDate", "2024-01-31")
                .where("id", february.getId())
                .execute()
                .block(WAIT);
        rejects(closing.preview(project, "2024-01", "CLOSE", END), 409);
        assertEquals(
                0, closingOperations.createQuery().where("projectId", project).count().block(WAIT));
        assertNull(
                controls.createQuery()
                        .where("projectId", project)
                        .fetchOne()
                        .block(WAIT)
                        .getClosedThrough());
    }

    @Test
    void finalReopenAuditFailureKeepsEverythingClosed() throws Exception {
        var payment = paid("reopen-rollback", "8");
        var batch = act(confirm(request(handoverInput(payment))).batch(), "APPROVE").batch();
        january();
        close("2024-02");
        var request = submission("2024-02", "REOPEN");
        var constraint = "reopen_fail_" + UUID.randomUUID().toString().replace("-", "");
        var added =
                DB.execInContainer(
                        "psql",
                        "-U",
                        "postgres",
                        "-d",
                        "setting_test",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-c",
                        "ALTER TABLE pm_fin_month_operation ADD CONSTRAINT "
                                + constraint
                                + " CHECK (project_id <> '"
                                + project
                                + "' OR action <> 'REOPEN')");
        assertEquals(0, added.getExitCode(), added.getStderr());
        try {
            StepVerifier.create(
                            closing.submit(project, "2024-02", "REOPEN", request, END)
                                    .contextWrite(allowed()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(
                    "CLOSED",
                    calendar.period(project, "2024-02")
                            .contextWrite(allowed())
                            .block(WAIT)
                            .getStatus());
            assertEquals(
                    "2024-02-29",
                    controls.createQuery()
                            .where("projectId", project)
                            .fetchOne()
                            .block(WAIT)
                            .getClosedThrough());
            assertTrue(handovers.findById(batch.getId()).block(WAIT).getMonthClosed());
            assertTrue(
                    bills.createQuery()
                            .where("projectId", project)
                            .fetchOne()
                            .block(WAIT)
                            .getFinancialClosed());
            assertEquals(
                    2,
                    closingOperations
                            .createQuery()
                            .where("projectId", project)
                            .count()
                            .block(WAIT));
        } finally {
            assertEquals(
                    0,
                    DB.execInContainer(
                                    "psql",
                                    "-U",
                                    "postgres",
                                    "-d",
                                    "setting_test",
                                    "-c",
                                    "ALTER TABLE pm_fin_month_operation DROP CONSTRAINT "
                                            + constraint)
                            .getExitCode());
        }
    }

    @Test
    void corruptedPriorBoundaryCannotBeAppliedDuringReopen() {
        january();
        var result = close("2024-02");
        closingOperations
                .createUpdate()
                .set("previousClosedThrough", "2024-02-15")
                .where("id", result.operationId())
                .execute()
                .block(WAIT);
        rejects(closing.preview(project, "2024-02", "REOPEN", END), 409);
        assertEquals(
                "2024-02-29",
                controls.createQuery()
                        .where("projectId", project)
                        .fetchOne()
                        .block(WAIT)
                        .getClosedThrough());
    }

    @Test
    void receivableSourceLimitIsExplicitAndDoesNotCloseAPartialPeriod() {
        var template = bill("volume-members");
        var copies = new ArrayList<ReceivableEntity>();
        for (int index = 0; index < 10000; index++) {
            var row = new ReceivableEntity();
            org.springframework.beans.BeanUtils.copyProperties(template, row);
            row.setId(UUID.randomUUID().toString());
            row.setBillKey(row.getId());
            copies.add(row);
        }
        Flux.fromIterable(copies).buffer(500).concatMap(bills::insertBatch).then().block(WAIT);
        january();
        rejects(closing.preview(project, "2024-02", "CLOSE", END), 409);
        assertEquals(10001, bills.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                0, closingMembers.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                "OPEN",
                calendar.period(project, "2024-02")
                        .contextWrite(allowed())
                        .block(WAIT)
                        .getStatus());
    }

    @Test
    void customCalendarClosesUsingItsActualEndDateAndBoundary() {
        calendar.create(project, new AccountingCalendarRules.Input(2024, "CUSTOM", 10))
                .contextWrite(allowed())
                .block(WAIT);
        rejects(closing.preview(project, "2024-01", "CLOSE", LocalDate.of(2024, 2, 8)), 409);
        var first = close("2024-01");
        assertEquals("2024-02-09", first.closedThrough());
        rejects(closing.preview(project, "2024-02", "CLOSE", END), 409);
        var next = LocalDate.of(2024, 3, 9);
        var preview =
                closing.preview(project, "2024-02", "CLOSE", next)
                        .contextWrite(allowed())
                        .block(WAIT);
        var result =
                closing.submit(
                                project,
                                "2024-02",
                                "CLOSE",
                                new MonthClosingService.Submission(
                                        UUID.randomUUID().toString(),
                                        preview.version(),
                                        preview.token(),
                                        "自定义账期核对"),
                                next)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("2024-03-09", result.closedThrough());
    }

    @Configuration
    @Import({
        HandoverPersistenceTest.Config.class,
        MonthClosingPreparation.class,
        AccountingCalendarRules.class,
        AccountingCalendarService.class,
        MonthClosingService.class,
        MonthClosingSources.class,
        DocumentStore.class
    })
    @ImportAutoConfiguration(JacksonAutoConfiguration.class)
    static class Config {}
}
