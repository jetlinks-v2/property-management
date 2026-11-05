package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.pro.property.finance.document.DocumentIssueInput;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.report.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/** 来源覆盖报表不能将收据占用、多次实收或红票另算成一份应收。 */
@SpringBootTest(
        classes = InvoiceReportPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class InvoiceReportPersistenceTest extends DocumentQueryPersistenceTest {
    @Autowired InvoiceReportService reports;
    @Autowired InvoiceReportExportService exports;
    @Autowired org.jetlinks.pro.property.finance.web.InvoiceReportController controller;

    @Autowired
    org.hswebframework.ezorm.rdb.mapping.ReactiveRepository<FundEntryEntity, String> entries;

    @Autowired
    org.hswebframework.ezorm.rdb.mapping.ReactiveRepository<FundAccountEntity, String> accounts;

    InvoiceReportInput reportInput() {
        var input = new InvoiceReportInput();
        input.setProjectIds(List.of(project));
        return input;
    }

    InvoiceReportResult report(InvoiceReportInput input) {
        return reports.query(input).contextWrite(fixture.allowed()).block(WAIT);
    }

    @Test
    void drillMatchesExactGroupingSnapshotAndExportUsesSameScope() {
        documentBill();
        var renamed = documentBill();
        bills.createUpdate()
                .set("subjectName", "历史新名")
                .where("id", renamed.getId())
                .execute()
                .block(WAIT);
        var i = reportInput();
        i.setSubjectGroups(List.of(new ReportGroupKey("subject", "历史新名")));
        assertEquals("100.00", report(i).summary().receivableAmount());
        assertEquals(1, report(i).page().getTotal());
        assertEquals(
                report(i).summary(),
                reports.exportData(i).contextWrite(fixture.allowed()).block(WAIT).summary());
    }

    @Test
    void relatedInvoicesIncludeBlueRedAndContentWithoutMultiplyingBasis() {
        var bill = documentBill();
        var first =
                issue(submission(input(List.of(item("RECEIVABLE", bill.getId(), "20")))))
                        .document();
        issue(submission(input(List.of(item("RECEIVABLE", bill.getId(), "30")))));
        reverse(first, reversal(first, "红冲核对"));
        var report = report(reportInput());
        assertEquals("100.00", report.summary().receivableAmount());
        assertEquals("30.00", report.summary().invoicedAmount());
        assertFalse(report.page().getData().get(0).invoiceContent().isBlank());
        var related =
                reports.related(project, "RECEIVABLE", bill.getId())
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(3, related.size());
        assertTrue(related.stream().anyMatch(d -> d.amount().startsWith("-")));
    }

    @Test
    void reportIncludesUnissuedAndIgnoresReceiptOccupation() {
        var a = documentBill();
        var invoice = input(List.of(item("RECEIVABLE", a.getId(), "20")));
        issue(
                submission(
                        new DocumentIssueInput(
                                "RECEIPT",
                                "company",
                                invoice.buyer(),
                                null,
                                null,
                                invoice.items())));
        var result = report(reportInput());
        assertEquals(1, result.page().getTotal());
        assertEquals("100.00", result.summary().receivableAmount());
        assertEquals("0.00", result.summary().invoicedAmount());
        assertEquals("100.00", result.summary().uninvoicedAmount());
        assertEquals("UNISSUED", result.page().getData().get(0).invoiceStatus());
        assertNull(result.page().getData().get(0).invoiceNo());
        assertEquals(
                new BigDecimal("20.00"),
                bills.findById(a.getId()).block(WAIT).getInvoiceOccupiedAmount());
    }

    @Test
    void reportSumsPartialInvoicesOnceAndRedReleasesOnlyItsOwnAmount() {
        var a = documentBill();
        var blue =
                issue(submission(input(List.of(item("RECEIVABLE", a.getId(), "20"))))).document();
        issue(submission(input(List.of(item("RECEIVABLE", a.getId(), "30")))));
        var before = report(reportInput());
        assertEquals(1, before.page().getTotal());
        assertEquals("50.00", before.summary().invoicedAmount());
        assertEquals("50.00", before.summary().uninvoicedAmount());
        assertEquals("PARTIAL", before.page().getData().get(0).invoiceStatus());
        reverse(blue, reversal(blue, "原部分金额红冲"));
        var after = report(reportInput());
        assertEquals("30.00", after.summary().invoicedAmount());
        assertEquals("70.00", after.summary().uninvoicedAmount());
        assertEquals(1, after.page().getTotal());
    }

    @Test
    void reportLatestBlueUsesCreationOrderWhenBusinessIssueTimesTie() {
        var a = documentBill();
        var first =
                issue(submission(input(List.of(item("RECEIVABLE", a.getId(), "20"))))).document();
        var second =
                issue(submission(input(List.of(item("RECEIVABLE", a.getId(), "30"))))).document();
        // 同秒开票时随机ID不能代表先后：用逆字典序ID明确构造旧票已红冲、新票有效。
        var older = first.getId().compareTo(second.getId()) > 0 ? first : second;
        var newer = older == first ? second : first;
        documentRows
                .createUpdate()
                .set("createTime", 1000L)
                .where("id", older.getId())
                .execute()
                .block(WAIT);
        documentRows
                .createUpdate()
                .set("createTime", 2000L)
                .where("id", newer.getId())
                .execute()
                .block(WAIT);
        reverse(older, reversal(older, "旧蓝票红冲，新蓝票仍有效"));
        var result = report(reportInput());
        assertEquals(1, result.page().getTotal());
        assertEquals(newer.getDocumentNumber(), result.page().getData().get(0).invoiceNo());
        assertEquals(newer.getId(), result.page().getData().get(0).documentId());
        assertEquals(newer == first ? "20.00" : "30.00", result.summary().invoicedAmount());
        var query = reportInput();
        query.setInvoiceNo(older.getDocumentNumber());
        assertEquals(0, report(query).page().getTotal());
    }

    @Test
    void reportFullyReversedSourceKeepsBlueNumberAndDateFiltersExcludeNeverIssued() {
        var a = documentBill();
        documentBill();
        var blue =
                issue(submission(input(List.of(item("RECEIVABLE", a.getId(), "100"))))).document();
        reverse(blue, reversal(blue, "全部红冲"));
        var input = reportInput();
        input.setInvoiceStatus("REVERSED");
        var result = report(input);
        assertEquals(1, result.page().getTotal());
        assertEquals(blue.getDocumentNumber(), result.page().getData().get(0).invoiceNo());
        assertEquals("0.00", result.summary().invoicedAmount());
        input.setInvoiceDateStart("2024-02-15");
        input.setInvoiceDateEnd("2024-02-15");
        assertEquals(1, report(input).page().getTotal());
        input.setInvoiceNo(blue.getDocumentNumber());
        assertEquals(1, report(input).page().getTotal());
        input.setInvoiceType("2");
        assertEquals(0, report(input).page().getTotal());
        input.setInvoiceType(null);
        input.setInvoiceDateStart("2024-02-16");
        input.setInvoiceDateEnd("2024-02-16");
        assertEquals(0, report(input).page().getTotal());
    }

    @Test
    void reportReceivableIsNotRepeatedForDifferentPaymentsAndSummaryIgnoresPageSize() {
        var a = documentBill();
        historicalPayment(a, "10.00");
        a = bills.findById(a.getId()).block(WAIT);
        historicalPayment(a, "20.00");
        documentBill();
        var input = reportInput();
        input.setPageSize(1);
        var first = report(input);
        assertEquals(2, first.page().getTotal());
        assertEquals("200.00", first.summary().receivableAmount());
        assertEquals("30.00", first.summary().receivedAmount());
        input.setPageIndex(1);
        var second = report(input);
        assertNotEquals(first.page().getData().get(0).id(), second.page().getData().get(0).id());
        assertEquals(first.summary(), second.summary());
        input.setGroupBy("PROJECT");
        input.setPageIndex(0);
        var grouped = report(input);
        assertEquals(1, grouped.page().getTotal());
        assertEquals(first.summary(), grouped.summary());
        assertEquals(2L, grouped.page().getData().get(0).rowCount());
    }

    @Test
    void reportCanonicalTemporaryMixedPaymentsUseNetRefundAndFrozenNames() {
        var paid = collect(input());
        var line = paid.lines().get(0);
        lines.createUpdate()
                .set("refundedAmount", new BigDecimal("10.00"))
                .where("id", line.getId())
                .execute()
                .block(WAIT);
        var query = reportInput();
        query.setSourceType("TEMP_PAYMENT");
        var result = report(query);
        assertEquals(1, result.page().getTotal());
        assertEquals(paid.payment().getId(), result.page().getData().get(0).id());
        assertEquals("90.01", result.summary().receivableAmount());
        assertEquals("90.01", result.summary().receivedAmount());
        assertEquals("90.01", result.summary().uninvoicedAmount());
        issue(submission(input(List.of(item("TEMP_PAYMENT", paid.payment().getId(), "20")))));
        assertEquals("20.00", report(query).summary().invoicedAmount());
        assertEquals("70.01", report(query).summary().uninvoicedAmount());
    }

    @Test
    void reportOriginalFundBasisDoesNotBecomeAccountBalanceOrDisappearWhenAccountDeleted() {
        var fixture = new ReceivedReportPersistenceTest();
        fixture.project = project;
        fixture.accounts = accounts;
        fixture.entries = entries;
        var topup = fixture.fund("88.08", "TOPUP", "ADVANCE", "2024-02-15T12:00:00");
        fixture.fund("-10.00", "REFUND", "DEPOSIT", "2024-02-15T12:00:00");
        accounts.createUpdate()
                .set("deleted", true)
                .set("balance", BigDecimal.ZERO)
                .where("id", topup.getAccountId())
                .execute()
                .block(WAIT);
        var query = reportInput();
        query.setSourceType("FUND_TOPUP");
        var result = report(query);
        assertEquals(1, result.page().getTotal());
        assertEquals("88.08", result.summary().receivableAmount());
        assertEquals("88.08", result.summary().receivedAmount());
        assertEquals(topup.getSubjectName(), result.page().getData().get(0).subjectName());
    }

    @Test
    void reportRealReadScopeFiltersBeforeSummaryAndHidesMixedInvoiceMetadata() {
        var a = documentBill();
        var b = documentBill();
        bills.createUpdate()
                .set("spaceId", "secret-room")
                .where("id", b.getId())
                .execute()
                .block(WAIT);
        var blue =
                issue(
                                submission(
                                        input(
                                                List.of(
                                                        item("RECEIVABLE", a.getId(), "10"),
                                                        item("RECEIVABLE", b.getId(), "20")))))
                        .document();
        var authority = new CarryForwardAuthorityTest();
        authority.holders();
        try {
            var restricted =
                    authorized(
                            authority,
                            Set.of("room"),
                            org.jetlinks.pro.assets.crud.CrudAssetPermission.read.getMask());
            var result = reports.query(reportInput()).contextWrite(restricted).block(WAIT);
            assertEquals(1, result.page().getTotal());
            assertEquals("100.00", result.summary().receivableAmount());
            assertEquals("10.00", result.summary().invoicedAmount());
            assertNull(result.page().getData().get(0).invoiceNo());
            assertNull(result.page().getData().get(0).buyerName());
            assertNull(result.page().getData().get(0).documentId());
            assertEquals(
                    0,
                    reports.related(project, "RECEIVABLE", a.getId())
                            .contextWrite(restricted)
                            .block(WAIT)
                            .size());
            var q = reportInput();
            q.setInvoiceNo(blue.getDocumentNumber());
            assertEquals(
                    0, reports.query(q).contextWrite(restricted).block(WAIT).page().getTotal());
            var full =
                    authorized(
                            authority,
                            Set.of("room", "secret-room"),
                            org.jetlinks.pro.assets.crud.CrudAssetPermission.read.getMask());
            var all = reports.query(q).contextWrite(full).block(WAIT);
            assertEquals(2, all.page().getTotal());
            assertEquals("30.00", all.summary().invoicedAmount());
            assertEquals(blue.getDocumentNumber(), all.page().getData().get(0).invoiceNo());
            assertEquals(
                    1,
                    reports.related(project, "RECEIVABLE", a.getId())
                            .contextWrite(full)
                            .block(WAIT)
                            .size());
        } finally {
            authority.close();
        }
    }

    @Test
    void reportRejectsInvalidFiltersUnauthorizedProjectAndNoAuthWithoutWriting() {
        documentBill();
        for (String invalid : List.of("any", "RECEIVED", "REFUND")) {
            var q = reportInput();
            q.setSourceType(invalid);
            fixture.rejects(reports.query(q), 400);
        }
        var q = reportInput();
        q.setProjectIds(List.of(project, "forbidden"));
        fixture.rejects(reports.query(q), 403);
        q = reportInput();
        q.setInvoiceDateStart("2024-02-30");
        fixture.rejects(reports.query(q), 400);
        q = reportInput();
        q.setInvoiceStatus("SIMULATED_ISSUED");
        fixture.rejects(reports.query(q), 400);
        q = reportInput();
        q.setInvoiceType("3");
        fixture.rejects(reports.query(q), 400);
        reactor.test.StepVerifier.create(reports.query(reportInput())).expectError().verify(WAIT);
        var versions =
                bills.createQuery()
                        .where("projectId", project)
                        .fetch()
                        .map(FinanceEntity::getVersion)
                        .collectList()
                        .block(WAIT);
        q = reportInput();
        q.setCustomerName("' OR 1=1 --");
        assertEquals(0, report(q).page().getTotal());
        assertEquals(
                versions,
                bills.createQuery()
                        .where("projectId", project)
                        .fetch()
                        .map(FinanceEntity::getVersion)
                        .collectList()
                        .block(WAIT));
    }

    @Test
    void reportFilesAndHttpPreserveExactAmountsChineseAndSafeUserText() throws Exception {
        var bill = documentBill();
        bills.createUpdate()
                .set("customerName", "=SUM(1,2)<script>evil</script>")
                .set("actualReceivableAmount", new BigDecimal("9999999999999999.99"))
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                controller)
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange).contextWrite(fixture.allowed()))
                        .build();
        String root = "/property/finance/reports/invoices";
        client.post()
                .uri(root + "/_query")
                .bodyValue(reportInput())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.summary.receivableAmount")
                .isEqualTo("9999999999999999.99")
                .jsonPath("$.page.data[0].snapshotJson")
                .doesNotExist();
        var bytes =
                client.post()
                        .uri(root + "/_export/excel")
                        .bodyValue(reportInput())
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectHeader()
                        .contentType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        .expectBody(byte[].class)
                        .returnResult()
                        .getResponseBody();
        try (var book =
                new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                        new java.io.ByteArrayInputStream(bytes))) {
            var sheet = book.getSheetAt(0);
            assertEquals("开票统计", sheet.getSheetName());
            assertEquals("项目", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(
                    org.apache.poi.ss.usermodel.CellType.STRING,
                    sheet.getRow(1).getCell(2).getCellType());
            assertEquals(
                    "=SUM(1,2)<script>evil</script>",
                    sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("9999999999999999.99", sheet.getRow(1).getCell(13).getStringCellValue());
        }
        var pdf = exports.export(reportInput(), "pdf").contextWrite(fixture.allowed()).block(WAIT);
        try (var doc = org.apache.pdfbox.pdmodel.PDDocument.load(pdf)) {
            var text =
                    java.text.Normalizer.normalize(
                            new org.apache.pdfbox.text.PDFTextStripper().getText(doc),
                            java.text.Normalizer.Form.NFKC);
            assertTrue(text.contains("开票统计"), text);
            assertTrue(text.contains("9999999999999999.99"), text);
            assertTrue(
                    text.replaceAll("\\s+", "").contains("=SUM(1,2)<script>evil</script>"), text);
        }
        client.post()
                .uri(root + "/_export/html")
                .bodyValue(reportInput())
                .exchange()
                .expectStatus()
                .isBadRequest();
        client.delete().uri(root).exchange().expectStatus().isNotFound();
        assertEquals(
                "property-finance-report-invoice",
                controller
                        .getClass()
                        .getAnnotation(
                                org.hswebframework.web.authorization.annotation.Resource.class)
                        .id());
        assertEquals(
                "export",
                controller
                        .getClass()
                        .getMethod("export", InvoiceReportInput.class, String.class)
                        .getAnnotation(
                                org.hswebframework.web.authorization.annotation.ResourceAction
                                        .class)
                        .id());
    }

    @Test
    void reportExportRejectsOver10000ButGroupedExportUsesFullFilteredTotal() {
        var template = documentBill();
        var bulk = new ArrayList<ReceivableEntity>();
        for (int n = 0; n < 10000; n++) {
            var row = new ReceivableEntity();
            org.springframework.beans.BeanUtils.copyProperties(template, row);
            row.setId(UUID.randomUUID().toString());
            row.setBillKey(row.getId());
            bulk.add(row);
        }
        reactor.core.publisher.Flux.fromIterable(bulk)
                .buffer(250)
                .concatMap(bills::insertBatch)
                .then()
                .block(WAIT);
        fixture.rejects(reports.exportData(reportInput()), 400);
        var input = reportInput();
        input.setGroupBy("PROJECT");
        var result = reports.exportData(input).contextWrite(fixture.allowed()).block(WAIT);
        assertEquals(1, result.page().getTotal());
        assertEquals(10001L, result.page().getData().get(0).rowCount());
        assertEquals("1000100.00", result.summary().receivableAmount());
    }

    @Configuration
    @Import({
        DocumentQueryPersistenceTest.Config.class,
        InvoiceReportService.class,
        InvoiceReportFile.class,
        InvoiceReportExportService.class,
        org.jetlinks.pro.property.finance.web.InvoiceReportController.class
    })
    static class Config {}
}
