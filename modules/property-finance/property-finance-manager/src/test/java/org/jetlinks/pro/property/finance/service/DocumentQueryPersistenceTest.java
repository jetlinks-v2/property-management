package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.impl.entity.DimensionAssetBindingEntity;
import org.jetlinks.pro.property.finance.document.DocumentIssueInput;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.*;

/** 真实PG分页及平台DimensionAssetsTerm验证：只拥有部分房屋不能看到整票或临时主单。 */
@SpringBootTest(
        classes = DocumentQueryPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DocumentQueryPersistenceTest extends DocumentIssuePersistenceTest {
    @Autowired DocumentRecordService history;
    @Autowired DocumentCandidateService candidates;
    @Autowired DocumentFileService files;
    @Autowired org.hswebframework.ezorm.rdb.operator.DatabaseOperator database;
    @Autowired ReactiveRepository<DimensionAssetBindingEntity, String> assetBindings;

    QueryParamEntity page(int index, int size) {
        var q = new QueryParamEntity();
        q.setPageIndex(index);
        q.setPageSize(size);
        return q;
    }

    DocumentCandidateService.Input candidate(
            String type, String company, String customer, List<String> rooms) {
        return new DocumentCandidateService.Input(type, company, customer, rooms, null, 0, 12);
    }

    @Test
    void ledgerSeparatesCurrentPaymentsFromIssueSnapshotAndExportsFrozenBusinessColumns()
            throws Exception {
        var bill = documentBill();
        var input = input(List.of(item("RECEIVABLE", bill.getId(), "10")));
        var receiptInput =
                new DocumentIssueInput(
                        "RECEIPT", input.companyId(), input.buyer(), null, "台账备注", input.items());
        var receipt = issue(submission(receiptInput)).document();
        var before =
                history.ledger(project, "RECEIPT", page(0, 12))
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .getData()
                        .get(0);
        assertEquals("UNPAID", before.getPaymentStatus());
        assertEquals("UNPAID", before.getIssuePaymentStatus());
        assertEquals("ISSUED", before.getStatus());
        cashier.collect(
                        project,
                        fixture.submission(
                                fixture.input(bills.findById(bill.getId()).block(WAIT), "100.00")),
                        DATE)
                .contextWrite(fixture.allowed())
                .block(WAIT);
        bills.createUpdate()
                .set("snapshotJson", "{}")
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        var after =
                history.ledger(project, "RECEIPT", page(0, 12))
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .getData()
                        .get(0);
        var paidFilter = page(0, 1);
        paidFilter.toQuery().and("paymentStatus", "PAID");
        assertEquals(
                1,
                history.ledger(project, "RECEIPT", paidFilter)
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .getTotal());
        var unpaidFilter = page(0, 1);
        unpaidFilter.toQuery().and("paymentStatus", "UNPAID");
        assertEquals(
                0,
                history.ledger(project, "RECEIPT", unpaidFilter)
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .getTotal());
        assertEquals("PAID", after.getPaymentStatus());
        assertEquals("100.00", after.getPaidAmount());
        assertEquals("0.00", after.getIssuePaidAmount());
        assertEquals("UNPAID", after.getIssuePaymentStatus());
        assertEquals("园区/1栋/101", after.getFullName());
        assertEquals("原物业服务", after.getSources().get(0).goodsName());
        assertEquals(receipt.getDocumentNumber(), after.getDocumentNumber());
        var q = page(0, 1);
        q.toQuery().like("fullName", "%101%");
        assertEquals(
                1,
                history.ledger(project, "RECEIPT", q)
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .getTotal());
        var excel = files.excel(project, "RECEIPT", q).contextWrite(fixture.allowed()).block(WAIT);
        try (var workbook =
                new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                        new java.io.ByteArrayInputStream(excel))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("票据号", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(
                    receipt.getDocumentNumber(), sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("已收款", sheet.getRow(1).getCell(10).getStringCellValue());
            assertEquals("未收款", sheet.getRow(1).getCell(12).getStringCellValue());
            assertEquals("台账备注", sheet.getRow(1).getCell(19).getStringCellValue());
        }
        bills.createUpdate().set("deleted", true).where("id", bill.getId()).execute().block(WAIT);
        var unknown =
                history.ledger(project, "RECEIPT", page(0, 12))
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .getData()
                        .get(0);
        assertEquals("UNKNOWN", unknown.getPaymentStatus());
        assertNull(unknown.getPaidAmount());
        assertEquals("0.00", unknown.getIssuePaidAmount());
    }

    @Test
    void invoicePdfCarriesBusinessLabelsAndHistoricalTax() throws Exception {
        var bill = documentBill();
        var blue =
                issue(submission(input(List.of(item("RECEIVABLE", bill.getId(), "10")))))
                        .document();
        bills.createUpdate()
                .set("snapshotJson", "{}")
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        var pdf =
                files.invoicePdf(project, blue.getId()).contextWrite(fixture.allowed()).block(WAIT);
        assertTrue(
                new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"));
        try (var parsed = org.apache.pdfbox.pdmodel.PDDocument.load(pdf)) {
            var text =
                    java.text.Normalizer.normalize(
                                    new org.apache.pdfbox.text.PDFTextStripper().getText(parsed),
                                    java.text.Normalizer.Form.NFKC)
                            .replaceAll("\\s+", "");
            var output = java.nio.file.Path.of("target", "document-preview");
            java.nio.file.Files.createDirectories(output);
            java.nio.file.Files.write(output.resolve("simulated-invoice.pdf"), pdf);
            javax.imageio.ImageIO.write(
                    new org.apache.pdfbox.rendering.PDFRenderer(parsed).renderImageWithDPI(0, 100),
                    "png",
                    output.resolve("simulated-invoice.png").toFile());
            assertTrue(text.contains("发票"));
            assertFalse(text.contains("模拟"));
            assertTrue(text.contains("不作为税务发票使用"));
            assertTrue(text.contains("原物业服务"));
            assertTrue(text.contains(blue.getDocumentNumber()));
        }
        fixture.rejects(files.invoicePdf("forbidden", blue.getId()), 403);
    }

    @Test
    void historyIsPagedByWholeDocumentsAndPreservesBlueRedAndVoidedFacts() {
        var a = documentBill();
        var b = documentBill();
        var blue =
                issue(
                                submission(
                                        input(
                                                List.of(
                                                        item("RECEIVABLE", a.getId(), "10"),
                                                        item("RECEIVABLE", b.getId(), "20")))))
                        .document();
        var red = reverse(blue, reversal(blue, "已红冲"));
        var invoice = input(List.of(item("RECEIVABLE", a.getId(), "5")));
        var receipt =
                issue(
                                submission(
                                        new DocumentIssueInput(
                                                "RECEIPT",
                                                "company",
                                                invoice.buyer(),
                                                null,
                                                null,
                                                invoice.items())))
                        .document();
        reverse(receipt, reversal(receipt, "已作废"));
        var q = page(0, 1);
        q.setPaging(false);
        q.setForUpdate(true);
        q.orderBy("documentNumber");
        var first =
                history.query(project, "INVOICE", q).contextWrite(fixture.allowed()).block(WAIT);
        var second =
                history.query(project, "INVOICE", page(1, 1))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(2, first.getTotal());
        assertEquals(1, first.getData().size());
        assertEquals(1, second.getData().size());
        var all =
                history.query(project, "INVOICE", page(0, 12))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(
                Set.of(blue.getId(), red.document().getId()),
                all.getData().stream()
                        .map(DocumentEntity::getId)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                Set.of("SIMULATED_REVERSED", "SIMULATED_RED"),
                all.getData().stream()
                        .map(DocumentEntity::getStatus)
                        .collect(java.util.stream.Collectors.toSet()));
        var receipts =
                history.query(project, "RECEIPT", page(0, 12))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(1, receipts.getTotal());
        assertEquals("VOIDED", receipts.getData().get(0).getStatus());
        var forged = page(0, 12);
        forged.toQuery().or("projectId", "foreign").or("documentType", "RECEIPT");
        assertEquals(
                0,
                history.query(project, "INVOICE", forged)
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .getTotal());
        var title = page(0, 12);
        title.toQuery().and("buyerName", blue.getBuyerName());
        assertEquals(
                2,
                history.query(project, "INVOICE", title)
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .getTotal());
        title.setTerms(new java.util.ArrayList<>());
        title.toQuery().and("buyerName", "不存在的抬头");
        assertEquals(
                0,
                history.query(project, "INVOICE", title)
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .getTotal());
        assertTrue(q.isForUpdate());
        assertFalse(q.isPaging());
    }

    @Test
    void realHolderFiltersWholeMixedDocumentBeforePagingAndDoesNotLeakItsTotal() {
        var a = documentBill();
        var b = documentBill();
        bills.createUpdate()
                .set("spaceId", "secret-room")
                .where("id", b.getId())
                .execute()
                .block(WAIT);
        var visible =
                issue(submission(input(List.of(item("RECEIVABLE", a.getId(), "10"))))).document();
        var visibleSecond =
                issue(submission(input(List.of(item("RECEIVABLE", a.getId(), "5"))))).document();
        issue(
                submission(
                        input(
                                List.of(
                                        item("RECEIVABLE", a.getId(), "10"),
                                        item("RECEIVABLE", b.getId(), "10")))));
        var authority = new CarryForwardAuthorityTest();
        authority.holders();
        try {
            var restricted = authorized(authority, Set.of("room"), CrudAssetPermission.all_mask);
            var actual = new HashSet<String>();
            for (int index : List.of(0, 1)) {
                var result =
                        history.query(project, "INVOICE", page(index, 1))
                                .contextWrite(restricted)
                                .block(WAIT);
                assertEquals(2, result.getTotal());
                assertEquals(1, result.getData().size());
                assertEquals(index, result.getPageIndex());
                actual.add(result.getData().get(0).getId());
            }
            assertEquals(Set.of(visible.getId(), visibleSecond.getId()), actual);
            var noRooms =
                    authorized(authority, Set.of("unrelated-room"), CrudAssetPermission.all_mask);
            assertEquals(
                    0,
                    history.query(project, "INVOICE", page(0, 1))
                            .contextWrite(noRooms)
                            .block(WAIT)
                            .getTotal());
            var both =
                    authorized(
                            authority, Set.of("room", "secret-room"), CrudAssetPermission.all_mask);
            assertEquals(
                    3,
                    history.query(project, "INVOICE", page(0, 1))
                            .contextWrite(both)
                            .block(WAIT)
                            .getTotal());
        } finally {
            authority.close();
        }
    }

    @Test
    void logsHaveIndependentPagingButRequireWholeOriginalDocumentScope() {
        var bill = documentBill();
        var blue =
                issue(submission(input(List.of(item("RECEIVABLE", bill.getId(), "50")))))
                        .document();
        var red = reverse(blue, reversal(blue, "红冲原因保留"));
        var rows =
                history.logs(project, "INVOICE", blue.getId(), page(0, 1))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(2, rows.getTotal());
        assertEquals(1, rows.getData().size());
        assertEquals("REVERSE", rows.getData().get(0).getAction());
        assertEquals("红冲原因保留", rows.getData().get(0).getReason());
        var redLogs =
                history.logs(project, "INVOICE", red.document().getId(), page(0, 12))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(1, redLogs.getTotal());
        assertEquals("REVERSE", redLogs.getData().get(0).getAction());
        assertEquals(blue.getId(), redLogs.getData().get(0).getDocumentId());
        assertEquals(red.document().getId(), redLogs.getData().get(0).getResultDocumentId());
        assertEquals(blue.getBuyerName(), red.document().getBuyerName());
        fixture.rejects(history.logs(project, "RECEIPT", blue.getId(), page(0, 1)), 404);
        fixture.rejects(history.query("forbidden", "INVOICE", page(0, 1)), 403);
        StepVerifier.create(history.query(project, "INVOICE", page(0, 1)))
                .expectError()
                .verify(WAIT);
        var authority = new CarryForwardAuthorityTest();
        authority.holders();
        try {
            var restricted =
                    authorized(authority, Set.of("unrelated-room"), CrudAssetPermission.all_mask);
            StepVerifier.create(
                            history.logs(project, "INVOICE", blue.getId(), page(0, 1))
                                    .contextWrite(restricted))
                    .expectError()
                    .verify(WAIT);
        } finally {
            authority.close();
        }
    }

    @Test
    void candidatesUseCanonicalPaymentOnceAndFrozenTaxWithCorrectPagingAndSearch() {
        var bill = documentBill();
        var payment = collect(input());
        var temp =
                candidates
                        .query(
                                project,
                                "INVOICE",
                                candidate("TEMP_PAYMENT", "company", "customer", List.of("room")),
                                NOW)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(1, temp.getTotal());
        assertEquals(1, temp.getData().size());
        assertEquals(payment.payment().getId(), temp.getData().get(0).sourceId());
        assertEquals("100.01", temp.getData().get(0).availableAmount());
        assertEquals("0.06", temp.getData().get(0).taxRate());
        assertEquals("原开票公司", temp.getData().get(0).companyName());
        assertTrue(temp.getData().get(0).selectable());
        var before =
                candidates
                        .query(
                                project,
                                "INVOICE",
                                candidate("RECEIVABLE", "company", "customer", List.of("room")),
                                NOW)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(1, before.getTotal());
        assertEquals(bill.getId(), before.getData().get(0).sourceId());
        assertEquals("100.00", before.getData().get(0).availableAmount());
        assertEquals("园区/1栋/101", before.getData().get(0).fullName());
        var none =
                new DocumentCandidateService.Input(
                        "RECEIVABLE", "company", "customer", List.of("room"), "不存在的房屋", 0, 1);
        assertEquals(
                0,
                candidates
                        .query(project, "INVOICE", none, NOW)
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .getTotal());
        assertEquals(0, documentRows.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                new BigDecimal("0.00"),
                bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
    }

    @Test
    void exhaustedAndExpiredCandidatesRemainVisibleWithExplicitReason() {
        var bill = documentBill();
        issue(submission(input(List.of(item("RECEIVABLE", bill.getId(), "100")))));
        var q = candidate("RECEIVABLE", "company", "customer", null);
        var exhausted =
                candidates
                        .query(project, "INVOICE", q, NOW)
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .getData()
                        .get(0);
        assertFalse(exhausted.selectable());
        assertEquals(
                "error.property_finance_document_quota_insufficient", exhausted.unavailableCode());
        var paid = documentBill();
        cashier.collect(project, fixture.submission(fixture.input(paid, "100.00")), DATE)
                .contextWrite(fixture.allowed())
                .block(WAIT);
        var query =
                new DocumentCandidateService.Input("RECEIVABLE", null, null, null, null, 0, 200);
        var expired =
                candidates
                        .query(project, "INVOICE", query, NOW.plusYears(1))
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .getData()
                        .stream()
                        .filter(r -> r.sourceId().equals(paid.getId()))
                        .findFirst()
                        .orElseThrow();
        assertFalse(expired.selectable());
        assertEquals("error.property_finance_document_expired", expired.unavailableCode());
        var receipt =
                candidates
                        .query(project, "RECEIPT", query, NOW.plusYears(1))
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .getData()
                        .stream()
                        .filter(r -> r.sourceId().equals(paid.getId()))
                        .findFirst()
                        .orElseThrow();
        assertTrue(receipt.selectable());
        fixture.rejects(
                candidates.query(project, "INVOICE", candidate("RECEIVED", null, null, null), NOW),
                400);
    }

    @Test
    void realHolderFiltersCandidatesBeforePaginationAndReadOnlyPermissionCannotSelect() {
        var a = documentBill();
        var b = documentBill();
        bills.createUpdate()
                .set("spaceId", "secret-room")
                .where("id", b.getId())
                .execute()
                .block(WAIT);
        var payment = collect(input());
        var authority = new CarryForwardAuthorityTest();
        authority.holders();
        try {
            var scoped = authorized(authority, Set.of("room"), CrudAssetPermission.all_mask);
            var q = new DocumentCandidateService.Input("RECEIVABLE", null, null, null, null, 0, 1);
            var found =
                    candidates.query(project, "INVOICE", q, NOW).contextWrite(scoped).block(WAIT);
            assertEquals(1, found.getTotal());
            assertEquals(a.getId(), found.getData().get(0).sourceId());
            var readonly =
                    authorized(authority, Set.of("room"), CrudAssetPermission.read.getMask());
            assertEquals(
                    0,
                    candidates
                            .query(project, "INVOICE", q, NOW)
                            .contextWrite(readonly)
                            .block(WAIT)
                            .getTotal());
            // 损坏临时主单混入隐藏房屋时也必须分页前整单隐藏，不能等加载快照后才暴露失败。
            lines.createUpdate()
                    .set("spaceId", "secret-room")
                    .where("id", payment.lines().get(1).getId())
                    .execute()
                    .block(WAIT);
            var temps = candidate("TEMP_PAYMENT", "company", null, null);
            assertEquals(
                    0,
                    candidates
                            .query(project, "INVOICE", temps, NOW)
                            .contextWrite(scoped)
                            .block(WAIT)
                            .getTotal());
        } finally {
            authority.close();
        }
    }

    @Test
    void pagingCapsFiltersAndTenantIsolationAreEnforcedWithoutChangingInput() {
        var bill = documentBill();
        var blue =
                issue(submission(input(List.of(item("RECEIVABLE", bill.getId(), "10")))))
                        .document();
        var q = page(-1, 1000);
        q.setTotal(12345);
        var result =
                history.query(project, "INVOICE", q).contextWrite(fixture.allowed()).block(WAIT);
        assertEquals(1, result.getTotal());
        assertEquals(0, result.getPageIndex());
        assertEquals(200, result.getPageSize());
        assertEquals(12345, q.getTotal());
        assertEquals(1000, q.getPageSize());
        var otherTenant =
                fixture.allowed()
                        .put(
                                org.jetlinks.pro.tenant.holder.TenantHolder.CONTEXT_KEY,
                                "other-tenant");
        assertEquals(
                0,
                history.query(project, "INVOICE", page(0, 12))
                        .contextWrite(otherTenant)
                        .block(WAIT)
                        .getTotal());
        StepVerifier.create(
                        history.logs(project, "INVOICE", blue.getId(), page(0, 12))
                                .contextWrite(otherTenant))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 404))
                .verify(WAIT);
        for (var type : List.of("RECEIVABLE", "TEMP_PAYMENT")) {
            var wrongCompany = candidate(type, "wrong-company", null, null);
            assertEquals(
                    0,
                    candidates
                            .query(project, "INVOICE", wrongCompany, NOW)
                            .contextWrite(fixture.allowed())
                            .block(WAIT)
                            .getTotal());
            var wrongCustomer = candidate(type, null, "another-customer", null);
            assertEquals(
                    0,
                    candidates
                            .query(project, "INVOICE", wrongCustomer, NOW)
                            .contextWrite(fixture.allowed())
                            .block(WAIT)
                            .getTotal());
        }
        var sourcePage =
                candidates
                        .query(
                                project,
                                "INVOICE",
                                new DocumentCandidateService.Input(
                                        "RECEIVABLE", null, null, null, null, -1, 1000),
                                NOW)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(0, sourcePage.getPageIndex());
        assertEquals(200, sourcePage.getPageSize());
        assertEquals(1, sourcePage.getTotal());
        fixture.rejects(candidates.query(project, "INVOICE", null, NOW), 400);
        fixture.rejects(
                candidates.query(
                        project,
                        "INVOICE",
                        candidate("RECEIVABLE", null, null, Collections.nCopies(1001, "room")),
                        NOW),
                400);
        fixture.rejects(
                candidates.query(
                        "forbidden", "INVOICE", candidate("RECEIVABLE", null, null, null), NOW),
                403);
        StepVerifier.create(
                        candidates.query(
                                project, "INVOICE", candidate("RECEIVABLE", null, null, null), NOW))
                .expectError()
                .verify(WAIT);
    }

    @Test
    void changedOrBrokenSourcesNeverBecomeSuccessfulEmptyPagesOrRewriteHistory() {
        var bill = documentBill();
        var blue =
                issue(submission(input(List.of(item("RECEIVABLE", bill.getId(), "10")))))
                        .document();
        bills.createUpdate()
                .set("snapshotJson", "{}")
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        fixture.rejects(
                candidates.query(
                        project, "INVOICE", candidate("RECEIVABLE", null, null, null), NOW),
                409);
        assertEquals(
                1,
                history.query(project, "INVOICE", page(0, 12))
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .getTotal());
        assertEquals(
                "原物业服务",
                issue.detail(project, "INVOICE", blue.getId())
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .lines()
                        .get(0)
                        .snapshot()
                        .goodsName());
        bills.createUpdate().set("deleted", true).where("id", bill.getId()).execute().block(WAIT);
        assertEquals(
                0,
                candidates
                        .query(project, "INVOICE", candidate("RECEIVABLE", null, null, null), NOW)
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .getTotal());
        assertEquals(
                1,
                history.query(project, "INVOICE", page(0, 12))
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .getTotal());
    }

    @Test
    void sourceAndHistoryHttpRoutesKeepSeparateTypesAndHideInternalSnapshots() {
        var props = new BillingJobProperties();
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web.InvoiceDocumentController(
                                        preparation,
                                        issue,
                                        reverse,
                                        history,
                                        candidates,
                                        null,
                                        props,
                                        files),
                                new org.jetlinks.pro.property.finance.web.ReceiptDocumentController(
                                        preparation,
                                        issue,
                                        reverse,
                                        history,
                                        candidates,
                                        null,
                                        props,
                                        files))
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange).contextWrite(fixture.allowed()))
                        .build();
        var bill = documentBill();
        var blue =
                issue(submission(input(List.of(item("RECEIVABLE", bill.getId(), "10")))))
                        .document();
        var path = "/property/finance/projects/" + project;
        client.post()
                .uri(path + "/invoices/_query")
                .bodyValue(page(0, 12))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1)
                .jsonPath("$.data[0].id")
                .isEqualTo(blue.getId())
                .jsonPath("$.data[0].amount")
                .isEqualTo("10.00")
                .jsonPath("$.data[0].buyerName")
                .isEqualTo(blue.getBuyerName())
                .jsonPath("$.data[0].buyerJson")
                .doesNotExist()
                .jsonPath("$.data[0].ruleJson")
                .doesNotExist()
                .jsonPath("$.data[0].requestFingerprint")
                .doesNotExist();
        client.get()
                .uri(path + "/invoices/" + blue.getId() + "/pdf")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType("application/pdf")
                .expectBody()
                .consumeWith(result -> assertTrue(result.getResponseBody().length > 100));
        client.post()
                .uri(path + "/invoices/_export/excel")
                .bodyValue(page(0, 12))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        client.post()
                .uri(path + "/receipts/_query")
                .bodyValue(page(0, 12))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(0);
        client.post()
                .uri(path + "/invoices/" + blue.getId() + "/logs/_query")
                .bodyValue(page(0, 12))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1)
                .jsonPath("$.data[0].action")
                .isEqualTo("ISSUE")
                .jsonPath("$.data[0].requestFingerprint")
                .doesNotExist();
        client.post()
                .uri(path + "/receipts/" + blue.getId() + "/logs/_query")
                .bodyValue(page(0, 12))
                .exchange()
                .expectStatus()
                .isNotFound();
        for (var route : List.of("invoices", "receipts")) {
            client.post()
                    .uri(path + "/" + route + "/sources/_query")
                    .bodyValue(candidate("RECEIVABLE", "company", "customer", List.of("room")))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.total")
                    .isEqualTo(1)
                    .jsonPath("$.data[0].sourceId")
                    .isEqualTo(bill.getId())
                    .jsonPath("$.data[0].availableAmount")
                    .isEqualTo("90.00")
                    .jsonPath("$.data[0].taxRate")
                    .isEqualTo("0.06")
                    .jsonPath("$.data[0].snapshotJson")
                    .doesNotExist();
        }
        client.post()
                .uri(path + "/invoices/sources/_query")
                .bodyValue(candidate("RECEIVED", null, null, null))
                .exchange()
                .expectStatus()
                .isBadRequest();
        assertEquals(
                new BigDecimal("10.00"),
                bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
    }

    /** 使用正式创建人 holder，空间资产来自资产管理器，不依赖收费库存在 SaaS 空间表。 */
    reactor.util.context.Context creatorScope(Set<String> owned) {
        var manager = org.mockito.Mockito.mock(AssetManager.class);
        org.mockito.Mockito.when(
                        manager.getAssets(
                                org.mockito.ArgumentMatchers.any(AssetType.class),
                                org.mockito.ArgumentMatchers.anyCollection()))
                .thenAnswer(
                        call ->
                                reactor.core.publisher.Flux.fromIterable(
                                                (Collection<?>) call.getArgument(1))
                                        .map(
                                                id ->
                                                        new Asset() {
                                                            public String getId() {
                                                                return id.toString();
                                                            }

                                                            public String getName() {
                                                                return id.toString();
                                                            }

                                                            public AssetType getType() {
                                                                return AssetType.of("space");
                                                            }

                                                            public Map<String, Object>
                                                                    getOptions() {
                                                                return Map.of(
                                                                        Asset.OPTION_CREATOR_ID,
                                                                        owned.contains(
                                                                                        id
                                                                                                .toString())
                                                                                ? "space-creator"
                                                                                : "someone-else");
                                                            }
                                                        }));
        var user = new org.hswebframework.web.authorization.simple.SimpleUser();
        user.setId("space-creator");
        var holder = new org.jetlinks.pro.assets.impl.CreatorAssetsHolder(user, manager);
        return fixture.allowed().put(AssetsHolder.class, holder);
    }

    @Test
    void creatorPermissionFiltersWholeReceiptBeforePagingWithoutSaasTables() {
        var a = documentBill();
        var b = documentBill();
        bills.createUpdate()
                .set("spaceId", "secret-room")
                .where("id", b.getId())
                .execute()
                .block(WAIT);
        var first = input(List.of(item("RECEIVABLE", a.getId(), "10")));
        var receipt =
                issue(
                                submission(
                                        new DocumentIssueInput(
                                                "RECEIPT",
                                                "company",
                                                first.buyer(),
                                                null,
                                                null,
                                                first.items())))
                        .document();
        var mixed =
                input(
                        List.of(
                                item("RECEIVABLE", a.getId(), "10"),
                                item("RECEIVABLE", b.getId(), "10")));
        issue(
                submission(
                        new DocumentIssueInput(
                                "RECEIPT", "company", mixed.buyer(), null, null, mixed.items())));
        var q = page(0, 1);
        var result =
                history.query(project, "RECEIPT", q)
                        .contextWrite(creatorScope(Set.of("room")))
                        .block(WAIT);
        assertEquals(1, result.getTotal());
        assertEquals(receipt.getId(), result.getData().get(0).getId());
        assertEquals(
                0,
                history.query(project, "RECEIPT", q)
                        .contextWrite(creatorScope(Set.of()))
                        .block(WAIT)
                        .getTotal());
        assertEquals(
                2,
                history.query(project, "RECEIPT", q)
                        .contextWrite(creatorScope(Set.of("room", "secret-room")))
                        .block(WAIT)
                        .getTotal());
    }

    reactor.util.context.Context authorized(
            CarryForwardAuthorityTest authority, Set<String> rooms, long permission) {
        database.getMetadata()
                .addFeature(new org.jetlinks.pro.assets.impl.terms.DimensionAssetsTermBuilder());
        var reader = "document-reader-" + UUID.randomUUID();
        authority.rights.put(reader, rooms);
        authority.masks.put(reader, permission);
        var auth = authority.auth(reader);
        var holder = AssetsHolder.fromAuth(auth).block(WAIT);
        for (var room : rooms) {
            var binding = new DimensionAssetBindingEntity();
            binding.setAssetId(room);
            binding.setAssetType("space");
            binding.setTargetId(reader);
            binding.setTargetType("test");
            binding.setPermission(permission);
            binding.setUpdateTime(System.currentTimeMillis());
            binding.generateTargetKey();
            binding.setId(binding.generateId());
            assetBindings.insert(binding).block(WAIT);
        }
        return fixture.allowed()
                .put(org.hswebframework.web.authorization.Authentication.class, auth)
                .put(AssetsHolder.class, holder);
    }

    @Configuration
    @org.hswebframework.web.crud.annotation.EnableEasyormRepository(
            "org.jetlinks.pro.assets.impl.entity")
    @Import({
        DocumentIssuePersistenceTest.Config.class,
        DocumentQueryScope.class,
        DocumentRecordService.class,
        DocumentFileService.class,
        DocumentCandidateService.class
    })
    static class Config {}
}
