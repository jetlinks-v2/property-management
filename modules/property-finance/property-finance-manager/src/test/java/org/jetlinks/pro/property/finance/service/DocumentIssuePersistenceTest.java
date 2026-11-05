package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.property.finance.document.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.*;

/** 开具、精确占用、红冲与末端审计使用真实PG事务；网络重试恢复已成功事实。 */
@SpringBootTest(
        classes = DocumentIssuePersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DocumentIssuePersistenceTest extends DocumentPreparationPersistenceTest {
    @Autowired DocumentIssueService issue;
    @Autowired DocumentReverseService reverse;
    @Autowired ReactiveRepository<DocumentEntity, String> documentRows;
    @Autowired ReactiveRepository<DocumentLineEntity, String> documentLines;
    @Autowired ReactiveRepository<DocumentOperationEntity, String> operations;
    @Autowired ReactiveRepository<DocumentOccupationEntity, String> occupationRows;
    @Autowired ReactiveRepository<FinanceCompanyEntity, String> companyRows;
    @Autowired FinancialPeriodTestSupport periods;
    @Autowired ReactiveRepository<FinancialPeriodControlEntity, String> periodRows;

    @BeforeEach
    void createCompany() {
        if (companyRows.findById("company").block(WAIT) != null) return;
        var company = new FinanceCompanyEntity();
        company.setId("company");
        company.setCompanyName("原开票公司");
        company.setTaxNumber("915001000000000001");
        company.setAddress("重庆");
        company.setPhone("123456");
        company.setDepositBank("测试银行");
        company.setBankAccount("62220000");
        company.setDrawer("开票员");
        company.setReviewer("复核员");
        company.setEstateNo("001");
        StandardPersistenceTest.initialize(company);
        companyRows.insert(company).block(WAIT);
    }

    DocumentIssueService.Submission submission(DocumentIssueInput i) {
        return new DocumentIssueService.Submission(
                UUID.randomUUID().toString(), preview(i).token(), i);
    }

    DocumentStore.Detail issue(DocumentIssueService.Submission s) {
        return issue.confirm(project, s, NOW).contextWrite(fixture.allowed()).block(WAIT);
    }

    DocumentReverseService.Input reversal(DocumentEntity d, String reason) {
        return new DocumentReverseService.Input(
                UUID.randomUUID().toString(), d.getVersion(), reason);
    }

    DocumentStore.Detail reverse(DocumentEntity d, DocumentReverseService.Input i) {
        return reverse.reverse(project, d.getDocumentType(), d.getId(), i, NOW.plusHours(1))
                .contextWrite(fixture.allowed())
                .block(WAIT);
    }

    @Test
    void closedOperationDateBlocksNewIssueButRestoresSuccessfulOriginal() {
        var firstBill = documentBill();
        var first = submission(input(List.of(item("RECEIVABLE", firstBill.getId(), "50"))));
        var saved = issue(first);
        var nextBill = documentBill();
        var next = submission(input(List.of(item("RECEIVABLE", nextBill.getId(), "50"))));
        periods.close(project, NOW.toLocalDate()).contextWrite(fixture.allowed()).block(WAIT);
        FinancialPeriodTestSupport.rejectsClosed(
                issue.confirm(project, next, NOW).contextWrite(fixture.allowed()));
        assertEquals(saved.document().getId(), issue(first).document().getId());
        assertEquals(1, documentRows.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(1, operations.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                new BigDecimal("0.00"),
                bills.findById(nextBill.getId()).block(WAIT).getInvoiceOccupiedAmount());
    }

    @Test
    void closedOperationDateBlocksRedButOpenDayCanReverseHistoricalInvoice() {
        var bill = documentBill();
        var saved = issue(submission(input(List.of(item("RECEIVABLE", bill.getId(), "50")))));
        var request = reversal(saved.document(), "跨期纠正");
        bills.createUpdate()
                .set("financialClosed", true)
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        periods.close(project, NOW.toLocalDate()).contextWrite(fixture.allowed()).block(WAIT);
        FinancialPeriodTestSupport.rejectsClosed(
                reverse.reverse(
                                project,
                                "INVOICE",
                                saved.document().getId(),
                                request,
                                NOW.plusHours(1))
                        .contextWrite(fixture.allowed()));
        assertEquals(
                "SIMULATED_ISSUED",
                documentRows.findById(saved.document().getId()).block(WAIT).getStatus());
        assertEquals(
                new BigDecimal("50.00"),
                bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
        var red =
                reverse.reverse(
                                project,
                                "INVOICE",
                                saved.document().getId(),
                                request,
                                NOW.plusDays(1))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals("-50.00", red.document().getAmount().toPlainString());
        assertEquals(NOW.plusDays(1).toString(), red.document().getIssuedAt());
        assertTrue(bills.findById(bill.getId()).block(WAIT).getFinancialClosed());
        assertEquals(
                new BigDecimal("0.00"),
                bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
    }

    @Test
    void closedOperationDateBlocksReceiptVoidButOpenDayCanVoidUnpaidReceipt() {
        var bill = documentBill();
        var invoice = input(List.of(item("RECEIVABLE", bill.getId(), "50")));
        var input =
                new DocumentIssueInput(
                        "RECEIPT",
                        invoice.companyId(),
                        invoice.buyer(),
                        null,
                        invoice.remark(),
                        invoice.items());
        var receipt = issue(submission(input)).document();
        var request = reversal(receipt, "误开收据");
        periods.close(project, NOW.toLocalDate()).contextWrite(fixture.allowed()).block(WAIT);
        FinancialPeriodTestSupport.rejectsClosed(
                reverse.reverse(project, "RECEIPT", receipt.getId(), request, NOW.plusHours(1))
                        .contextWrite(fixture.allowed()));
        assertEquals("ISSUED", documentRows.findById(receipt.getId()).block(WAIT).getStatus());
        assertEquals(
                new BigDecimal("50.00"),
                bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
        var voided =
                reverse.reverse(project, "RECEIPT", receipt.getId(), request, NOW.plusDays(1))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals("VOIDED", voided.document().getStatus());
        assertEquals(
                new BigDecimal("0.00"),
                bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
    }

    @Test
    void successfulReverseRecoversAfterItsOperationPeriodCloses() {
        var bill = documentBill();
        var blue =
                issue(submission(input(List.of(item("RECEIVABLE", bill.getId(), "50")))))
                        .document();
        var request = reversal(blue, "原红冲恢复");
        var red = reverse(blue, request);
        periods.close(project, NOW.toLocalDate()).contextWrite(fixture.allowed()).block(WAIT);
        assertEquals(red.document().getId(), reverse(blue, request).document().getId());
        assertEquals(2, documentRows.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                new BigDecimal("0.00"),
                bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
    }

    @Test
    void openOperationDayCanIssueForHistoricalClosedReceivable() {
        var bill = documentBill();
        bills.createUpdate()
                .set("financialClosed", true)
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        periods.close(project, NOW.toLocalDate()).contextWrite(fixture.allowed()).block(WAIT);
        var input = input(List.of(item("RECEIVABLE", bill.getId(), "50")));
        var now = NOW.plusDays(1);
        var preview =
                preparation
                        .preview(project, input, now)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        var request =
                new DocumentIssueService.Submission(
                        UUID.randomUUID().toString(), preview.token(), input);
        var saved =
                issue.confirm(project, request, now).contextWrite(fixture.allowed()).block(WAIT);
        assertEquals(now.toString(), saved.document().getIssuedAt());
        assertEquals("50.00", saved.document().getAmount().toPlainString());
        assertTrue(bills.findById(bill.getId()).block(WAIT).getFinancialClosed());
    }

    @Test
    void previewDoesNotInitializeFinancialPeriodAndFailedIssueRollsBackInitialization() {
        var bill = documentBill();
        var input = input(List.of(item("RECEIVABLE", bill.getId(), "50")));
        var before = periodRows.createQuery().where("projectId", project).count().block(WAIT);
        preview(input);
        assertEquals(
                before, periodRows.createQuery().where("projectId", project).count().block(WAIT));
        var stale =
                new DocumentIssueService.Submission(
                        UUID.randomUUID().toString(), "0".repeat(64), input);
        fixture.rejects(issue.confirm(project, stale, NOW), 409);
        assertEquals(
                before, periodRows.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, documentRows.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void successfulRequestCanRecoverAfterItsOwnOccupationAndAcrossDayWithoutLiveCustomer() {
        var bill = documentBill();
        var request = submission(input(List.of(item("RECEIVABLE", bill.getId(), "100"))));
        var first = issue(request);
        assertEquals("SIMULATED_ISSUED", first.document().getStatus());
        assertEquals("SIMULATED", first.document().getChannel());
        assertTrue(first.document().getDocumentNumber().startsWith("SIM-"));
        assertEquals("100.00", first.document().getAmount().toPlainString());
        assertEquals("5.66", first.document().getTaxAmount().toPlainString());
        assertEquals("94.34", first.document().getNetAmount().toPlainString());
        assertEquals("原开票公司", first.document().getCompanyName());
        assertEquals("购方企业", first.buyer().name());
        assertEquals(1, first.lines().size());
        clearInvocations(archive);
        var again =
                issue.confirm(project, request, NOW.plusYears(1))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(first.document().getId(), again.document().getId());
        verifyNoInteractions(archive);
        assertEquals(1, documentRows.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(1, operations.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                new BigDecimal("100.00"),
                bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
        var changed =
                new DocumentIssueService.Submission(
                        request.requestId(),
                        request.token(),
                        input(List.of(item("RECEIVABLE", bill.getId(), "99"))));
        fixture.rejects(issue.confirm(project, changed, NOW), 409);
    }

    @Test
    void concurrentSameRequestReturnsOneDocumentAndDifferentRequestsCannotDoubleSpend() {
        var bill = documentBill();
        var request = submission(input(List.of(item("RECEIVABLE", bill.getId(), "100"))));
        var same =
                Mono.zip(issue.confirm(project, request, NOW), issue.confirm(project, request, NOW))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(same.getT1().document().getId(), same.getT2().document().getId());
        assertEquals(
                1, occupationRows.createQuery().where("projectId", project).count().block(WAIT));
        var otherBill = documentBill();
        var a = submission(input(List.of(item("RECEIVABLE", otherBill.getId(), "100"))));
        var b =
                new DocumentIssueService.Submission(
                        UUID.randomUUID().toString(), a.token(), a.input());
        var race =
                Mono.zip(
                                issue.confirm(project, a, NOW).materialize(),
                                issue.confirm(project, b, NOW).materialize())
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertNotEquals(race.getT1().isOnNext(), race.getT2().isOnNext());
        assertTrue(
                TaxItemPersistenceTest.status(
                        race.getT1().isOnError()
                                ? race.getT1().getThrowable()
                                : race.getT2().getThrowable(),
                        409));
        assertEquals(
                new BigDecimal("100.00"),
                bills.findById(otherBill.getId()).block(WAIT).getInvoiceOccupiedAmount());
    }

    @Test
    void staleTokenAndRevokedFrozenSpaceRejectAndNeverOccupy() {
        var bill = documentBill();
        var request = submission(input(List.of(item("RECEIVABLE", bill.getId(), "50"))));
        bills.createUpdate().set("version", 7L).where("id", bill.getId()).execute().block(WAIT);
        fixture.rejects(issue.confirm(project, request, NOW), 409);
        assertEquals(
                0, occupationRows.createQuery().where("projectId", project).count().block(WAIT));
        var fresh = submission(request.input());
        var detail = issue(fresh);
        var restricted = fixture.allowed();
        var holder = restricted.<AssetsHolder>get(AssetsHolder.class);
        when(holder.assertPermission(anyString(), anyCollection(), any()))
                .thenAnswer(
                        call ->
                                call.<Collection<String>>getArgument(1).contains("room")
                                        ? Mono.error(FinanceScope.error("permission_denied", 403))
                                        : Mono.empty());
        StepVerifier.create(issue.confirm(project, fresh, NOW).contextWrite(restricted))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        StepVerifier.create(
                        issue.detail(project, "INVOICE", detail.document().getId())
                                .contextWrite(restricted))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        fixture.rejects(issue.detail(project, "RECEIPT", detail.document().getId()), 404);
        fixture.rejects(issue.detail("forbidden", "INVOICE", detail.document().getId()), 403);
    }

    @Test
    void issueAuditFailureRollsBackAllRecordsAndOccupation() {
        var bill = documentBill();
        var request = submission(input(List.of(item("RECEIVABLE", bill.getId(), "50"))));
        var constraint = "reject_document_issue";
        sql(
                "alter table pm_fin_doc_operation add constraint "
                        + constraint
                        + " check (project_id <> '"
                        + project
                        + "')");
        try {
            StepVerifier.create(
                            issue.confirm(project, request, NOW).contextWrite(fixture.allowed()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(
                    new BigDecimal("0.00"),
                    bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
            assertEquals(
                    0, documentRows.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(
                    0, documentLines.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(
                    0,
                    occupationRows.createQuery().where("projectId", project).count().block(WAIT));
        } finally {
            sql("alter table pm_fin_doc_operation drop constraint " + constraint);
        }
        assertNotNull(issue(request));
    }

    @Test
    void simulatedRedIsNegativeAtomicIdempotentAndNotLimitedByReopenZero() {
        var bill = documentBill();
        var request = submission(input(List.of(item("RECEIVABLE", bill.getId(), "50"))));
        var blue = issue(request).document();
        var redInput = reversal(blue, "开票信息有误");
        var red = reverse(blue, redInput);
        assertEquals("SIMULATED_RED", red.document().getStatus());
        assertEquals(blue.getId(), red.document().getOriginalDocumentId());
        assertEquals("-50.00", red.document().getAmount().toPlainString());
        assertEquals("-2.83", red.document().getTaxAmount().toPlainString());
        assertEquals("-47.17", red.document().getNetAmount().toPlainString());
        assertEquals("-50.00", red.lines().get(0).snapshot().amount());
        assertEquals("购方企业", red.buyer().name());
        assertEquals(red.document().getId(), reverse(blue, redInput).document().getId());
        assertEquals(
                "SIMULATED_REVERSED", documentRows.findById(blue.getId()).block(WAIT).getStatus());
        assertEquals(
                new BigDecimal("0.00"),
                bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
        assertEquals(
                "RELEASED",
                occupationRows
                        .createQuery()
                        .where("documentId", blue.getId())
                        .fetchOne()
                        .block(WAIT)
                        .getState());
        var recovered = issue(request);
        assertEquals(blue.getId(), recovered.document().getId());
        assertEquals("SIMULATED_REVERSED", recovered.document().getStatus());
        fixture.rejects(
                reverse.reverse(
                        project,
                        "INVOICE",
                        blue.getId(),
                        new DocumentReverseService.Input(
                                redInput.requestId(), blue.getVersion(), "不同原因"),
                        NOW),
                409);
        fixture.rejects(
                reverse.reverse(project, "INVOICE", blue.getId(), reversal(blue, "再次红冲"), NOW),
                409);
    }

    @Test
    void reverseAuditFailureRestoresBlueQuotaAndNoNegativeRecordSurvives() {
        var bill = documentBill();
        var blue =
                issue(submission(input(List.of(item("RECEIVABLE", bill.getId(), "50")))))
                        .document();
        var input = reversal(blue, "红冲回滚验证");
        sql(
                "alter table pm_fin_doc_operation add constraint reject_document_reverse check"
                        + " (project_id <> '"
                        + project
                        + "' or action <> 'REVERSE')");
        try {
            StepVerifier.create(
                            reverse.reverse(
                                            project,
                                            "INVOICE",
                                            blue.getId(),
                                            input,
                                            NOW.plusHours(1))
                                    .contextWrite(fixture.allowed()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(
                    "SIMULATED_ISSUED",
                    documentRows.findById(blue.getId()).block(WAIT).getStatus());
            assertEquals(
                    new BigDecimal("50.00"),
                    bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
            assertEquals(
                    "ACTIVE",
                    occupationRows
                            .createQuery()
                            .where("documentId", blue.getId())
                            .fetchOne()
                            .block(WAIT)
                            .getState());
            assertEquals(
                    1, documentRows.createQuery().where("projectId", project).count().block(WAIT));
        } finally {
            sql("alter table pm_fin_doc_operation drop constraint reject_document_reverse");
        }
        assertEquals("SIMULATED_RED", reverse(blue, input).document().getStatus());
    }

    @Test
    void receiptVoidRetainsPaymentLinkGuardIncludingBeforeInvoiceThenPayment() {
        var bill = documentBill();
        var invoice = input(List.of(item("RECEIVABLE", bill.getId(), "50")));
        var receiptInput =
                new DocumentIssueInput(
                        "RECEIPT", "company", invoice.buyer(), null, "收据", invoice.items());
        var receipt = issue(submission(receiptInput)).document();
        assertEquals("ISSUED", receipt.getStatus());
        var voidInput = reversal(receipt, "错误收据");
        assertEquals("VOIDED", reverse(receipt, voidInput).document().getStatus());
        assertEquals("VOIDED", reverse(receipt, voidInput).document().getStatus());
        assertEquals(1, documentRows.createQuery().where("projectId", project).count().block(WAIT));
        var second = issue(submission(receiptInput)).document();
        var current = bills.findById(bill.getId()).block(WAIT);
        historicalPayment(current, "10.00");
        fixture.rejects(
                reverse.reverse(
                        project, "RECEIPT", second.getId(), reversal(second, "已收款不能作废"), NOW),
                409);
        assertEquals("ISSUED", documentRows.findById(second.getId()).block(WAIT).getStatus());
        assertEquals(
                new BigDecimal("50.00"),
                bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
    }

    @Test
    void mixedTemporaryPaymentRedReleasesOriginalDistributionAndDoesNotChangeCash() {
        var payment = collect(input());
        var originalCash = payment.payment().getAmount();
        var request =
                submission(input(List.of(item("TEMP_PAYMENT", payment.payment().getId(), "80"))));
        var blue = issue(request).document();
        var allocated =
                lines.createQuery()
                        .where("paymentId", payment.payment().getId())
                        .fetch()
                        .collectList()
                        .block(WAIT);
        assertEquals(
                new BigDecimal("80.00"),
                allocated.stream()
                        .map(PaymentAllocationEntity::getInvoiceOccupiedAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        var red = reverse(blue, reversal(blue, "临时款红冲"));
        assertEquals("-80.00", red.document().getAmount().toPlainString());
        for (var line :
                lines.createQuery()
                        .where("paymentId", payment.payment().getId())
                        .fetch()
                        .collectList()
                        .block(WAIT)) {
            assertEquals(new BigDecimal("0.00"), line.getInvoiceOccupiedAmount());
            assertEquals(new BigDecimal("0.00"), line.getRefundedAmount());
        }
        assertEquals(
                originalCash, payments.findById(payment.payment().getId()).block(WAIT).getAmount());
        assertEquals(
                "SUCCEEDED", payments.findById(payment.payment().getId()).block(WAIT).getStatus());
    }

    @Test
    void multiSourceDifferentialDocumentKeepsVouchersAndOldSnapshotsOnRed() {
        var a = documentBill();
        var b = documentBill();
        var voucher =
                new DocumentIssueInput.DifferenceVoucher(
                        "09",
                        "凭证001",
                        null,
                        null,
                        null,
                        "2024-02-15",
                        "10",
                        "其他扣除凭证说明",
                        "attachment",
                        "扣除凭证.pdf");
        var input =
                input(
                        List.of(
                                new DocumentIssueInput.Item(
                                        "RECEIVABLE",
                                        a.getId(),
                                        "50",
                                        "DIFFERENTIAL",
                                        "10",
                                        voucher),
                                item("RECEIVABLE", b.getId(), "20")));
        var blue = issue(submission(input));
        assertEquals("70.00", blue.document().getAmount().toPlainString());
        assertEquals("3.39", blue.document().getTaxAmount().toPlainString());
        assertEquals("66.61", blue.document().getNetAmount().toPlainString());
        bills.createUpdate().set("fullName", "实时已改名").where("id", a.getId()).execute().block(WAIT);
        var red = reverse(blue.document(), reversal(blue.document(), "整张红冲"));
        assertEquals("-70.00", red.document().getAmount().toPlainString());
        assertEquals("-3.39", red.document().getTaxAmount().toPlainString());
        assertEquals("-66.61", red.document().getNetAmount().toPlainString());
        assertEquals(
                blue.lines().get(0).snapshot().fullName(),
                red.lines().get(0).snapshot().fullName());
        assertEquals("attachment", red.lines().get(0).snapshot().voucher().attachmentFileId());
        assertEquals("-10.00", red.lines().get(0).snapshot().differenceAmount());
        assertEquals(
                new BigDecimal("0.00"),
                bills.findById(a.getId()).block(WAIT).getInvoiceOccupiedAmount());
        assertEquals(
                new BigDecimal("0.00"),
                bills.findById(b.getId()).block(WAIT).getInvoiceOccupiedAmount());
    }

    @Test
    void concurrentReverseSameRequestRestoresOneResultAndStaleVersionRejects() {
        var bill = documentBill();
        var blue =
                issue(submission(input(List.of(item("RECEIVABLE", bill.getId(), "100")))))
                        .document();
        fixture.rejects(
                reverse.reverse(
                        project,
                        "INVOICE",
                        blue.getId(),
                        new DocumentReverseService.Input(UUID.randomUUID().toString(), 99L, "过期版本"),
                        NOW),
                409);
        var request = reversal(blue, "并发红冲");
        var results =
                Mono.zip(
                                reverse.reverse(project, "INVOICE", blue.getId(), request, NOW),
                                reverse.reverse(project, "INVOICE", blue.getId(), request, NOW))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(results.getT1().document().getId(), results.getT2().document().getId());
        assertEquals(2, documentRows.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                1,
                operations
                        .createQuery()
                        .where("projectId", project)
                        .and("action", "REVERSE")
                        .count()
                        .block(WAIT));
        assertEquals(
                new BigDecimal("0.00"),
                bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
    }

    @Test
    void invoiceAndReceiptShareQuotaAndPaymentAliasesCannotBypassReceiptVoidGuard() {
        var bill = documentBill();
        var invoice = input(List.of(item("RECEIVABLE", bill.getId(), "80")));
        var invoiceDoc = issue(submission(invoice)).document();
        var receipt =
                new DocumentIssueInput(
                        "RECEIPT",
                        "company",
                        invoice.buyer(),
                        null,
                        null,
                        List.of(item("RECEIVABLE", bill.getId(), "21")));
        fixture.rejects(preparation.preview(project, receipt, NOW), 409);
        reverse(invoiceDoc, reversal(invoiceDoc, "退回额度"));
        var current = bills.findById(bill.getId()).block(WAIT);
        var paid = historicalPayment(current, "100.00");
        for (var item :
                List.of(
                        item("RECEIVABLE", bill.getId(), "10"),
                        item("RECEIVED", paid.lines().get(0).getId(), "10"))) {
            var r =
                    new DocumentIssueInput(
                            "RECEIPT", "company", invoice.buyer(), null, null, List.of(item));
            var doc = issue(submission(r)).document();
            fixture.rejects(
                    reverse.reverse(project, "RECEIPT", doc.getId(), reversal(doc, "付款关联"), NOW),
                    409);
        }
        assertEquals(
                new BigDecimal("20.00"),
                bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
    }

    @Test
    void httpRoutesEnforceTypeReadOnlyHistoryAndRequestReplay() {
        var props = new BillingJobProperties();
        var history = mock(DocumentRecordService.class);
        when(history.detail(anyString(), anyString(), anyString()))
                .thenAnswer(
                        call ->
                                issue.detail(
                                        call.getArgument(0),
                                        call.getArgument(1),
                                        call.getArgument(2)));
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web.InvoiceDocumentController(
                                        preparation,
                                        issue,
                                        reverse,
                                        history,
                                        null,
                                        null,
                                        props,
                                        null),
                                new org.jetlinks.pro.property.finance.web.ReceiptDocumentController(
                                        preparation,
                                        issue,
                                        reverse,
                                        history,
                                        null,
                                        null,
                                        props,
                                        null))
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange).contextWrite(fixture.allowed()))
                        .build();
        var bill = documentBill();
        var input = input(List.of(item("RECEIVABLE", bill.getId(), "50")));
        var path = "/property/finance/projects/" + project;
        var preview =
                client.post()
                        .uri(path + "/invoices/_preview")
                        .bodyValue(input)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(DocumentPreparationService.Preview.class)
                        .returnResult()
                        .getResponseBody();
        var request =
                new DocumentIssueService.Submission(
                        UUID.randomUUID().toString(), preview.token(), input);
        client.post()
                .uri(path + "/receipts/_confirm")
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isBadRequest();
        var saved =
                client.post()
                        .uri(path + "/invoices/_confirm")
                        .bodyValue(request)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(DocumentStore.Detail.class)
                        .returnResult()
                        .getResponseBody();
        var id = saved.document().getId();
        client.post()
                .uri(path + "/invoices/_confirm")
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.document.id")
                .isEqualTo(id);
        client.get()
                .uri(path + "/invoices/" + id)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.document.channel")
                .isEqualTo("SIMULATED")
                .jsonPath("$.document.amount")
                .isEqualTo("50.00")
                .jsonPath("$.document.requestFingerprint")
                .doesNotExist()
                .jsonPath("$.document.buyerJson")
                .doesNotExist()
                .jsonPath("$.lines[0].line.sourceSnapshotJson")
                .doesNotExist()
                .jsonPath("$.lines[0].snapshot.goodsCode")
                .isEqualTo("3040801");
        client.get().uri(path + "/receipts/" + id).exchange().expectStatus().isNotFound();
        client.delete().uri(path + "/invoices/" + id).exchange().expectStatus().is4xxClientError();
        client.put()
                .uri(path + "/invoices/" + id)
                .bodyValue(Map.of("amount", "0"))
                .exchange()
                .expectStatus()
                .is4xxClientError();
        var redInput = reversal(saved.document(), "HTTP模拟红冲");
        client.post()
                .uri(path + "/invoices/" + id + "/_reverse")
                .bodyValue(redInput)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.document.status")
                .isEqualTo("SIMULATED_RED")
                .jsonPath("$.document.amount")
                .isEqualTo("-50.00");
        client.post()
                .uri(path + "/invoices/" + id + "/_reverse")
                .bodyValue(redInput)
                .exchange()
                .expectStatus()
                .isOk();
        client.get()
                .uri(path + "/invoices/" + id)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.document.status")
                .isEqualTo("SIMULATED_REVERSED");
        var ri =
                new DocumentIssueInput(
                        "RECEIPT", "company", input.buyer(), null, null, input.items());
        var rp =
                client.post()
                        .uri(path + "/receipts/_preview")
                        .bodyValue(ri)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(DocumentPreparationService.Preview.class)
                        .returnResult()
                        .getResponseBody();
        var rd =
                client.post()
                        .uri(path + "/receipts/_confirm")
                        .bodyValue(
                                new DocumentIssueService.Submission(
                                        UUID.randomUUID().toString(), rp.token(), ri))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(DocumentStore.Detail.class)
                        .returnResult()
                        .getResponseBody();
        client.post()
                .uri(path + "/receipts/" + rd.document().getId() + "/_void")
                .bodyValue(reversal(rd.document(), "HTTP作废"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.document.status")
                .isEqualTo("VOIDED");
    }

    @Test
    void replayAndReverseCheckEveryFrozenSpaceEvenWhenCurrentSourcesHaveChanged() {
        var a = documentBill();
        var b = documentBill();
        bills.createUpdate()
                .set("spaceId", "secret-space")
                .where("id", b.getId())
                .execute()
                .block(WAIT);
        var request =
                submission(
                        input(
                                List.of(
                                        item("RECEIVABLE", a.getId(), "10"),
                                        item("RECEIVABLE", b.getId(), "10"))));
        var blue = issue(request).document();
        var restricted = fixture.allowed();
        var holder = restricted.<AssetsHolder>get(AssetsHolder.class);
        when(holder.assertPermission(anyString(), anyCollection(), any()))
                .thenAnswer(
                        call ->
                                call.<Collection<String>>getArgument(1).contains("secret-space")
                                        ? Mono.error(FinanceScope.error("permission_denied", 403))
                                        : Mono.empty());
        StepVerifier.create(issue.confirm(project, request, NOW).contextWrite(restricted))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        StepVerifier.create(
                        reverse.reverse(
                                        project,
                                        "INVOICE",
                                        blue.getId(),
                                        reversal(blue, "无空间权限"),
                                        NOW)
                                .contextWrite(restricted))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        assertEquals(
                "SIMULATED_ISSUED", documentRows.findById(blue.getId()).block(WAIT).getStatus());
        assertEquals(
                new BigDecimal("10.00"),
                bills.findById(a.getId()).block(WAIT).getInvoiceOccupiedAmount());
        assertEquals(
                new BigDecimal("10.00"),
                bills.findById(b.getId()).block(WAIT).getInvoiceOccupiedAmount());
        bills.createUpdate().set("deleted", true).where("id", b.getId()).execute().block(WAIT);
        clearInvocations(archive);
        assertEquals(blue.getId(), issue(request).document().getId());
        verifyNoInteractions(archive);
        fixture.rejects(issue.detail(project, "INVOICE", null), 404);
        fixture.rejects(
                reverse.reverse(project, "INVOICE", null, reversal(blue, "无票据ID"), NOW), 404);
        StepVerifier.create(issue.detail(project, "INVOICE", blue.getId()))
                .expectError()
                .verify(WAIT);
        StepVerifier.create(issue.confirm(project, request, NOW)).expectError().verify(WAIT);
        StepVerifier.create(
                        reverse.reverse(
                                project, "INVOICE", blue.getId(), reversal(blue, "未登录"), NOW))
                .expectError()
                .verify(WAIT);
    }

    @Test
    void lastFrozenLineFailureAndRedInsertFailureRollbackTheWholeDocument() {
        var a = documentBill();
        var b = documentBill();
        var request =
                submission(
                        input(
                                List.of(
                                        item("RECEIVABLE", a.getId(), "10"),
                                        item("RECEIVABLE", b.getId(), "10"))));
        sql(
                "alter table pm_fin_doc_line add constraint reject_document_line check (project_id"
                        + " <> '"
                        + project
                        + "' or line_index <> 1)");
        try {
            StepVerifier.create(
                            issue.confirm(project, request, NOW).contextWrite(fixture.allowed()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(
                    0, documentRows.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(
                    0, documentLines.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(
                    0,
                    occupationRows.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(
                    new BigDecimal("0.00"),
                    bills.findById(a.getId()).block(WAIT).getInvoiceOccupiedAmount());
            assertEquals(
                    new BigDecimal("0.00"),
                    bills.findById(b.getId()).block(WAIT).getInvoiceOccupiedAmount());
        } finally {
            sql("alter table pm_fin_doc_line drop constraint reject_document_line");
        }
        var blue = issue(request).document();
        var reverse = reversal(blue, "红票最后写入失败");
        sql(
                "alter table pm_fin_document add constraint reject_red_document check (project_id"
                        + " <> '"
                        + project
                        + "' or status <> 'SIMULATED_RED')");
        try {
            StepVerifier.create(
                            this.reverse
                                    .reverse(project, "INVOICE", blue.getId(), reverse, NOW)
                                    .contextWrite(fixture.allowed()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(
                    "SIMULATED_ISSUED",
                    documentRows.findById(blue.getId()).block(WAIT).getStatus());
            assertEquals(
                    1, documentRows.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(
                    2,
                    occupationRows
                            .createQuery()
                            .where("documentId", blue.getId())
                            .and("state", "ACTIVE")
                            .count()
                            .block(WAIT));
            assertEquals(
                    new BigDecimal("10.00"),
                    bills.findById(a.getId()).block(WAIT).getInvoiceOccupiedAmount());
            assertEquals(
                    new BigDecimal("10.00"),
                    bills.findById(b.getId()).block(WAIT).getInvoiceOccupiedAmount());
        } finally {
            sql("alter table pm_fin_document drop constraint reject_red_document");
        }
        assertEquals("SIMULATED_RED", reverse(blue, reverse).document().getStatus());
    }

    private void sql(String statement) {
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
    @Import({
        DocumentPreparationPersistenceTest.Config.class,
        DocumentOccupationLedger.class,
        DocumentStore.class,
        DocumentIssueService.class,
        DocumentReverseService.class
    })
    static class Config {}
}
