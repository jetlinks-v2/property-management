package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.events.EventContext;
import org.hswebframework.ezorm.rdb.events.EventType;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.mapping.events.MappingContextKeys;
import org.hswebframework.ezorm.rdb.mapping.events.MappingEventTypes;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.tenant.Tenant;
import org.jetlinks.pro.tenant.crud.TenantColumnEventListener;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** 真实PG验证整单交账不重记资金，状态与来源保护同事务。 */
@SpringBootTest(
        classes = HandoverPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HandoverPersistenceTest extends CashierPersistenceTest {
    static final LocalDateTime NOW = DATE.atTime(16, 0);
    @Autowired HandoverService handover;
    @Autowired HandoverRecords handoverRecords;
    @Autowired ReactiveRepository<HandoverEntity, String> handovers;
    @Autowired ReactiveRepository<HandoverSourceEntity, String> claims;
    @Autowired ReactiveRepository<HandoverOperationEntity, String> handoverLogs;
    @Autowired ReactiveRepository<DocumentEntity, String> documents;
    @Autowired ReactiveRepository<DocumentLineEntity, String> documentLines;

    @Autowired HistoricalReceiptFixture historicalReceipts;
    @Autowired ReceivedReportService receivedReports;

    @Test
    void receivedStatusTracksReviewReleaseAndResubmissionWithoutHidingPayments() {
        var payment = paid("handover-status", "10");
        // 本测试验证交账投影；历史流水缺少操作人ID时无需访问外部用户目录。
        payments.createUpdate()
                .setNull("modifierId")
                .where("id", payment.getId())
                .execute()
                .block(WAIT);
        assertReceivedState("UNHANDED");
        var pending = confirm(request(handoverInput(payment))).batch();
        assertReceivedState("PENDING");
        var approved = act(pending, "APPROVE").batch();
        assertReceivedState("APPROVED");
        var reversed = act(approved, "UNAPPROVE").batch();
        assertReceivedState("PENDING");
        act(reversed, "REJECT");
        assertReceivedState("UNHANDED");
        var next = confirm(request(handoverInput(payment))).batch();
        assertReceivedState("PENDING");
        act(next, "CANCEL");
        assertReceivedState("UNHANDED");
        assertEquals(2, handovers.createQuery().where("projectId", project).count().block(WAIT));
    }

    void assertReceivedState(String expected) {
        var input = new org.jetlinks.pro.property.finance.report.ReceivedReportInput();
        input.setProjectIds(List.of(project));
        var report = receivedReports.query(input).contextWrite(allowed()).block(WAIT);
        assertEquals(1, report.page().getTotal());
        assertEquals(expected, report.page().getData().get(0).handoverState());
        assertEquals("10.00", report.summary().netAmount());
        for (String state : List.of("UNHANDED", "PENDING", "APPROVED")) {
            input.setHandoverState(state);
            int count = expected.equals(state) ? 1 : 0;
            assertEquals(
                    count,
                    receivedReports
                            .query(input)
                            .contextWrite(allowed())
                            .block(WAIT)
                            .page()
                            .getTotal());
            var original = new QueryParamEntity();
            original.toQuery().nest().and("handoverState", state).end();
            var page = records.query(project, original).contextWrite(allowed()).block(WAIT);
            assertEquals(count, page.getTotal());
            if (count > 0) assertEquals(expected, page.getData().get(0).handoverState());
        }
    }

    PaymentEntity paid(String name, String amount) {
        var row = bill(name);
        return historicalReceipts.seed(row, amount).contextWrite(allowed()).block(WAIT).payment();
    }

    HandoverService.Input handoverInput(PaymentEntity... rows) {
        return new HandoverService.Input(
                Arrays.stream(rows)
                        .map(p -> new HandoverSources.Item("PAYMENT", p.getId(), p.getVersion()))
                        .toList(),
                DATE.toString(),
                "柜台核对",
                null,
                null);
    }

    HandoverService.Submission request(HandoverService.Input input) {
        var quote = handover.preview(project, input, NOW).contextWrite(allowed()).block(WAIT);
        return new HandoverService.Submission(UUID.randomUUID().toString(), quote.token(), input);
    }

    HandoverRecords.Detail confirm(HandoverService.Submission input) {
        return handover.confirm(project, input, NOW).contextWrite(allowed()).block(WAIT);
    }

    HandoverService.Action action(HandoverEntity row) {
        return new HandoverService.Action(UUID.randomUUID().toString(), row.getVersion(), "核对完成");
    }

    HandoverRecords.Detail act(HandoverEntity row, String action) {
        return handover.action(project, row.getId(), action, action(row), NOW.plusHours(1))
                .contextWrite(allowed())
                .block(WAIT);
    }

    @Test
    void identityTenantDoesNotMoveHandoverOrSourceOwnershipDuringReview() {
        var saved = confirm(request(handoverInput(paid("tenant-review", "5")))).batch();
        var tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn("identity-tenant");
        var context = allowed().put(Tenant.class, tenant);
        var approval = action(saved);
        var approved =
                handover.action(project, saved.getId(), "APPROVE", approval, NOW)
                        .contextWrite(context)
                        .block(WAIT)
                        .batch();
        assertEquals("APPROVED", approved.getStatus());
        assertEquals("", handovers.findById(saved.getId()).block(WAIT).getTenantId());
        assertEquals(
                approved.getVersion(),
                handover.action(project, saved.getId(), "APPROVE", approval, NOW)
                        .contextWrite(context)
                        .block(WAIT)
                        .batch()
                        .getVersion());
        var pending =
                handover.action(project, saved.getId(), "UNAPPROVE", action(approved), NOW)
                        .contextWrite(context)
                        .block(WAIT)
                        .batch();
        var cancelled =
                handover.action(project, saved.getId(), "CANCEL", action(pending), NOW)
                        .contextWrite(context)
                        .block(WAIT)
                        .batch();
        assertEquals("CANCELLED", cancelled.getStatus());
        var binding = claims.createQuery().where("projectId", project).fetchOne().block(WAIT);
        assertEquals("", binding.getTenantId());
        assertNull(binding.getBatchId());
        var nextRequest =
                request(handoverInput(payments.findById(binding.getSourceId()).block(WAIT)));
        var next = handover.confirm(project, nextRequest, NOW).contextWrite(context).block(WAIT);
        assertNotEquals(saved.getId(), next.batch().getId());
        assertEquals("", claims.findById(binding.getId()).block(WAIT).getTenantId());
        rejects(
                handover.action(project, next.batch().getId(), "APPROVE", action(next.batch()), NOW)
                        .contextWrite(TenantHolder.makeCurrent("other-tenant")),
                404);
        assertEquals("PENDING", handovers.findById(next.batch().getId()).block(WAIT).getStatus());
    }

    @Autowired FinancialPeriodTestSupport periods;

    @Test
    void closingRejectsNewHandoverByBusinessDateButRestoresOriginalCreation() {
        var original = request(handoverInput(paid("period-original", "10")));
        var pending = request(handoverInput(paid("period-next", "20")));
        var saved = confirm(original);
        periods.close(project, DATE).contextWrite(allowed()).block(WAIT);
        FinancialPeriodTestSupport.rejectsClosed(
                handover.confirm(project, pending, NOW.plusDays(1)).contextWrite(allowed()));
        assertEquals(saved.batch().getId(), confirm(original).batch().getId());
        assertEquals(1, handovers.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(1, claims.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(1, handoverLogs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void closedPendingBatchCannotBeChangedEvenOnTheNextOpenDay() {
        var saved = confirm(request(handoverInput(paid("period-pending", "10"))));
        periods.close(project, DATE).contextWrite(allowed()).block(WAIT);
        for (var name : List.of("APPROVE", "REJECT", "CANCEL"))
            FinancialPeriodTestSupport.rejectsClosed(
                    handover.action(
                                    project,
                                    saved.batch().getId(),
                                    name,
                                    action(saved.batch()),
                                    NOW.plusDays(1))
                            .contextWrite(allowed()));
        assertEquals("PENDING", handovers.findById(saved.batch().getId()).block(WAIT).getStatus());
        assertEquals(1, claims.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(1, handoverLogs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void closingBlocksUnapprovalButRestoresAnAlreadySuccessfulApproval() {
        var saved = confirm(request(handoverInput(paid("period-approved", "10"))));
        var request = action(saved.batch());
        var approved =
                handover.action(project, saved.batch().getId(), "APPROVE", request, NOW)
                        .contextWrite(allowed())
                        .block(WAIT);
        periods.close(project, DATE).contextWrite(allowed()).block(WAIT);
        FinancialPeriodTestSupport.rejectsClosed(
                handover.action(
                                project,
                                saved.batch().getId(),
                                "UNAPPROVE",
                                action(approved.batch()),
                                NOW.plusDays(1))
                        .contextWrite(allowed()));
        var recovered =
                handover.action(project, saved.batch().getId(), "APPROVE", request, NOW.plusDays(1))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(approved.batch().getVersion(), recovered.batch().getVersion());
        assertEquals("APPROVED", recovered.batch().getStatus());
        assertEquals(2, handoverLogs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void previewIsReadOnlyAndSuccessfulRequestRecoversWithoutWritingAnotherPayment() {
        var payment = paid("one", "12.34");
        var input = handoverInput(payment);
        var req = request(input);
        assertEquals(0, handovers.createQuery().where("projectId", project).count().block(WAIT));
        var result = confirm(req);
        assertEquals("12.34", result.batch().getAmount().toPlainString());
        assertEquals("12.34", result.batch().getCashAmount().toPlainString());
        assertEquals(1, result.lines().size());
        assertEquals("PENDING", result.batch().getStatus());
        var again =
                handover.confirm(project, req, NOW.plusDays(1)).contextWrite(allowed()).block(WAIT);
        assertEquals(result.batch().getId(), again.batch().getId());
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(1, handoverLogs.createQuery().where("projectId", project).count().block(WAIT));
        rejects(
                handover.confirm(
                        project,
                        new HandoverService.Submission(
                                req.requestId(),
                                req.token(),
                                new HandoverService.Input(
                                        input.items(), input.closingDate(), "改备注", null, null)),
                        NOW),
                409);
    }

    @Test
    void wholePaymentCannotBeHandedTwiceAndCancelAllowsNewBatchWhileKeepingHistory() {
        var payment = paid("again", "10");
        var i = handoverInput(payment);
        var a = request(i);
        var b = request(i);
        var first = confirm(a);
        rejects(handover.confirm(project, b, NOW), 409);
        var cancelled = act(first.batch(), "CANCEL");
        assertEquals("CANCELLED", cancelled.batch().getStatus());
        var next = confirm(request(i));
        assertNotEquals(first.batch().getId(), next.batch().getId());
        assertEquals(2, handovers.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(1, claims.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                "10.00",
                handoverRecords
                        .detail(project, first.batch().getId())
                        .contextWrite(allowed())
                        .block(WAIT)
                        .batch()
                        .getAmount()
                        .toPlainString());
    }

    @Test
    void buttonReviewStateMachineAndStaleVersionsAreEnforced() {
        var first = confirm(request(handoverInput(paid("review", "5"))));
        var approved = act(first.batch(), "APPROVE");
        assertEquals("APPROVED", approved.batch().getStatus());
        rejects(
                handover.action(
                        project, approved.batch().getId(), "CANCEL", action(approved.batch()), NOW),
                409);
        rejects(
                handover.action(
                        project, approved.batch().getId(), "UNAPPROVE", action(first.batch()), NOW),
                409);
        var pending = act(approved.batch(), "UNAPPROVE");
        assertEquals("PENDING", pending.batch().getStatus());
        var rejected = act(pending.batch(), "REJECT");
        assertEquals("REJECTED", rejected.batch().getStatus());
        rejects(
                handover.action(
                        project,
                        rejected.batch().getId(),
                        "APPROVE",
                        action(rejected.batch()),
                        NOW),
                409);
    }

    @Test
    void concurrentSameRequestRecoversAndDifferentRequestsHaveExactlyOneWinner() {
        var i = handoverInput(paid("same", "6"));
        var req = request(i);
        var same =
                Mono.zip(handover.confirm(project, req, NOW), handover.confirm(project, req, NOW))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(same.getT1().batch().getId(), same.getT2().batch().getId());
        var second = handoverInput(paid("race", "7"));
        var a = request(second);
        var b = request(second);
        var race =
                Mono.zip(
                                handover.confirm(project, a, NOW).materialize(),
                                handover.confirm(project, b, NOW).materialize())
                        .contextWrite(allowed())
                        .block(WAIT);
        assertNotEquals(race.getT1().isOnNext(), race.getT2().isOnNext());
    }

    @Test
    void wholeBatchRejectsInvalidMemberWithoutClaimingAnySource() {
        var a = paid("valid", "4");
        var b = paid("stale", "3");
        var i = handoverInput(a, b);
        var req = request(i);
        payments.createUpdate().set("version", 8L).where("id", b.getId()).execute().block(WAIT);
        rejects(handover.confirm(project, req, NOW), 409);
        assertEquals(0, claims.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, handovers.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void pendingCandidatesHaveExactTotalsAndReappearAfterRejection() {
        var a = paid("a", "1");
        paid("b", "2");
        var row = confirm(request(handoverInput(a)));
        var q = new QueryParamEntity();
        q.setPageSize(1);
        var page =
                handoverRecords
                        .candidates(project, "PAYMENT", q)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(1, page.getTotal());
        assertEquals(1, page.getData().size());
        act(row.batch(), "REJECT");
        assertEquals(
                2,
                handoverRecords
                        .candidates(project, "PAYMENT", new QueryParamEntity())
                        .contextWrite(allowed())
                        .block(WAIT)
                        .getTotal());
        assertEquals(
                1,
                handoverRecords
                        .query(project, new QueryParamEntity())
                        .contextWrite(allowed())
                        .block(WAIT)
                        .getTotal());
    }

    @Test
    void fullPaymentExpandsAllMethodsAndOnlyManualMethodsCountAsCash() {
        var payment = paid("mixed", "10");
        var line = lines.createQuery().where("paymentId", payment.getId()).fetchOne().block(WAIT);
        lines.createUpdate()
                .set("amount", new BigDecimal("4.00"))
                .where("id", line.getId())
                .execute()
                .block(WAIT);
        line.setId(UUID.randomUUID().toString());
        line.setAmount(new BigDecimal("6.00"));
        line.setMethod("ADVANCE");
        line.setMethodText("预收冲抵");
        lines.insert(line).block(WAIT);
        var quote =
                handover.preview(project, handoverInput(payment), NOW)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("10.00", quote.amount());
        assertEquals("4.00", quote.cashAmount());
        assertEquals(2, quote.lines().size());
        var saved = confirm(request(handoverInput(payment)));
        assertEquals(2, saved.lines().size());
        assertEquals("4.00", saved.batch().getCashAmount().toPlainString());
    }

    @Test
    void missingSpacePermissionHidesWholePaymentBeforePaginationAndBlocksReplayAndWrites() {
        var payment = paid("restricted", "10");
        var input = handoverInput(payment);
        var req = request(input);
        var row = lines.createQuery().where("paymentId", payment.getId()).fetchOne().block(WAIT);
        lines.createUpdate()
                .set("amount", new BigDecimal("5.00"))
                .where("id", row.getId())
                .execute()
                .block(WAIT);
        row.setId(UUID.randomUUID().toString());
        row.setSpaceId("hidden");
        row.setAmount(new BigDecimal("5.00"));
        lines.insert(row).block(WAIT);
        req = request(input);
        var complete = confirm(req);
        var restricted = allowed();
        var holder = restricted.<AssetsHolder>get(AssetsHolder.class);
        when(holder.injectQuery(any(QueryParamEntity.class), eq("space"), eq("spaceId"), any()))
                .thenAnswer(
                        call -> {
                            var q = call.getArgument(0, QueryParamEntity.class);
                            q.toQuery().and("spaceId", "room");
                            return Mono.just(q);
                        });
        when(holder.assertPermission(anyString(), anyCollection(), any()))
                .thenAnswer(
                        call ->
                                call.getArgument(1, Collection.class).contains("hidden")
                                        ? Mono.error(FinanceScope.error("permission_denied", 403))
                                        : Mono.empty());
        assertEquals(
                0,
                handoverRecords
                        .query(project, new QueryParamEntity())
                        .contextWrite(restricted)
                        .block(WAIT)
                        .getTotal());
        reactor.test.StepVerifier.create(
                        handover.confirm(project, req, NOW).contextWrite(restricted))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        reactor.test.StepVerifier.create(
                        handoverRecords
                                .detail(project, complete.batch().getId())
                                .contextWrite(restricted))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        reactor.test.StepVerifier.create(
                        handover.action(
                                        project,
                                        complete.batch().getId(),
                                        "APPROVE",
                                        action(complete.batch()),
                                        NOW)
                                .contextWrite(restricted))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        assertEquals(
                "PENDING", handovers.findById(complete.batch().getId()).block(WAIT).getStatus());
    }

    @Test
    void auditFailureRollsBackBatchBindingsAndLines() throws Exception {
        var payment = paid("rollback", "8");
        var req = request(handoverInput(payment));
        var name = "handover_audit_" + UUID.randomUUID().toString().replace("-", "");
        var db = HouseSettingPersistenceTest.DB;
        var installed =
                db.execInContainer(
                        "psql",
                        "-U",
                        "postgres",
                        "-d",
                        "setting_test",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-c",
                        "ALTER TABLE pm_fin_handover_log ADD CONSTRAINT "
                                + name
                                + " CHECK (project_id <> '"
                                + project
                                + "') NOT VALID");
        assertEquals(0, installed.getExitCode(), installed.getStderr());
        try {
            reactor.test.StepVerifier.create(
                            handover.confirm(project, req, NOW).contextWrite(allowed()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(
                    0, handovers.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(0, claims.createQuery().where("projectId", project).count().block(WAIT));
        } finally {
            db.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "setting_test",
                    "-c",
                    "ALTER TABLE pm_fin_handover_log DROP CONSTRAINT " + name);
        }
        assertEquals("PENDING", confirm(req).batch().getStatus());
    }

    @Test
    void monthClosedBlocksReviewReversalAndCancellationButKeepsReadOnlyDetails() {
        var result = confirm(request(handoverInput(paid("closed", "7"))));
        handovers
                .createUpdate()
                .set("monthClosed", true)
                .where("id", result.batch().getId())
                .execute()
                .block(WAIT);
        rejects(
                handover.action(
                        project, result.batch().getId(), "CANCEL", action(result.batch()), NOW),
                409);
        rejects(
                handover.action(
                        project, result.batch().getId(), "APPROVE", action(result.batch()), NOW),
                409);
        assertTrue(
                handoverRecords
                        .detail(project, result.batch().getId())
                        .contextWrite(allowed())
                        .block(WAIT)
                        .batch()
                        .getMonthClosed());
    }

    @Test
    void httpSerializesDecimalStringsAndHidesInternalIdentityAndHasNoGenericDelete()
            throws Exception {
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web.HandoverController(
                                        handover,
                                        handoverRecords,
                                        new HandoverBatchService(handoverRecords, handover),
                                        new HandoverStatementService(handoverRecords),
                                        new BillingJobProperties()))
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter((e, c) -> c.filter(e).contextWrite(allowed()))
                        .build();
        var input = handoverInput(paid("http", "9.99"));
        var path = "/property/finance/projects/" + project + "/handovers";
        var quote =
                client.post()
                        .uri(path + "/_preview")
                        .bodyValue(input)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(HandoverService.Preview.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(quote);
        var req =
                new HandoverService.Submission(UUID.randomUUID().toString(), quote.token(), input);
        var body =
                client.post()
                        .uri(path + "/_confirm")
                        .bodyValue(req)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("$.batch.amount")
                        .isEqualTo("9.99")
                        .jsonPath("$.batch.tenantId")
                        .doesNotExist()
                        .jsonPath("$.batch.requestFingerprint")
                        .doesNotExist()
                        .returnResult()
                        .getResponseBody();
        var row = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("batch");
        client.post()
                .uri(path + "/" + row.path("id").asText() + "/_approve")
                .bodyValue(
                        new HandoverService.Action(
                                UUID.randomUUID().toString(), row.path("version").asLong(), "已核对"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.batch.status")
                .isEqualTo("APPROVED");
        client.delete()
                .uri(path + "/" + row.path("id").asText())
                .exchange()
                .expectStatus()
                .is4xxClientError();
        client.post()
                .uri(path + "/sources/UNKNOWN/_query")
                .bodyValue(new QueryParamEntity())
                .exchange()
                .expectStatus()
                .isBadRequest();
        var controller = org.jetlinks.pro.property.finance.web.HandoverController.class;
        assertEquals(
                "approve",
                controller
                        .getMethod(
                                "approve", String.class, String.class, HandoverService.Action.class)
                        .getAnnotation(
                                org.hswebframework.web.authorization.annotation.ResourceAction
                                        .class)
                        .id());
        assertEquals(
                "cancel",
                controller
                        .getMethod(
                                "cancel", String.class, String.class, HandoverService.Action.class)
                        .getAnnotation(
                                org.hswebframework.web.authorization.annotation.ResourceAction
                                        .class)
                        .id());
    }

    @Test
    void unifiedRowsLocateBatchAndAttachmentsAreFrozenAndBoundToPreview() {
        var payment = paid("unified", "19.99");
        var query = new HandoverRecords.DetailQuery(0, 12, "NO", null, null, null, null, null);
        var pending = handoverRecords.details(project, query).contextWrite(allowed()).block(WAIT);
        assertEquals(1, pending.getTotal());
        assertEquals(payment.getId(), pending.getData().get(0).sourceId());
        var input =
                new HandoverService.Input(
                        handoverInput(payment).items(),
                        DATE.toString(),
                        "附件核对",
                        null,
                        null,
                        List.of(
                                new HandoverService.Attachment("f1", "凭证1.pdf", 100L),
                                new HandoverService.Attachment("f2", "凭证2.pdf", 200L)));
        var saved = confirm(request(input));
        assertTrue(saved.batch().getHandoverNo().startsWith("JZ-"));
        assertEquals(2, saved.batch().getAttachments().size());
        assertEquals(
                0,
                handoverRecords
                        .details(project, query)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .getTotal());
        var handed =
                handoverRecords
                        .details(
                                project,
                                new HandoverRecords.DetailQuery(
                                        0, 12, "YES", null, null, null, null, null))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(saved.batch().getId(), handed.getData().get(0).batchId());
        var invalid =
                new HandoverService.Input(
                        handoverInput(payment).items(),
                        DATE.toString(),
                        null,
                        null,
                        null,
                        List.of(
                                new HandoverService.Attachment(
                                        "f", "large.pdf", 21L * 1024 * 1024)));
        assertThrows(
                Exception.class,
                () -> handover.preview(project, invalid, NOW).contextWrite(allowed()).block(WAIT));
        assertEquals(1, handovers.createQuery().where("projectId", project).count().block(WAIT));
        var batchService = new HandoverBatchService(handoverRecords, handover);
        var batchInput =
                new HandoverBatchService.Input(
                        List.of(
                                new HandoverBatchService.Item(
                                        saved.batch().getId(),
                                        saved.batch().getVersion(),
                                        UUID.randomUUID().toString())),
                        "批量核对");
        assertTrue(
                batchService
                        .preview(project, "APPROVE", batchInput)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .get(0)
                        .success());
        assertTrue(
                batchService
                        .execute(project, "APPROVE", batchInput, NOW)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .get(0)
                        .success());
        assertEquals("APPROVED", handovers.findById(saved.batch().getId()).block(WAIT).getStatus());
        var statement =
                new HandoverStatementService(handoverRecords)
                        .statement(project, saved.batch().getId())
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("19.99", statement.rows().get(0).cashAmount());
    }

    @Test
    void batchPreviewRequiresSavePermissionForEverySpaceEvenWhenReadIsAllowed() {
        var batch = confirm(request(handoverInput(paid("save-scope", "7.00")))).batch();
        var restricted = allowed();
        var holder = restricted.<AssetsHolder>get(AssetsHolder.class);
        when(holder.assertPermission(
                        eq("space"),
                        anyCollection(),
                        eq(org.jetlinks.pro.assets.crud.CrudAssetPermission.save)))
                .thenReturn(Mono.error(FinanceScope.error("permission_denied", 403)));
        assertNotNull(
                handoverRecords
                        .detail(project, batch.getId())
                        .contextWrite(restricted)
                        .block(WAIT));
        var batchService = new HandoverBatchService(handoverRecords, handover);
        var input =
                new HandoverBatchService.Input(
                        List.of(
                                new HandoverBatchService.Item(
                                        batch.getId(),
                                        batch.getVersion(),
                                        UUID.randomUUID().toString())),
                        "核对");
        assertFalse(
                batchService
                        .preview(project, "APPROVE", input)
                        .contextWrite(restricted)
                        .block(WAIT)
                        .get(0)
                        .success());
        assertFalse(
                batchService
                        .execute(project, "APPROVE", input, NOW)
                        .contextWrite(restricted)
                        .block(WAIT)
                        .get(0)
                        .success());
        assertEquals("PENDING", handovers.findById(batch.getId()).block(WAIT).getStatus());
        assertEquals(1, handoverLogs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Configuration
    @Import({
        CashierPersistenceTest.Config.class,
        ReceivedReportService.class,
        HandoverSources.class,
        HandoverRecords.class,
        HandoverService.class,
        DocumentQueryScope.class
    })
    static class Config {
        @Bean
        TenantColumnEventListener tenantColumnEventListener() {
            // 只替代租户身份解析，真实 ORM 租户列填充与数据库更新沿用平台实现。
            return new TenantColumnEventListener() {
                @Override
                public void onEvent(EventType type, EventContext event) {
                    if (type == MappingEventTypes.insert_before
                            || type == MappingEventTypes.save_before
                            || type == MappingEventTypes.update_before) {
                        event.get(MappingContextKeys.reactiveResultHolder)
                                .ifPresent(
                                        holder ->
                                                holder.before(
                                                        Mono.deferContextual(
                                                                context -> {
                                                                    doApply(
                                                                            context,
                                                                            type,
                                                                            event,
                                                                            context.getOrDefault(
                                                                                    Tenant.class,
                                                                                    null));
                                                                    return Mono.empty();
                                                                })));
                    }
                }
            };
        }
    }
}
