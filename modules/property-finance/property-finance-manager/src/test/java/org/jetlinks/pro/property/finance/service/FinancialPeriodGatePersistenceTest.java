package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.FinancialPeriodControlEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.*;

import java.time.LocalDate;
import java.util.*;

/** 两个真实数据库事务验证月结与收款共享锁序，不以线程睡眠推测竞争发生。 */
@SpringBootTest(
        classes = FinancialPeriodGatePersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FinancialPeriodGatePersistenceTest extends CashierPersistenceTest {
    @Autowired FinancialPeriodGate gate;
    @Autowired ReactiveRepository<FinancialPeriodControlEntity, String> controls;
    @Autowired ReactiveTransactionManager transactionManager;

    TransactionalOperator transaction() {
        return TransactionalOperator.create(transactionManager);
    }

    Mono<Void> closeTo(LocalDate date) {
        return transaction()
                .transactional(
                        gate.lock(project)
                                .flatMap(
                                        control ->
                                                controls.createUpdate()
                                                        .set("closedThrough", date.toString())
                                                        .where("id", control.getId())
                                                        .execute())
                                .then());
    }

    void initialize() {
        transaction().transactional(gate.lock(project)).contextWrite(allowed()).block(WAIT);
    }

    long controlCount() {
        return controls.createQuery().where("projectId", project).count().block(WAIT);
    }

    @Test
    void businessDateCannotBeBypassedByTheCalculationDateAndRejectedWriteRollsBackInitialization() {
        var row = bill("actual-date");
        var request = submission(input(row, "100"));
        var stale =
                new CashierService.Submission(request.requestId(), "0".repeat(64), request.input());
        rejects(cashier.collect(project, stale, DATE), 409);
        assertEquals(0, controlCount());
        closeTo(DATE).contextWrite(allowed()).block(WAIT);
        rejects(cashier.collect(project, request, DATE.plusDays(1)), 409);
        assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void closingIsIsolatedByTenantEvenForTheSameProjectId() {
        closeTo(DATE)
                .contextWrite(allowed())
                .contextWrite(org.jetlinks.pro.tenant.holder.TenantHolder.makeCurrent("tenant-a"))
                .block(WAIT);
        rejects(gate.assertOpen(project, DATE), 500);
        var open =
                transaction()
                        .transactional(gate.assertOpen(project, DATE))
                        .thenReturn("OPEN")
                        .contextWrite(allowed())
                        .contextWrite(
                                org.jetlinks.pro.tenant.holder.TenantHolder.makeCurrent("tenant-b"))
                        .block(WAIT);
        assertEquals("OPEN", open);
        assertEquals(2, controlCount());
        reactor.test.StepVerifier.create(
                        transaction()
                                .transactional(gate.assertOpen(project, DATE))
                                .contextWrite(allowed())
                                .contextWrite(
                                        org.jetlinks.pro.tenant.holder.TenantHolder.makeCurrent(
                                                "tenant-a")))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 409))
                .verify(WAIT);
    }

    @Test
    void previewAndCallsOutsideATransactionCannotCreateControlRows() {
        var row = bill("preview");
        preview(input(row, "100"));
        assertEquals(0, controlCount());
        rejects(gate.lock(project), 500);
        assertEquals(0, controlCount());
        reactor.test.StepVerifier.create(transaction().transactional(gate.lock(project)))
                .expectError()
                .verify(WAIT);
        assertEquals(0, controlCount());
    }

    @Test
    void closedBusinessDateRejectsNewMoneyButOpenPeriodCanCollectHistoricalArrears() {
        var row = bill("closed");
        var oldRequest = submission(input(row, "100"));
        closeTo(DATE).contextWrite(allowed()).block(WAIT);
        rejects(cashier.collect(project, oldRequest, DATE), 409);
        assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                "0.00", bills.findById(row.getId()).block(WAIT).getPaidAmount().toPlainString());
        bills.createUpdate()
                .set("financialClosed", true)
                .where("id", row.getId())
                .execute()
                .block(WAIT);
        var next = input(row, "100");
        next.setPaidAt(DATE.plusDays(1) + "T10:00:00");
        var quote =
                cashier.preview(project, next, DATE.plusDays(1))
                        .contextWrite(allowed())
                        .block(WAIT);
        var request =
                new CashierService.Submission(UUID.randomUUID().toString(), quote.token(), next);
        var paid =
                cashier.collect(project, request, DATE.plusDays(1))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("100.00", paid.payment().getAmount().toPlainString());
        assertEquals(1, controlCount());
    }

    @Test
    void successfulOriginalRequestStillRecoversAfterItsPeriodCloses() {
        var row = bill("replay");
        var request = submission(input(row, "100"));
        var paid = cashier.collect(project, request, DATE).contextWrite(allowed()).block(WAIT);
        closeTo(DATE).contextWrite(allowed()).block(WAIT);
        var recovered =
                cashier.collect(project, request, DATE.plusDays(1))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(paid.payment().getId(), recovered.payment().getId());
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(1, controlCount());
    }

    @Test
    void replayThatStartsBeforeCommitRecoversAfterWaitingForClosingTransaction() {
        var row = bill("waiting-replay");
        var request = submission(input(row, "100"));
        var paidButNotCommitted = Sinks.<Void>one();
        var replayMissedUncommittedPayment = Sinks.<Void>one();
        var first =
                transaction()
                        .transactional(
                                gate.lock(project)
                                        .then(cashier.collect(project, request, DATE))
                                        .flatMap(
                                                paid ->
                                                        Mono.fromRunnable(
                                                                        paidButNotCommitted
                                                                                ::tryEmitEmpty)
                                                                .then(
                                                                        replayMissedUncommittedPayment
                                                                                .asMono())
                                                                .then(
                                                                        gate.lock(project)
                                                                                .flatMap(
                                                                                        control ->
                                                                                                controls.createUpdate()
                                                                                                        .set(
                                                                                                                "closedThrough",
                                                                                                                DATE
                                                                                                                        .toString())
                                                                                                        .where(
                                                                                                                "id",
                                                                                                                control
                                                                                                                        .getId())
                                                                                                        .execute()))
                                                                .thenReturn(
                                                                        paid.payment().getId())));
        var replay =
                payments.createQuery()
                        .where("projectId", project)
                        .fetchOne()
                        .doOnSuccess(
                                payment -> {
                                    if (payment == null)
                                        replayMissedUncommittedPayment.tryEmitEmpty();
                                })
                        .map(payment -> payment.getId());
        var second =
                paidButNotCommitted
                        .asMono()
                        .then(
                                transaction()
                                        .transactional(
                                                gate.inOpenPeriod(
                                                        project,
                                                        DATE,
                                                        replay,
                                                        () ->
                                                                Mono.error(
                                                                        new AssertionError(
                                                                                "A committed"
                                                                                    + " original"
                                                                                    + " request"
                                                                                    + " must be"
                                                                                    + " recovered")))));
        var result = Mono.zip(first, second).contextWrite(allowed()).block(WAIT);
        assertEquals(result.getT1(), result.getT2());
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void closingFirstRejectsAConcurrentBackdatedCollection() {
        initialize();
        var row = bill("closing-first");
        var request = submission(input(row, "100"));
        var acquired = Sinks.<Void>one();
        var collectionStarted = Sinks.<Void>one();
        var closing =
                transaction()
                        .transactional(
                                gate.lock(project)
                                        .flatMap(
                                                control ->
                                                        Mono.fromRunnable(acquired::tryEmitEmpty)
                                                                .then(collectionStarted.asMono())
                                                                .then(
                                                                        controls.createUpdate()
                                                                                .set(
                                                                                        "closedThrough",
                                                                                        DATE
                                                                                                .toString())
                                                                                .where(
                                                                                        "id",
                                                                                        control
                                                                                                .getId())
                                                                                .execute())))
                        .thenReturn("CLOSED");
        var collection =
                acquired.asMono()
                        .then(
                                cashier.collect(project, request, DATE)
                                        .doOnSubscribe(ignored -> collectionStarted.tryEmitEmpty()))
                        .materialize();
        var result = Mono.zip(closing, collection).contextWrite(allowed()).block(WAIT);
        assertEquals("CLOSED", result.getT1());
        assertTrue(result.getT2().isOnError());
        assertTrue(TaxItemPersistenceTest.status(result.getT2().getThrowable(), 409));
        assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void collectionFirstCommitsBeforeClosingCanReadThePeriod() {
        initialize();
        var row = bill("collection-first");
        var request = submission(input(row, "100"));
        var acquired = Sinks.<Void>one();
        var closingStarted = Sinks.<Void>one();
        var collection =
                transaction()
                        .transactional(
                                gate.lock(project)
                                        .then(Mono.fromRunnable(acquired::tryEmitEmpty))
                                        .then(closingStarted.asMono())
                                        .then(cashier.collect(project, request, DATE)));
        var closing =
                acquired.asMono()
                        .then(
                                transaction()
                                        .transactional(
                                                gate.lock(project)
                                                        .flatMap(
                                                                control ->
                                                                        payments.createQuery()
                                                                                .where(
                                                                                        "projectId",
                                                                                        project)
                                                                                .count()
                                                                                .flatMap(
                                                                                        count -> {
                                                                                            assertEquals(
                                                                                                    1,
                                                                                                    count);
                                                                                            return controls.createUpdate()
                                                                                                    .set(
                                                                                                            "closedThrough",
                                                                                                            DATE
                                                                                                                    .toString())
                                                                                                    .where(
                                                                                                            "id",
                                                                                                            control
                                                                                                                    .getId())
                                                                                                    .execute();
                                                                                        }))
                                                        .doOnSubscribe(
                                                                ignored ->
                                                                        closingStarted
                                                                                .tryEmitEmpty())));
        var result = Mono.zip(collection, closing).contextWrite(allowed()).block(WAIT);
        assertEquals("100.00", result.getT1().payment().getAmount().toPlainString());
        assertEquals(1, result.getT2());
    }

    @Test
    void firstConcurrentRequestUsesOneControlAndPreservesSuccessfulReplay() {
        var row = bill("first-race");
        var request = submission(input(row, "100"));
        var results =
                Flux.merge(
                                cashier.collect(project, request, DATE),
                                cashier.collect(project, request, DATE))
                        .collectList()
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(2, results.size());
        assertEquals(results.get(0).payment().getId(), results.get(1).payment().getId());
        assertEquals(1, controlCount());
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void sameProjectWritesSerializeButDifferentProjectsDoNotBlockEachOther() {
        initialize();
        var writes =
                Flux.range(0, 20)
                        .flatMap(
                                index ->
                                        transaction()
                                                .transactional(
                                                        gate.lock(project)
                                                                .flatMap(
                                                                        control ->
                                                                                controls.createUpdate()
                                                                                        .set(
                                                                                                "version",
                                                                                                control
                                                                                                                .getVersion()
                                                                                                        + 1)
                                                                                        .where(
                                                                                                "id",
                                                                                                control
                                                                                                        .getId())
                                                                                        .execute())),
                                8);
        assertEquals(20, writes.reduce(0, Integer::sum).contextWrite(allowed()).block(WAIT));
        assertEquals(
                20L,
                controls.createQuery()
                        .where("projectId", project)
                        .fetchOne()
                        .block(WAIT)
                        .getVersion());
        var both = Sinks.<Void>one();
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var independent =
                Flux.fromIterable(List.of(project, project + "-other"))
                        .flatMap(
                                id ->
                                        transaction()
                                                .transactional(
                                                        gate.lock(id)
                                                                .flatMap(
                                                                        control -> {
                                                                            if (count
                                                                                            .incrementAndGet()
                                                                                    == 2)
                                                                                both.tryEmitEmpty();
                                                                            return both.asMono()
                                                                                    .thenReturn(
                                                                                            control
                                                                                                    .getProjectId());
                                                                        })),
                                2)
                        .collectList()
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(2, independent.size());
    }

    @Configuration
    @Import(CashierPersistenceTest.Config.class)
    static class Config {}
}
