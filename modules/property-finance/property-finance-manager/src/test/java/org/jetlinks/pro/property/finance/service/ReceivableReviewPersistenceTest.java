package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.fastjson.JSON;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 按钮审核是独立账务操作，真实PG验证整批、重放、权限与审计回滚。 */
@SpringBootTest(
        classes = ReceivableReviewPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReceivableReviewPersistenceTest extends HouseSettingPersistenceTest {
    @Autowired ReceivableReviewService reviews;
    @Autowired ReactiveRepository<ReceivableReviewOperationEntity, String> reviewOperations;

    ReceivableEntity bill(String space) {
        var saved =
                settings.create(project, input(space, "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        return bills.createQuery()
                .where("settingId", saved.setting().getId())
                .fetchOne()
                .block(WAIT);
    }

    ReceivableReviewService.Input request(String action, ReceivableEntity... rows) {
        return new ReceivableReviewService.Input(
                UUID.randomUUID().toString(),
                action,
                java.util.Arrays.stream(rows)
                        .map(row -> new ReceivableReviewService.Item(row.getId(), row.getVersion()))
                        .toList(),
                "核对应收金额");
    }

    @Test
    void legacyPendingCanBeApprovedRejectedAndUnapprovedWithoutChangingMoney() {
        var bill = bill("room");
        bills.createUpdate().setNull("reviewState").where("id", bill.getId()).execute().block(WAIT);
        var result =
                reviews.submit(project, request("APPROVE", bill), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("APPROVED", result.get(0).getReviewState());
        assertEquals("finance-tester", result.get(0).getReviewedBy());
        assertEquals(DATE.toString(), result.get(0).getReviewedOn());
        var approved = bills.findById(bill.getId()).block(WAIT);
        assertEquals(bill.getActualReceivableAmount(), approved.getActualReceivableAmount());
        assertEquals(bill.getVersion() + 1, approved.getVersion());
        var pending =
                reviews.submit(project, request("UNAPPROVE", approved), DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .get(0);
        assertEquals("PENDING", pending.getReviewState());
        var rejected =
                reviews.submit(
                                project,
                                request("REJECT", bills.findById(pending.getId()).block(WAIT)),
                                DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .get(0);
        assertEquals("REJECTED", rejected.getReviewState());
        assertEquals(
                3,
                receivableLogs
                        .createQuery()
                        .where("receivableId", bill.getId())
                        .count()
                        .block(WAIT));
        var audit =
                receivableLogs
                        .createQuery()
                        .where("receivableId", bill.getId())
                        .and("operation", "APPROVE")
                        .fetchOne()
                        .block(WAIT);
        assertEquals("APPROVED", JSON.parseObject(audit.getAfterJson()).getString("reviewState"));
        assertEquals(
                3, reviewOperations.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void duplicateSuccessfulRequestRestoresOriginalSnapshotAfterClosingWithoutAnotherAudit() {
        var row = bill("room");
        var request = request("APPROVE", row);
        var first =
                reviews.submit(project, request, DATE).contextWrite(allowed()).block(WAIT).get(0);
        financialPeriods.close(project, DATE).contextWrite(allowed()).block(WAIT);
        var replay =
                reviews.submit(project, request, DATE).contextWrite(allowed()).block(WAIT).get(0);
        assertEquals(first.getVersion(), replay.getVersion());
        assertEquals("APPROVED", replay.getReviewState());
        assertEquals(
                1,
                receivableLogs
                        .createQuery()
                        .where("receivableId", row.getId())
                        .count()
                        .block(WAIT));
        var altered =
                new ReceivableReviewService.Input(
                        request.requestId(), "REJECT", request.items(), request.reason());
        rejects(reviews.submit(project, altered, DATE), "receivable_review_request_conflict");
    }

    @Test
    void closedNewReviewLeavesOriginalBillAndAllAuditsUntouched() {
        var row = bill("room");
        financialPeriods.close(project, DATE).contextWrite(allowed()).block(WAIT);
        FinancialPeriodTestSupport.rejectsClosed(
                reviews.submit(project, request("APPROVE", row), DATE).contextWrite(allowed()));
        assertEquals(row.getVersion(), bills.findById(row.getId()).block(WAIT).getVersion());
        assertEquals(
                0, reviewOperations.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                0,
                receivableLogs
                        .createQuery()
                        .where("receivableId", row.getId())
                        .count()
                        .block(WAIT));
    }

    @Test
    void concurrentSameRequestReturnsOneOperationAndOneVersion() {
        var row = bill("room");
        var request = request("APPROVE", row);
        var result =
                Flux.range(0, 6)
                        .flatMap(ignored -> reviews.submit(project, request, DATE), 6)
                        .collectList()
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(6, result.size());
        assertTrue(
                result.stream()
                        .allMatch(items -> items.get(0).getVersion().equals(row.getVersion() + 1)));
        assertEquals(
                1, reviewOperations.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                1,
                receivableLogs
                        .createQuery()
                        .where("receivableId", row.getId())
                        .count()
                        .block(WAIT));
    }

    @Test
    void entireBatchValidatesLastVersionBeforeWritingFirstRow() {
        var first = bill("room");
        var last = bill("job");
        var request =
                new ReceivableReviewService.Input(
                        UUID.randomUUID().toString(),
                        "APPROVE",
                        List.of(
                                new ReceivableReviewService.Item(first.getId(), first.getVersion()),
                                new ReceivableReviewService.Item(last.getId(), 999L)),
                        "核对");
        rejects(reviews.submit(project, request, DATE), "version_conflict");
        for (var row : List.of(first, last)) {
            assertEquals(row.getVersion(), bills.findById(row.getId()).block(WAIT).getVersion());
            assertEquals(
                    0,
                    receivableLogs
                            .createQuery()
                            .where("receivableId", row.getId())
                            .count()
                            .block(WAIT));
        }
        assertEquals(
                0, reviewOperations.createQuery().where("projectId", project).count().block(WAIT));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "financialClosed",
                "frozen",
                "split",
                "discountLocked",
                "bankCollectionLocked",
                "paymentLocked",
                "deleted"
            })
    void protectedBillCannotBeReviewed(String field) {
        var row = bill("room");
        bills.createUpdate().set(field, true).where("id", row.getId()).execute().block(WAIT);
        StepVerifier.create(
                        reviews.submit(project, request("APPROVE", row), DATE)
                                .contextWrite(allowed()))
                .expectErrorMatches(
                        error ->
                                TaxItemPersistenceTest.status(error, 409)
                                        || TaxItemPersistenceTest.status(error, 404))
                .verify(WAIT);
        assertEquals(row.getVersion(), bills.findById(row.getId()).block(WAIT).getVersion());
        assertEquals(
                0, reviewOperations.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void partialPaymentPreventsUnapprovalAndOccupationPreventsApproval() {
        var row = bill("room");
        var approved =
                reviews.submit(project, request("APPROVE", row), DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .get(0);
        bills.createUpdate()
                .set("paidAmount", new BigDecimal("1.00"))
                .where("id", row.getId())
                .execute()
                .block(WAIT);
        rejects(
                reviews.submit(
                        project,
                        request("UNAPPROVE", bills.findById(approved.getId()).block(WAIT)),
                        DATE),
                "receivable_review_denied");
        var other = bill("job");
        bills.createUpdate()
                .set("invoiceOccupiedAmount", new BigDecimal("1.00"))
                .where("id", other.getId())
                .execute()
                .block(WAIT);
        rejects(
                reviews.submit(project, request("APPROVE", other), DATE),
                "receivable_review_denied");
    }

    @Test
    void requestReplayStillChecksCurrentProjectAndEverySpacePermission() {
        var row = bill("room");
        var request = request("APPROVE", row);
        reviews.submit(project, request, DATE).contextWrite(allowed()).block(WAIT);
        var holder = org.mockito.Mockito.mock(org.jetlinks.pro.assets.AssetsHolder.class);
        org.mockito.Mockito.when(
                        holder.assertPermission(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyCollection(),
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.error(FinanceScope.error("forbidden", 403)));
        StepVerifier.create(
                        reviews.submit(project, request, DATE)
                                .contextWrite(
                                        allowed()
                                                .put(
                                                        org.jetlinks.pro.assets.AssetsHolder.class,
                                                        holder)))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 403))
                .verify(WAIT);
        StepVerifier.create(reviews.submit("forbidden", request, DATE).contextWrite(allowed()))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 403))
                .verify(WAIT);
        assertEquals(
                1, reviewOperations.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void multiBillReviewReturnsEveryIdentityAndCanBeReplayed() {
        var first = bill("room");
        var second = bill("job");
        var request = request("APPROVE", first, second);
        var result = reviews.submit(project, request, DATE).contextWrite(allowed()).block(WAIT);
        assertEquals(2, result.size());
        assertEquals(
                java.util.Set.of(first.getId(), second.getId()),
                result.stream()
                        .map(ReceivableReviewService.Result::getId)
                        .collect(java.util.stream.Collectors.toSet()));
        for (var row : List.of(first, second)) {
            assertEquals("APPROVED", bills.findById(row.getId()).block(WAIT).getReviewState());
        }
        assertEquals(
                JSON.toJSONString(result),
                JSON.toJSONString(
                        reviews.submit(project, request, DATE)
                                .contextWrite(allowed())
                                .block(WAIT)));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"[]", "null", "[null]", "not-json"})
    void damagedSuccessJournalIsAnExplicitConflict(String damaged) {
        var row = bill("room");
        var request = request("APPROVE", row);
        reviews.submit(project, request, DATE).contextWrite(allowed()).block(WAIT);
        reviewOperations
                .createUpdate()
                .set("resultJson", damaged)
                .where("projectId", project)
                .execute()
                .block(WAIT);
        rejects(reviews.submit(project, request, DATE), "receivable_review_request_conflict");
        assertEquals(row.getVersion() + 1, bills.findById(row.getId()).block(WAIT).getVersion());
        assertEquals(
                1,
                receivableLogs
                        .createQuery()
                        .where("receivableId", row.getId())
                        .count()
                        .block(WAIT));
    }

    @Test
    void secondSpaceDeniedLeavesEntireBatchUnchanged() {
        var first = bill("room");
        var second = bill("job");
        var holder = org.mockito.Mockito.mock(org.jetlinks.pro.assets.AssetsHolder.class);
        org.mockito.Mockito.when(
                        holder.assertPermission(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyCollection(),
                                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        invocation -> {
                            java.util.Collection<?> ids = invocation.getArgument(1);
                            return ids.contains("job")
                                    ? Mono.error(FinanceScope.error("forbidden", 403))
                                    : Mono.empty();
                        });
        StepVerifier.create(
                        reviews.submit(project, request("APPROVE", first, second), DATE)
                                .contextWrite(
                                        allowed()
                                                .put(
                                                        org.jetlinks.pro.assets.AssetsHolder.class,
                                                        holder)))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 403))
                .verify(WAIT);
        for (var row : List.of(first, second)) {
            assertEquals(row.getVersion(), bills.findById(row.getId()).block(WAIT).getVersion());
            assertEquals(
                    0,
                    receivableLogs
                            .createQuery()
                            .where("receivableId", row.getId())
                            .count()
                            .block(WAIT));
        }
        assertEquals(
                0, reviewOperations.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void invalidTransitionCannotSilentlyRepeatReview() {
        var row = bill("room");
        rejects(
                reviews.submit(project, request("UNAPPROVE", row), DATE),
                "receivable_review_denied");
        var approved =
                reviews.submit(project, request("APPROVE", row), DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .get(0);
        rejects(
                reviews.submit(
                        project,
                        request("APPROVE", bills.findById(approved.getId()).block(WAIT)),
                        DATE),
                "receivable_review_denied");
        assertEquals(
                1, reviewOperations.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void finalJournalFailureRollsBackBillReviewAndPerBillAudits() throws Exception {
        var first = bill("room");
        var second = bill("job");
        var constraint = "review_fail_" + UUID.randomUUID().toString().replace("-", "");
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
                        "ALTER TABLE pm_fin_recv_review ADD CONSTRAINT "
                                + constraint
                                + " CHECK (project_id <> '"
                                + project
                                + "')");
        assertEquals(0, added.getExitCode(), added.getStderr());
        try {
            StepVerifier.create(
                            reviews.submit(project, request("APPROVE", first, second), DATE)
                                    .contextWrite(allowed()))
                    .expectError()
                    .verify(WAIT);
            for (var row : List.of(first, second)) {
                assertEquals(
                        row.getVersion(), bills.findById(row.getId()).block(WAIT).getVersion());
                assertEquals(
                        0,
                        receivableLogs
                                .createQuery()
                                .where("receivableId", row.getId())
                                .count()
                                .block(WAIT));
            }
            assertEquals(
                    0,
                    reviewOperations.createQuery().where("projectId", project).count().block(WAIT));
        } finally {
            var removed =
                    DB.execInContainer(
                            "psql",
                            "-U",
                            "postgres",
                            "-d",
                            "setting_test",
                            "-c",
                            "ALTER TABLE pm_fin_recv_review DROP CONSTRAINT " + constraint);
            assertEquals(0, removed.getExitCode(), removed.getStderr());
        }
    }

    void rejects(Mono<?> action, String code) {
        StepVerifier.create(action.contextWrite(allowed()))
                .expectErrorMatches(
                        error ->
                                TaxItemPersistenceTest.status(error, 409)
                                        && error.getMessage().contains(code))
                .verify(WAIT);
    }

    @Configuration
    @Import({HouseSettingPersistenceTest.Config.class, ReceivableReviewService.class})
    static class Config {}
}
