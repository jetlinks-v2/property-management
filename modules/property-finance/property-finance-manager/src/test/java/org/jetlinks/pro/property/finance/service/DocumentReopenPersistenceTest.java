package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.property.finance.document.DocumentIssueInput;
import org.jetlinks.pro.property.finance.entity.DocumentEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.*;

/** 重开沿真实PG事务验证整条蓝/红/新蓝链，不能只测试次数规则。 */
@SpringBootTest(
        classes = DocumentReopenPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DocumentReopenPersistenceTest extends DocumentIssuePersistenceTest {
    @Autowired DocumentReopenService reopen;

    DocumentEntity blue() {
        var bill = documentBill();
        return issue(submission(input(List.of(item("RECEIVABLE", bill.getId(), "100")))))
                .document();
    }

    void maximum(Integer max) {
        var old = ruleService.get(project).contextWrite(fixture.allowed()).block(WAIT);
        ruleService
                .save(
                        project,
                        new DocumentRuleService.Input(
                                old.version(), "NATURAL_YEAR", null, max, "设置重开次数"))
                .contextWrite(fixture.allowed())
                .block(WAIT);
    }

    DocumentReopenService.Input correction(DocumentEntity blue) {
        return new DocumentReopenService.Input(
                blue.getVersion(),
                new DocumentIssueInput.Buyer(
                        "更正购方企业",
                        "NEW123",
                        "13800138000",
                        "new@example.com",
                        "重庆",
                        "银行",
                        "6222",
                        null,
                        "ENTERPRISE"),
                "2",
                "重新开具备注",
                "购方抬头有误");
    }

    DocumentReopenService.Submission request(DocumentEntity blue) {
        var input = correction(blue);
        var preview =
                reopen.preview(project, blue.getId(), input, NOW.plusHours(2))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        return new DocumentReopenService.Submission(
                UUID.randomUUID().toString(), preview.token(), input);
    }

    DocumentStore.Detail execute(DocumentEntity blue, DocumentReopenService.Submission request) {
        return reopen.confirm(project, blue.getId(), request, NOW.plusHours(2))
                .contextWrite(fixture.allowed())
                .block(WAIT);
    }

    @Test
    void closedOperationDateBlocksNewReopenButRecoversSuccessfulReplacement() {
        maximum(2);
        var first = blue();
        var original = request(first);
        var replacement = execute(first, original);
        var next = blue();
        var request = request(next);
        periods.close(project, NOW.toLocalDate()).contextWrite(fixture.allowed()).block(WAIT);
        FinancialPeriodTestSupport.rejectsClosed(
                reopen.confirm(project, next.getId(), request, NOW.plusHours(2))
                        .contextWrite(fixture.allowed()));
        assertEquals(replacement.document().getId(), execute(first, original).document().getId());
        assertEquals(
                "SIMULATED_ISSUED", documentRows.findById(next.getId()).block(WAIT).getStatus());
        assertEquals(4, documentRows.createQuery().where("projectId", project).count().block(WAIT));
        var now = NOW.plusDays(1).plusHours(2);
        var preview =
                reopen.preview(project, next.getId(), request.input(), now)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        var nextRequest =
                new DocumentReopenService.Submission(
                        UUID.randomUUID().toString(), preview.token(), request.input());
        var reopened =
                reopen.confirm(project, next.getId(), nextRequest, now)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(now.toString(), reopened.document().getIssuedAt());
        assertEquals(6, documentRows.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void previewIsReadOnlyAndConfirmationAtomicallyBuildsRedAndReplacement() {
        maximum(1);
        var blue = blue();
        var request = request(blue);
        assertEquals(1, documentRows.createQuery().where("projectId", project).count().block(WAIT));
        var next = execute(blue, request);
        assertEquals("SIMULATED_ISSUED", next.document().getStatus());
        assertEquals(blue.getId(), next.document().getRootDocumentId());
        assertEquals(blue.getId(), next.document().getPreviousDocumentId());
        assertEquals(1, next.document().getReopenCount());
        assertEquals("100.00", next.document().getAmount().toPlainString());
        assertEquals("5.66", next.document().getTaxAmount().toPlainString());
        assertEquals("更正购方企业", next.buyer().name());
        assertEquals("2", next.document().getInvoiceType());
        var savedBlue = documentRows.findById(blue.getId()).block(WAIT);
        assertEquals("SIMULATED_REVERSED", savedBlue.getStatus());
        assertEquals(next.document().getId(), savedBlue.getReopenDocumentId());
        assertEquals("购方企业", savedBlue.getBuyerName());
        var red = documentRows.findById(savedBlue.getReverseDocumentId()).block(WAIT);
        assertEquals("-100.00", red.getAmount().toPlainString());
        assertEquals("-5.66", red.getTaxAmount().toPlainString());
        assertEquals(3, documentRows.createQuery().where("projectId", project).count().block(WAIT));
        var billId = next.lines().get(0).snapshot().sourceId();
        assertEquals(
                new BigDecimal("100.00"),
                bills.findById(billId).block(WAIT).getInvoiceOccupiedAmount());
        assertEquals(
                1,
                occupationRows
                        .createQuery()
                        .where("documentId", blue.getId())
                        .and("state", "RELEASED")
                        .count()
                        .block(WAIT));
        assertEquals(
                1,
                occupationRows
                        .createQuery()
                        .where("documentId", next.document().getId())
                        .and("state", "ACTIVE")
                        .count()
                        .block(WAIT));
        assertEquals(
                1,
                operations
                        .createQuery()
                        .where("documentId", blue.getId())
                        .and("action", "REOPEN")
                        .count()
                        .block(WAIT));
        fixture.rejects(
                reopen.preview(
                        project,
                        next.document().getId(),
                        correction(next.document()),
                        NOW.plusHours(3)),
                409);
    }

    @Test
    void zeroLimitBlocksReopenButNotPlainReverseAndAlreadyReversedCanContinueOnce() {
        var blue = blue();
        fixture.rejects(reopen.preview(project, blue.getId(), correction(blue), NOW), 409);
        reverse(blue, reversal(blue, "先红冲"));
        var reversed = documentRows.findById(blue.getId()).block(WAIT);
        maximum(1);
        var next = execute(reversed, request(reversed));
        assertEquals(3, documentRows.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(1, next.document().getReopenCount());
        fixture.rejects(
                reopen.preview(project, reversed.getId(), correction(reversed), NOW.plusHours(3)),
                409);
    }

    @Test
    void unlimitedReopenAccumulatesAcrossChainRatherThanResettingOnEachBlue() {
        maximum(null);
        var root = blue();
        var second = execute(root, request(root)).document();
        var third = execute(second, request(second)).document();
        assertEquals(root.getId(), third.getRootDocumentId());
        assertEquals(second.getId(), third.getPreviousDocumentId());
        assertEquals(2, third.getReopenCount());
        assertEquals(5, documentRows.createQuery().where("projectId", project).count().block(WAIT));
        maximum(2);
        fixture.rejects(
                reopen.preview(project, third.getId(), correction(third), NOW.plusHours(3)), 409);
    }

    @Test
    void successfulOriginalRequestRecoversAcrossDayAndSourceDeletionButChangedPayloadRejects() {
        maximum(1);
        var blue = blue();
        var request = request(blue);
        var next = execute(blue, request);
        bills.createUpdate()
                .set("deleted", true)
                .where("id", next.lines().get(0).snapshot().sourceId())
                .execute()
                .block(WAIT);
        var again =
                reopen.confirm(project, blue.getId(), request, NOW.plusYears(1))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(next.document().getId(), again.document().getId());
        var changed =
                new DocumentReopenService.Input(
                        request.input().version(),
                        request.input().buyer(),
                        "1",
                        "改内容",
                        request.input().reason());
        fixture.rejects(
                reopen.confirm(
                        project,
                        blue.getId(),
                        new DocumentReopenService.Submission(
                                request.requestId(), request.token(), changed),
                        NOW),
                409);
    }

    @Test
    void concurrentSameRequestRecoversOneReplacementAndDifferentRequestCannotFork() {
        maximum(3);
        var blue = blue();
        var request = request(blue);
        var same =
                Mono.zip(
                                reopen.confirm(project, blue.getId(), request, NOW.plusHours(2)),
                                reopen.confirm(project, blue.getId(), request, NOW.plusHours(2)))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(same.getT1().document().getId(), same.getT2().document().getId());
        assertEquals(3, documentRows.createQuery().where("projectId", project).count().block(WAIT));
        var next = same.getT1().document();
        var first = request(next);
        var second =
                new DocumentReopenService.Submission(
                        UUID.randomUUID().toString(), first.token(), first.input());
        var race =
                Mono.zip(
                                reopen.confirm(project, next.getId(), first, NOW.plusHours(2))
                                        .materialize(),
                                reopen.confirm(project, next.getId(), second, NOW.plusHours(2))
                                        .materialize())
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertNotEquals(race.getT1().isOnNext(), race.getT2().isOnNext());
        assertTrue(
                TaxItemPersistenceTest.status(
                        race.getT1().isOnError()
                                ? race.getT1().getThrowable()
                                : race.getT2().getThrowable(),
                        409));
        assertEquals(5, documentRows.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void changedRuleOrSourceInvalidatesTokenWithoutReversingOriginal() {
        maximum(2);
        var blue = blue();
        var request = request(blue);
        maximum(3);
        fixture.rejects(reopen.confirm(project, blue.getId(), request, NOW.plusHours(2)), 409);
        var fresh = request(blue);
        var detail =
                issue.detail(project, "INVOICE", blue.getId())
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        bills.createUpdate()
                .set("version", 99L)
                .where("id", detail.lines().get(0).snapshot().sourceId())
                .execute()
                .block(WAIT);
        fixture.rejects(reopen.confirm(project, blue.getId(), fresh, NOW.plusHours(2)), 409);
        assertEquals(
                "SIMULATED_ISSUED", documentRows.findById(blue.getId()).block(WAIT).getStatus());
        assertEquals(1, documentRows.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void lastReopenAuditFailureRollsBackRedReplacementSourceAndChainPointer() {
        maximum(1);
        var blue = blue();
        var request = request(blue);
        sqlReopen(
                "alter table pm_fin_doc_operation add constraint reject_reopen_end check"
                        + " (project_id <> '"
                        + project
                        + "' or action <> 'REOPEN')");
        try {
            StepVerifier.create(
                            reopen.confirm(project, blue.getId(), request, NOW.plusHours(2))
                                    .contextWrite(fixture.allowed()))
                    .expectErrorMatches(
                            e ->
                                    e.toString().contains("reject_reopen_end")
                                            || containsConstraint(e))
                    .verify(WAIT);
            var unchanged = documentRows.findById(blue.getId()).block(WAIT);
            assertEquals("SIMULATED_ISSUED", unchanged.getStatus());
            assertNull(unchanged.getReverseDocumentId());
            assertNull(unchanged.getReopenDocumentId());
            assertEquals(blue.getVersion(), unchanged.getVersion());
            assertEquals(
                    1, documentRows.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(
                    1,
                    occupationRows
                            .createQuery()
                            .where("documentId", blue.getId())
                            .and("state", "ACTIVE")
                            .count()
                            .block(WAIT));
        } finally {
            sqlReopen("alter table pm_fin_doc_operation drop constraint reject_reopen_end");
        }
        assertEquals(1, execute(blue, request).document().getReopenCount());
    }

    @Test
    void revokedSpaceRejectsPreviewConfirmAndSuccessfulRecovery() {
        maximum(1);
        var blue = blue();
        var request = request(blue);
        execute(blue, request);
        var restricted = fixture.allowed();
        var holder = restricted.<AssetsHolder>get(AssetsHolder.class);
        when(holder.assertPermission(anyString(), anyCollection(), any()))
                .thenReturn(Mono.error(FinanceScope.error("permission_denied", 403)));
        StepVerifier.create(
                        reopen.confirm(project, blue.getId(), request, NOW)
                                .contextWrite(restricted))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        fixture.rejects(reopen.preview("forbidden", blue.getId(), correction(blue), NOW), 403);
        StepVerifier.create(reopen.preview(project, blue.getId(), correction(blue), NOW))
                .expectError()
                .verify(WAIT);
    }

    @Test
    void differentialVoucherAndTaxSurviveReplacementAndInitialIssueExpiryDoesNotBlockReopen() {
        maximum(2);
        var bill = documentBill();
        var voucher =
                new DocumentIssueInput.DifferenceVoucher(
                        "09",
                        "凭证001",
                        null,
                        null,
                        null,
                        "2024-02-15",
                        "10",
                        "差额扣除",
                        "attachment",
                        "原始凭证.pdf");
        var input =
                input(
                        List.of(
                                new DocumentIssueInput.Item(
                                        "RECEIVABLE",
                                        bill.getId(),
                                        "50",
                                        "DIFFERENTIAL",
                                        "10",
                                        voucher)));
        var blue = issue(submission(input)).document();
        var first = execute(blue, request(blue));
        assertEquals("2.26", first.document().getTaxAmount().toPlainString());
        assertEquals("47.74", first.document().getNetAmount().toPlainString());
        assertEquals("attachment", first.lines().get(0).snapshot().voucher().attachmentFileId());
        assertEquals("10.00", first.lines().get(0).snapshot().differenceAmount());
        var nextInput = correction(first.document());
        var late = NOW.plusYears(1);
        var preview =
                reopen.preview(project, first.document().getId(), nextInput, late)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        var next =
                reopen.confirm(
                                project,
                                first.document().getId(),
                                new DocumentReopenService.Submission(
                                        UUID.randomUUID().toString(), preview.token(), nextInput),
                                late)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(2, next.document().getReopenCount());
        assertEquals("2.26", next.document().getTaxAmount().toPlainString());
    }

    @Test
    void sourceOccupationCorruptionReceiptRedAndBadBuyerCannotBypassValidation() {
        maximum(2);
        var blue = blue();
        var request = request(blue);
        occupationRows
                .createUpdate()
                .set("amount", new BigDecimal("99.00"))
                .where("documentId", blue.getId())
                .execute()
                .block(WAIT);
        fixture.rejects(reopen.preview(project, blue.getId(), correction(blue), NOW), 409);
        fixture.rejects(reopen.confirm(project, blue.getId(), request, NOW.plusHours(2)), 409);
        occupationRows
                .createUpdate()
                .set("amount", new BigDecimal("100.00"))
                .where("documentId", blue.getId())
                .execute()
                .block(WAIT);
        var invalidBuyer =
                new DocumentReopenService.Input(
                        blue.getVersion(),
                        new DocumentIssueInput.Buyer(
                                "企业", null, null, null, null, null, null, null, "ENTERPRISE"),
                        "2",
                        null,
                        "更正专票");
        fixture.rejects(reopen.preview(project, blue.getId(), invalidBuyer, NOW), 400);
        var red = reverse(blue, reversal(blue, "普通红冲"));
        fixture.rejects(
                reopen.preview(
                        project,
                        red.document().getId(),
                        correction(red.document()),
                        NOW.plusHours(2)),
                409);
        var bill = documentBill();
        var receiptInput =
                new DocumentIssueInput(
                        "RECEIPT",
                        "company",
                        input(List.of()).buyer(),
                        null,
                        null,
                        List.of(item("RECEIVABLE", bill.getId(), "10")));
        var receipt = issue(submission(receiptInput)).document();
        fixture.rejects(reopen.preview(project, receipt.getId(), correction(receipt), NOW), 404);
        assertEquals(3, documentRows.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void replacementInsertFailureRollsBackAlreadyCreatedRedAndRelease() {
        maximum(1);
        var blue = blue();
        var request = request(blue);
        sqlReopen(
                "alter table pm_fin_document add constraint reject_replacement check (project_id <>"
                        + " '"
                        + project
                        + "' or previous_document_id is null)");
        try {
            StepVerifier.create(
                            reopen.confirm(project, blue.getId(), request, NOW.plusHours(2))
                                    .contextWrite(fixture.allowed()))
                    .expectErrorMatches(e -> contains(e, "reject_replacement"))
                    .verify(WAIT);
            var original = documentRows.findById(blue.getId()).block(WAIT);
            assertEquals("SIMULATED_ISSUED", original.getStatus());
            assertEquals(blue.getVersion(), original.getVersion());
            assertEquals(
                    1, documentRows.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(
                    1,
                    occupationRows
                            .createQuery()
                            .where("documentId", blue.getId())
                            .and("state", "ACTIVE")
                            .count()
                            .block(WAIT));
        } finally {
            sqlReopen("alter table pm_fin_document drop constraint reject_replacement");
        }
    }

    @Test
    void reopenHttpHasIndependentRoutesStringMoneyAndReadOnlyChainMetadata() {
        maximum(1);
        var blue = blue();
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web.InvoiceDocumentController(
                                        preparation,
                                        issue,
                                        reverse,
                                        null,
                                        null,
                                        reopen,
                                        new BillingJobProperties(),
                                        null))
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange).contextWrite(fixture.allowed()))
                        .build();
        var path =
                "/property/finance/projects/" + project + "/invoices/" + blue.getId() + "/reopen";
        var preview =
                client.post()
                        .uri(path + "/_preview")
                        .bodyValue(correction(blue))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(DocumentPreparationService.Preview.class)
                        .returnResult()
                        .getResponseBody();
        assertEquals("100.00", preview.amount());
        client.post()
                .uri(path + "/_confirm")
                .bodyValue(
                        new DocumentReopenService.Submission(
                                UUID.randomUUID().toString(), preview.token(), correction(blue)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.document.amount")
                .isEqualTo("100.00")
                .jsonPath("$.document.reopenCount")
                .isEqualTo(1)
                .jsonPath("$.document.previousDocumentId")
                .isEqualTo(blue.getId())
                .jsonPath("$.document.rootDocumentId")
                .isEqualTo(blue.getId())
                .jsonPath("$.document.requestFingerprint")
                .doesNotExist()
                .jsonPath("$.buyer.name")
                .isEqualTo("更正购方企业");
        client.put()
                .uri(path + "/_confirm")
                .bodyValue(correction(blue))
                .exchange()
                .expectStatus()
                .isEqualTo(405);
    }

    private static boolean containsConstraint(Throwable e) {
        return contains(e, "reject_reopen_end");
    }

    private static boolean contains(Throwable e, String constraint) {
        for (var cause = e; cause != null; cause = cause.getCause())
            if (cause.toString().contains(constraint)) return true;
        return false;
    }

    private void sqlReopen(String statement) {
        try {
            var result =
                    HouseSettingPersistenceTest.DB.execInContainer(
                            "psql", "-U", "postgres", "-d", "setting_test", "-c", statement);
            assertEquals(0, result.getExitCode(), result.getStderr());
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Configuration
    @Import({DocumentIssuePersistenceTest.Config.class, DocumentReopenService.class})
    static class Config {}
}
