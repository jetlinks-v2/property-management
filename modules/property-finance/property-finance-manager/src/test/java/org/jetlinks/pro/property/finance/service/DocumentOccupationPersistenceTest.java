package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.document.DocumentRules.CanonicalSource;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.*;

/** 真实PG的公司和来源行锁；验证共享票据额度与可追溯反向释放。 */
@SpringBootTest(
        classes = DocumentOccupationPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DocumentOccupationPersistenceTest extends DocumentSourcePersistenceTest {
    @Autowired DocumentOccupationLedger ledger;
    @Autowired InvoiceCompanyService companies;
    @Autowired ReactiveRepository<FinanceCompanyEntity, String> companyRows;
    @Autowired ReactiveRepository<DocumentOccupationEntity, String> occupations;
    @Autowired ReactiveTransactionManager transactions;

    @BeforeEach
    void companyLockRow() {
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

    Mono<List<DocumentOccupationEntity>> occupy(
            String doc, String type, String sourceType, String sourceId, String amount) {
        return inTransaction(
                doc,
                type,
                List.of(new DocumentSourceService.Reference(sourceType, sourceId)),
                List.of(
                        new DocumentOccupationLedger.Claim(
                                new CanonicalSource(sourceType, sourceId), new BigDecimal(amount))),
                false,
                null);
    }

    Mono<List<DocumentOccupationEntity>> release(
            String doc, String sourceType, String sourceId, String reason) {
        return inTransaction(
                doc,
                null,
                List.of(new DocumentSourceService.Reference(sourceType, sourceId)),
                null,
                true,
                reason);
    }

    private Mono<List<DocumentOccupationEntity>> inTransaction(
            String doc,
            String type,
            List<DocumentSourceService.Reference> refs,
            List<DocumentOccupationLedger.Claim> claims,
            boolean release,
            String reason) {
        var action =
                companies
                        .company("company", true)
                        .then(documents.load(project, refs, true))
                        .flatMap(
                                sources ->
                                        new FinanceScope()
                                                .authenticated()
                                                .flatMap(
                                                        auth ->
                                                                release
                                                                        ? ledger.release(
                                                                                project, doc,
                                                                                reason, sources,
                                                                                auth)
                                                                        : ledger.occupy(
                                                                                project, doc, type,
                                                                                claims, sources,
                                                                                auth)));
        return TransactionalOperator.create(transactions)
                .transactional(action)
                .contextWrite(fixture.allowed());
    }

    @Test
    void invoiceReceiptShareQuotaAndReplayCannotDoubleOccupyOrChangeRequest() {
        var bill = documentBill();
        var first = occupy("invoice-a", "INVOICE", "RECEIVABLE", bill.getId(), "80.00").block(WAIT);
        assertEquals(
                first.get(0).getId(),
                occupy("invoice-a", "INVOICE", "RECEIVABLE", bill.getId(), "80.00")
                        .block(WAIT)
                        .get(0)
                        .getId());
        denied(occupy("receipt-too-large", "RECEIPT", "RECEIVABLE", bill.getId(), "20.01"));
        denied(occupy("invoice-a", "INVOICE", "RECEIVABLE", bill.getId(), "79.00"));
        occupy("receipt-b", "RECEIPT", "RECEIVABLE", bill.getId(), "20.00").block(WAIT);
        assertEquals(
                new BigDecimal("100.00"),
                bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
        assertEquals(2, occupations.createQuery().where("projectId", project).count().block(WAIT));
        release("invoice-a", "RECEIVABLE", bill.getId(), "红冲").block(WAIT);
        release("invoice-a", "RECEIVABLE", bill.getId(), "红冲").block(WAIT);
        assertEquals(
                new BigDecimal("20.00"),
                bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
        denied(occupy("invoice-a", "INVOICE", "RECEIVABLE", bill.getId(), "80.00"));
        denied(release("invoice-a", "RECEIVABLE", bill.getId(), "修改历史原因"));
    }

    @Test
    void twoDocumentsCompetingForSameBillCannotBothConsumeWholeQuota() {
        var bill = documentBill();
        var results =
                Mono.zip(
                                occupy(
                                                "parallel-a",
                                                "INVOICE",
                                                "RECEIVABLE",
                                                bill.getId(),
                                                "100.00")
                                        .materialize(),
                                occupy(
                                                "parallel-b",
                                                "RECEIPT",
                                                "RECEIVABLE",
                                                bill.getId(),
                                                "100.00")
                                        .materialize())
                        .block(WAIT);
        assertNotEquals(results.getT1().isOnNext(), results.getT2().isOnNext());
        var failed =
                results.getT1().isOnError()
                        ? results.getT1().getThrowable()
                        : results.getT2().getThrowable();
        assertTrue(TaxItemPersistenceTest.status(failed, 409));
        assertEquals(
                new BigDecimal("100.00"),
                bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
        assertEquals(1, occupations.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void temporaryMixedTenderReleaseRestoresExactOriginalLinesAndRefundProtection() {
        var payment = collect(input());
        var id = payment.payment().getId();
        occupy("temp-a", "INVOICE", "TEMP_PAYMENT", id, "50.00").block(WAIT);
        var afterFirst =
                lines.createQuery().where("paymentId", id).fetch().collectList().block(WAIT);
        var firstTouched =
                afterFirst.stream()
                        .filter(line -> line.getInvoiceOccupiedAmount().signum() > 0)
                        .toList();
        assertFalse(firstTouched.isEmpty());
        occupy("temp-b", "RECEIPT", "TEMP_PAYMENT", id, "30.00").block(WAIT);
        var refund = new org.jetlinks.pro.property.finance.payment.RefundInput();
        refund.setCompanyId("company");
        refund.setMethod("1");
        refund.setAmount(new BigDecimal("1.00"));
        refund.setRefundedAt("2024-02-15T13:00:00");
        refund.setReceiverName("客户");
        refund.setReason("退费");
        refund.setItems(
                firstTouched.stream()
                        .map(
                                line ->
                                        new org.jetlinks.pro.property.finance.payment.RefundInput
                                                .Item(
                                                line.getId(),
                                                lines.findById(line.getId())
                                                        .block(WAIT)
                                                        .getVersion()))
                        .toList());
        denied(refunds.preview(project, refund, DATE).contextWrite(fixture.allowed()));
        release("temp-b", "TEMP_PAYMENT", id, "作废收据").block(WAIT);
        for (var original : afterFirst)
            assertEquals(
                    original.getInvoiceOccupiedAmount(),
                    lines.findById(original.getId()).block(WAIT).getInvoiceOccupiedAmount());
        release("temp-a", "TEMP_PAYMENT", id, "红冲发票").block(WAIT);
        for (var line : payment.lines())
            assertEquals(
                    new BigDecimal("0.00"),
                    lines.findById(line.getId()).block(WAIT).getInvoiceOccupiedAmount());
        refund.setItems(
                firstTouched.stream()
                        .map(
                                line ->
                                        new org.jetlinks.pro.property.finance.payment.RefundInput
                                                .Item(
                                                line.getId(),
                                                lines.findById(line.getId())
                                                        .block(WAIT)
                                                        .getVersion()))
                        .toList());
        assertNotNull(
                refunds.preview(project, refund, DATE).contextWrite(fixture.allowed()).block(WAIT));
    }

    @Test
    void journalInsertFailureRollsBackCounterAndReleaseFailureRollsBackJournal() throws Exception {
        var bill = documentBill();
        var constraint = "occupation_" + UUID.randomUUID().toString().replace("-", "");
        sql(
                "ALTER TABLE pm_fin_document_occupation ADD CONSTRAINT "
                        + constraint
                        + " CHECK (project_id <> '"
                        + project
                        + "' OR document_id <> 'fail-insert')");
        try {
            StepVerifier.create(
                            occupy("fail-insert", "INVOICE", "RECEIVABLE", bill.getId(), "90.00"))
                    .expectError()
                    .verify(WAIT);
            assertEquals(
                    new BigDecimal("0.00"),
                    bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
            assertEquals(
                    0, occupations.createQuery().where("projectId", project).count().block(WAIT));
        } finally {
            sql("ALTER TABLE pm_fin_document_occupation DROP CONSTRAINT " + constraint);
        }
        occupy("valid", "INVOICE", "RECEIVABLE", bill.getId(), "90.00").block(WAIT);
        sql(
                "ALTER TABLE pm_fin_document_occupation ADD CONSTRAINT "
                        + constraint
                        + " CHECK (project_id <> '"
                        + project
                        + "' OR state <> 'RELEASED')");
        try {
            StepVerifier.create(release("valid", "RECEIVABLE", bill.getId(), "红冲"))
                    .expectError()
                    .verify(WAIT);
            assertEquals(
                    new BigDecimal("90.00"),
                    bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
            assertEquals(
                    "ACTIVE",
                    occupations
                            .createQuery()
                            .where("projectId", project)
                            .fetchOne()
                            .block(WAIT)
                            .getState());
        } finally {
            sql("ALTER TABLE pm_fin_document_occupation DROP CONSTRAINT " + constraint);
        }
    }

    private void sql(String sql) throws Exception {
        var result =
                HouseSettingPersistenceTest.DB.execInContainer(
                        "psql",
                        "-U",
                        "postgres",
                        "-d",
                        "setting_test",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-c",
                        sql);
        assertEquals(0, result.getExitCode(), result.getStderr());
    }

    @Test
    void batchPrecheckAndSecondJournalFailureLeaveEverySourceUntouched() throws Exception {
        var bill = documentBill();
        var temporary = collect(input());
        var tempId = temporary.payment().getId();
        var refs =
                List.of(
                        new DocumentSourceService.Reference("RECEIVABLE", bill.getId()),
                        new DocumentSourceService.Reference("TEMP_PAYMENT", tempId));
        var first =
                new DocumentOccupationLedger.Claim(
                        new CanonicalSource("RECEIVABLE", bill.getId()), new BigDecimal("80.00"));
        var second =
                new DocumentOccupationLedger.Claim(
                        new CanonicalSource("TEMP_PAYMENT", tempId), new BigDecimal("100.02"));
        denied(
                inTransaction(
                        "batch-precheck", "INVOICE", refs, List.of(first, second), false, null));
        assertEquals(
                new BigDecimal("0.00"),
                bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
        var constraint = "occupation_batch_" + UUID.randomUUID().toString().replace("-", "");
        sql(
                "ALTER TABLE pm_fin_document_occupation ADD CONSTRAINT "
                        + constraint
                        + " CHECK (project_id <> '"
                        + project
                        + "' OR source_type <> 'TEMP_PAYMENT')");
        second =
                new DocumentOccupationLedger.Claim(
                        new CanonicalSource("TEMP_PAYMENT", tempId), new BigDecimal("80.00"));
        try {
            StepVerifier.create(
                            inTransaction(
                                    "batch-insert",
                                    "RECEIPT",
                                    refs,
                                    List.of(first, second),
                                    false,
                                    null))
                    .expectError()
                    .verify(WAIT);
            assertEquals(
                    new BigDecimal("0.00"),
                    bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
            for (var line : temporary.lines())
                assertEquals(
                        new BigDecimal("0.00"),
                        lines.findById(line.getId()).block(WAIT).getInvoiceOccupiedAmount());
            assertEquals(
                    0, occupations.createQuery().where("projectId", project).count().block(WAIT));
        } finally {
            sql("ALTER TABLE pm_fin_document_occupation DROP CONSTRAINT " + constraint);
        }
        var saved =
                inTransaction("batch-ok", "RECEIPT", refs, List.of(first, second), false, null)
                        .block(WAIT);
        assertEquals(2, saved.size());
        assertTrue(
                saved.stream()
                        .allMatch(
                                row ->
                                        row.getAmount().compareTo(new BigDecimal("80.00")) == 0
                                                && row.getModifierId().equals("finance-tester")
                                                && row.getCreateTime() != null));
        var released = inTransaction("batch-ok", null, refs, null, true, "整票作废").block(WAIT);
        assertTrue(
                released.stream()
                        .allMatch(
                                row ->
                                        row.getState().equals("RELEASED")
                                                && row.getVersion() == 1
                                                && row.getReleasedAt() != null
                                                && row.getReleaseReason().equals("整票作废")));
        assertEquals(
                new BigDecimal("0.00"),
                bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
        for (var line : temporary.lines())
            assertEquals(
                    new BigDecimal("0.00"),
                    lines.findById(line.getId()).block(WAIT).getInvoiceOccupiedAmount());
    }

    private void denied(Mono<?> action) {
        StepVerifier.create(action)
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 409))
                .verify(WAIT);
    }

    @Test
    void corruptedReleaseAllocationCannotConsumeAnotherPaymentCounter() {
        var payment = collect(input());
        var id = payment.payment().getId();
        var occupation =
                occupy("corrupt-journal", "INVOICE", "TEMP_PAYMENT", id, "80.00")
                        .block(WAIT)
                        .get(0);
        var original = occupation.getAllocationJson();
        for (var invalid :
                List.of(
                        "{}",
                        "{\"unknown-line\":\"80.00\"}",
                        "{\"unknown-line\":\"-80.00\"}",
                        "invalid-json")) {
            occupations
                    .createUpdate()
                    .set("allocationJson", invalid)
                    .where("id", occupation.getId())
                    .execute()
                    .block(WAIT);
            StepVerifier.create(release("corrupt-journal", "TEMP_PAYMENT", id, "作废"))
                    .expectError()
                    .verify(WAIT);
            var total =
                    lines.createQuery()
                            .where("paymentId", id)
                            .fetch()
                            .map(PaymentAllocationEntity::getInvoiceOccupiedAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .block(WAIT);
            assertEquals(new BigDecimal("80.00"), total);
            assertEquals("ACTIVE", occupations.findById(occupation.getId()).block(WAIT).getState());
        }
        occupations
                .createUpdate()
                .set("allocationJson", original)
                .where("id", occupation.getId())
                .execute()
                .block(WAIT);
        release("corrupt-journal", "TEMP_PAYMENT", id, "作废").block(WAIT);
        assertEquals("RELEASED", occupations.findById(occupation.getId()).block(WAIT).getState());
    }

    @Configuration
    @Import({DocumentSourcePersistenceTest.Config.class, DocumentOccupationLedger.class})
    static class Config {}
}
