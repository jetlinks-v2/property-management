package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.*;

/** 月结准备使用真实资金和交账事实，不能把无权限来源过滤后声称全期已审核。 */
@SpringBootTest(
        classes = MonthClosingPreparationPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MonthClosingPreparationPersistenceTest extends HandoverPersistenceTest {
    @Autowired MonthClosingPreparation preparation;
    @Autowired ReactiveRepository<FundEntryEntity, String> entries;
    @Autowired ReactiveRepository<HandoverLineEntity, String> frozenLines;

    MonthClosingPreparation.Period period() {
        return new MonthClosingPreparation.Period(
                "2024-02", LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29));
    }

    MonthClosingPreparation.Result prepare() {
        return preparation.prepare(project, period()).contextWrite(allowed()).block(WAIT);
    }

    @Test
    void wholePeriodReadinessTracksReviewAndReleasedBindingsWithoutWriting() {
        var p = paid("period", "12.34");
        var first = prepare();
        assertEquals(1, first.sources().size());
        assertEquals(1, first.unhandedCount());
        assertFalse(first.allReviewed());
        assertEquals("12.34", first.amount());
        assertEquals("12.34", first.cashAmount());
        assertEquals("NOT_HANDED", first.sources().get(0).reviewState());
        var submitted = confirm(request(handoverInput(p)));
        var pending = prepare();
        assertEquals(0, pending.unhandedCount());
        assertEquals(1, pending.pendingReviewCount());
        assertEquals("PENDING_REVIEW", pending.sources().get(0).reviewState());
        assertNotEquals(first.token(), pending.token());
        var approved = act(submitted.batch(), "APPROVE");
        var ready = prepare();
        assertTrue(ready.allReviewed());
        assertEquals(approved.batch().getId(), ready.sources().get(0).handoverId());
        assertNotEquals(pending.token(), ready.token());
        assertEquals(ready.token(), prepare().token());
        assertEquals(2, handoverLogs.createQuery().where("projectId", project).count().block(WAIT));
        var unapproved = act(approved.batch(), "UNAPPROVE");
        act(unapproved.batch(), "CANCEL");
        var released = prepare();
        assertEquals("NOT_HANDED", released.sources().get(0).reviewState());
        assertEquals(1, released.unhandedCount());
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(1, handovers.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void periodBoundariesIncludeLastInstantAndDoNotDefaultToNaturalMonth() {
        var before = paid("before", "1");
        var inside = paid("inside", "2");
        var after = paid("after", "3");
        payments.createUpdate()
                .set("paidAt", "2024-01-15T00:00:00")
                .where("id", before.getId())
                .execute()
                .block(WAIT);
        payments.createUpdate()
                .set("paidAt", "2024-02-14T23:59:59.999999")
                .where("id", inside.getId())
                .execute()
                .block(WAIT);
        payments.createUpdate()
                .set("paidAt", "2024-02-15T00:00:00")
                .where("id", after.getId())
                .execute()
                .block(WAIT);
        var custom =
                new MonthClosingPreparation.Period(
                        "2024-02", LocalDate.of(2024, 1, 16), LocalDate.of(2024, 2, 14));
        var result = preparation.prepare(project, custom).contextWrite(allowed()).block(WAIT);
        assertEquals(
                List.of(inside.getId()),
                result.sources().stream().map(MonthClosingPreparation.PreparedSource::id).toList());
        assertEquals("2.00", result.amount());
    }

    @Test
    void anyForbiddenRoomRejectsWholePreviewInsteadOfShrinkingTotals() {
        var first = bill("one");
        var second = bill("two");
        bills.createUpdate()
                .set("spaceId", "hidden")
                .where("id", second.getId())
                .execute()
                .block(WAIT);
        second.setSpaceId("hidden");
        var input = input(first, "100");
        input.setItems(List.of(input.getItems().get(0), input(second, "100").getItems().get(0)));
        input.setTenders(
                List.of(
                        new org.jetlinks.pro.property.finance.payment.CashierInput.Tender(
                                "1", null, new java.math.BigDecimal("200"))));
        cashier.collect(project, submission(input), DATE).contextWrite(allowed()).block(WAIT);
        var context = allowed();
        var holder = context.<AssetsHolder>get(AssetsHolder.class);
        when(holder.assertPermission(anyString(), anyCollection(), any()))
                .thenAnswer(
                        call ->
                                call.getArgument(1, Collection.class).contains("hidden")
                                        ? Mono.error(FinanceScope.error("permission_denied", 403))
                                        : Mono.empty());
        StepVerifier.create(preparation.prepare(project, period()).contextWrite(context))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        assertEquals(0, handovers.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void damagedActiveClaimCannotMasqueradeAsReviewedOrUnhanded() {
        var p = paid("damaged", "8");
        confirm(request(handoverInput(p)));
        claims.createUpdate()
                .set("batchId", "missing-batch")
                .where("projectId", project)
                .execute()
                .block(WAIT);
        rejects(preparation.prepare(project, period()), 409);
    }

    @Test
    void bindingToAnUnrelatedApprovedBatchCannotGrantReadiness() {
        var first = paid("original", "8");
        var second = paid("unrelated", "9");
        act(confirm(request(handoverInput(first))).batch(), "APPROVE");
        var other = act(confirm(request(handoverInput(second))).batch(), "APPROVE");
        claims.createUpdate()
                .set("batchId", other.batch().getId())
                .where("projectId", project)
                .and("sourceId", first.getId())
                .execute()
                .block(WAIT);
        rejects(preparation.prepare(project, period()), 409);
    }

    @Test
    void inconsistentFrozenAmountsCannotGrantReadiness() {
        var payment = paid("changed-snapshot", "8");
        var approved = act(confirm(request(handoverInput(payment))).batch(), "APPROVE");
        frozenLines
                .createUpdate()
                .set("cashAmount", new java.math.BigDecimal("7"))
                .where("batchId", approved.batch().getId())
                .execute()
                .block(WAIT);
        rejects(preparation.prepare(project, period()), 409);
    }

    @Test
    void tenantClaimsDoNotGrantApprovalToAnotherTenantAndAnonymousCannotRead() {
        var p = paid("tenant", "4");
        var submitted = confirm(request(handoverInput(p)));
        act(submitted.batch(), "APPROVE");
        assertTrue(prepare().allReviewed());
        claims.createUpdate()
                .set("tenantId", "other-tenant")
                .where("projectId", project)
                .execute()
                .block(WAIT);
        assertEquals(1, prepare().unhandedCount());
        StepVerifier.create(preparation.prepare(project, period())).expectError().verify(WAIT);
    }

    @Test
    void emptyPeriodReadIsStableAndDoesNotCreateAnyFinancialRecords() {
        var a = prepare();
        assertTrue(a.sources().isEmpty());
        assertEquals("0.00", a.amount());
        assertEquals("0.00", a.cashAmount());
        assertEquals(a.token(), prepare().token());
        assertEquals(0, handovers.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, handoverLogs.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, claims.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, entries.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void sourceOverflowIsRejectedInsteadOfReportingTruncatedTotals() {
        var template = paid("overflow", "1");
        var data = new ArrayList<PaymentEntity>();
        for (int index = 0; index < 10000; index++) {
            var row = new PaymentEntity();
            org.springframework.beans.BeanUtils.copyProperties(template, row);
            row.setId(UUID.randomUUID().toString());
            data.add(row);
        }
        reactor.core.publisher.Flux.fromIterable(data)
                .buffer(1000)
                .concatMap(payments::insertBatch)
                .then()
                .block(WAIT);
        StepVerifier.create(preparation.prepare(project, period()).contextWrite(allowed()))
                .expectErrorMatches(
                        e ->
                                TaxItemPersistenceTest.status(e, 409)
                                        && e.getMessage().contains("month_closing_source_limit"))
                .verify(WAIT);
        assertEquals(10001, payments.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, handovers.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void representativePeriodIncludesEverySourceAndPreservesExactTotals() {
        var template = paid("volume", "1");
        var lineTemplate =
                lines.createQuery().where("paymentId", template.getId()).fetchOne().block(WAIT);
        var data = new ArrayList<PaymentEntity>();
        var allocations = new ArrayList<PaymentAllocationEntity>();
        for (int index = 0; index < 128; index++) {
            var payment = new PaymentEntity();
            org.springframework.beans.BeanUtils.copyProperties(template, payment);
            payment.setId(UUID.randomUUID().toString());
            data.add(payment);
            var line = new PaymentAllocationEntity();
            org.springframework.beans.BeanUtils.copyProperties(lineTemplate, line);
            line.setId(UUID.randomUUID().toString());
            line.setPaymentId(payment.getId());
            allocations.add(line);
        }
        payments.insertBatch(data).block(WAIT);
        lines.insertBatch(allocations).block(WAIT);
        var begin = System.nanoTime();
        var result = prepare();
        System.out.println(
                "Monthly preparation 129 whole sources: "
                        + (System.nanoTime() - begin) / 1_000_000
                        + "ms");
        assertEquals(129, result.sources().size());
        assertEquals(129, result.unhandedCount());
        assertEquals("129.00", result.amount());
        assertEquals("129.00", result.cashAmount());
        assertEquals(129, payments.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, handovers.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void invalidPeriodIsRejectedBeforeReadingSources() {
        rejects(
                preparation.prepare(
                        project,
                        new MonthClosingPreparation.Period(
                                "2024-13", period().start(), period().end())),
                400);
        rejects(
                preparation.prepare(
                        project,
                        new MonthClosingPreparation.Period(
                                "2024-02", period().end(), period().start())),
                400);
        rejects(preparation.prepare(project, null), 400);
    }

    @Configuration
    @Import({HandoverPersistenceTest.Config.class, MonthClosingPreparation.class})
    static class Config {}
}
