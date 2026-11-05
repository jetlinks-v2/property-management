package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.jetlinks.pro.property.finance.document.DocumentIssueInput;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.web.ReceiptPrintController;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.test.StepVerifier;

import java.nio.file.*;
import java.util.*;

/** 真实PG票据快照到真实PDF：不读实时档案，不重复占用；打印仍逐空间授权。 */
@SpringBootTest(
        classes = ReceiptPrintPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReceiptPrintPersistenceTest extends DocumentQueryPersistenceTest {
    @Autowired ReceiptPrintService printing;
    @Autowired org.jetlinks.pro.property.finance.document.ReceiptPdfRenderer renderer;

    DocumentStore.Detail receipt() {
        var original = input(List.of(item("RECEIVABLE", documentBill().getId(), "100")));
        return issue(
                submission(
                        new DocumentIssueInput(
                                "RECEIPT",
                                original.companyId(),
                                original.buyer(),
                                null,
                                "收据备注 <script>alert('x')</script>",
                                original.items())));
    }

    byte[] pdf(String id) {
        return printing.pdf(project, id).contextWrite(fixture.allowed()).block(WAIT);
    }

    static Path artifact(String name) throws java.io.IOException {
        return Files.createDirectories(Path.of("target/receipt-print-artifacts")).resolve(name);
    }

    static String text(byte[] bytes) throws Exception {
        try (var document = PDDocument.load(bytes)) {
            // 中文字体的同形字映射可能返回康熙部首，按Unicode兼容规范化后比较语义文字。
            return java.text.Normalizer.normalize(
                    new PDFTextStripper().getText(document), java.text.Normalizer.Form.NFKC);
        }
    }

    @Test
    void printUsesFrozenFactsAndDoesNotChangeAmountsOccupationOrNumber() throws Exception {
        var original = receipt();
        var d = original.document();
        companyRows
                .createUpdate()
                .set("companyName", "已更名实时单位")
                .where("id", "company")
                .execute()
                .block(WAIT);
        bills.createUpdate()
                .set("deleted", true)
                .where("id", original.lines().get(0).line().getSourceId())
                .execute()
                .block(WAIT);
        var beforeOps = operations.createQuery().where("projectId", project).count().block(WAIT);
        var beforeOccupation =
                occupationRows.createQuery().where("projectId", project).count().block(WAIT);
        var first = pdf(d.getId());
        String text = text(first);
        Files.write(artifact("receipt-print-sample.pdf"), first);
        assertTrue(text.contains("电子收据"), text);
        assertTrue(text.contains(d.getDocumentNumber()));
        assertTrue(d.getDocumentNumber().matches("SJ[0-9]{17}[A-F0-9]{16}"));
        assertTrue(text.contains("原开票公司"));
        assertFalse(text.contains("已更名实时单位"));
        assertTrue(text.contains(original.buyer().name()));
        assertTrue(text.contains(original.lines().get(0).snapshot().subjectName()));
        assertTrue(text.contains("100.00"));
        assertTrue(text.contains("壹佰元整"));
        assertTrue(text.contains("收据金额"));
        assertFalse(text.contains("今收到"));
        assertFalse(text.contains("实收金额"));
        assertTrue(text.contains("<script>"));
        assertTrue(text(pdf(d.getId())).contains(d.getDocumentNumber()));
        assertEquals(
                beforeOps,
                operations.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                beforeOccupation,
                occupationRows.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(d.getVersion(), documentRows.findById(d.getId()).block(WAIT).getVersion());
        try (var parsed = PDDocument.load(first)) {
            assertEquals(1, parsed.getNumberOfPages());
            assertTrue(
                    parsed.getPage(0).getMediaBox().getWidth()
                            > parsed.getPage(0).getMediaBox().getHeight());
            javax.imageio.ImageIO.write(
                    new org.apache.pdfbox.rendering.PDFRenderer(parsed).renderImageWithDPI(0, 130),
                    "png",
                    artifact("receipt-print-sample.png").toFile());
        }
        Files.write(artifact("receipt-print-sample.pdf"), first);
    }

    @Test
    void voidedReceiptKeepsOriginalNumberAndVisibleVoidMark() throws Exception {
        var d = receipt().document();
        reverse(d, reversal(d, "误开收据"));
        documentRows
                .createUpdate()
                .set("modifierName", "另一位作废员")
                .where("id", d.getId())
                .execute()
                .block(WAIT);
        var printed = text(pdf(d.getId()));
        assertTrue(printed.contains("已作废"));
        assertTrue(printed.contains(d.getDocumentNumber()));
        assertTrue(printed.contains("财务测试"));
        assertFalse(printed.contains("另一位作废员"));
        assertEquals("VOIDED", documentRows.findById(d.getId()).block(WAIT).getStatus());
    }

    @Test
    void mixedReceiptDoesNotClaimTheWholeAmountWasReceivedAndPaidReceiptDoes() throws Exception {
        var d = receipt().document();
        for (var scene : List.of("MIXED", "AFTER_PAYMENT")) {
            documentRows
                    .createUpdate()
                    .set("issueScene", scene)
                    .where("id", d.getId())
                    .execute()
                    .block(WAIT);
            String printed = text(pdf(d.getId()));
            assertEquals("AFTER_PAYMENT".equals(scene), printed.contains("今收到 / 实收金额"), printed);
        }
    }

    @Test
    void noScopeWrongTenantProjectOrInvoiceCanObtainAReceiptPdf() {
        var r = receipt().document();
        fixture.rejects(printing.pdf("forbidden", r.getId()), 403);
        StepVerifier.create(printing.pdf(project, r.getId())).expectError().verify(WAIT);
        StepVerifier.create(
                        printing.pdf(project, r.getId())
                                .contextWrite(
                                        fixture.allowed()
                                                .put(
                                                        org.jetlinks.pro.tenant.holder.TenantHolder
                                                                .CONTEXT_KEY,
                                                        "other")))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 404))
                .verify(WAIT);
        var invoice =
                issue(submission(input(List.of(item("RECEIVABLE", documentBill().getId(), "1")))))
                        .document();
        fixture.rejects(printing.pdf(project, invoice.getId()), 404);
        var authority = new CarryForwardAuthorityTest();
        authority.holders();
        try {
            var restricted =
                    authorized(
                            authority,
                            Set.of("other-room"),
                            org.jetlinks.pro.assets.crud.CrudAssetPermission.all_mask);
            StepVerifier.create(printing.pdf(project, r.getId()).contextWrite(restricted))
                    .expectError()
                    .verify(WAIT);
        } finally {
            authority.close();
        }
    }

    @Test
    void httpReturnsPrivateInlinePdfAndDeclaresIndependentPrintPermission() throws Exception {
        var r = receipt().document();
        var client =
                WebTestClient.bindToController(new ReceiptPrintController(printing))
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange).contextWrite(fixture.allowed()))
                        .build();
        var result =
                client.get()
                        .uri(
                                "/property/finance/projects/"
                                        + project
                                        + "/receipts/"
                                        + r.getId()
                                        + "/print.pdf")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectHeader()
                        .contentType("application/pdf")
                        .expectHeader()
                        .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                        .expectHeader()
                        .valueMatches(HttpHeaders.CONTENT_DISPOSITION, "inline;.*")
                        .expectBody()
                        .returnResult()
                        .getResponseBody();
        assertTrue(text(result).contains(r.getDocumentNumber()));
        var action =
                ReceiptPrintController.class
                        .getMethod("print", String.class, String.class)
                        .getAnnotation(ResourceAction.class);
        assertEquals("print", action.id());
    }

    @Test
    void longReceiptRepeatsNumberAndVoidStatusOnEveryPageWithoutTruncatingRows() throws Exception {
        var source = receipt();
        source.document().setStatus("VOIDED");
        source.document().setAmount(new java.math.BigDecimal("10000.00"));
        var lines = new ArrayList<DocumentStore.Line>();
        for (int i = 0; i < 100; i++) lines.add(source.lines().get(0));
        var detail =
                new DocumentStore.Detail(source.document(), source.buyer(), source.rule(), lines);
        byte[] bytes = renderer.render(detail, Locale.SIMPLIFIED_CHINESE, "财务测试");
        try (var parsed = PDDocument.load(bytes)) {
            assertTrue(parsed.getNumberOfPages() > 1);
            for (int page = 1; page <= parsed.getNumberOfPages(); page++) {
                var stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                var content = stripper.getText(parsed);
                assertTrue(content.contains("已作废"), "page " + page);
                assertTrue(content.contains(source.document().getDocumentNumber()), "page " + page);
            }
            assertTrue(new PDFTextStripper().getText(parsed).contains("10000.00"));
            var visual = new org.apache.pdfbox.rendering.PDFRenderer(parsed);
            javax.imageio.ImageIO.write(
                    visual.renderImageWithDPI(0, 130),
                    "png",
                    artifact("receipt-print-multipage-first.png").toFile());
            javax.imageio.ImageIO.write(
                    visual.renderImageWithDPI(parsed.getNumberOfPages() - 1, 130),
                    "png",
                    artifact("receipt-print-multipage-last.png").toFile());
        }
        Files.write(artifact("receipt-print-multipage.pdf"), bytes);
    }

    @Test
    void corruptSnapshotAmountFailsVisiblyInsteadOfPrintingAFalseReceipt() {
        var source = receipt();
        source.document().setAmount(new java.math.BigDecimal("99.99"));
        assertThrows(
                RuntimeException.class,
                () -> renderer.render(source, Locale.SIMPLIFIED_CHINESE, "财务测试"));
    }

    @Configuration
    @Import({
        DocumentQueryPersistenceTest.Config.class,
        ReceiptPrintService.class,
        org.jetlinks.pro.property.finance.document.ReceiptPdfRenderer.class
    })
    static class Config {}
}
