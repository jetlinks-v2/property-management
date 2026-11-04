package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.document.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** 预览只读来源和规则，税额由真实快照计算；配置更新与审计同事务。 */
@SpringBootTest(
        classes = DocumentPreparationPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DocumentPreparationPersistenceTest extends DocumentSourcePersistenceTest {
    static final LocalDateTime NOW = LocalDateTime.of(2024, 2, 15, 14, 0);
    @Autowired DocumentPreparationService preparation;
    @Autowired DocumentRuleService ruleService;
    @Autowired ReactiveRepository<DocumentRuleEntity, String> ruleRows;
    @Autowired ReactiveRepository<DocumentRuleLogEntity, String> ruleLogs;

    DocumentIssueInput input(List<DocumentIssueInput.Item> items) {
        return new DocumentIssueInput(
                "INVOICE",
                "company",
                new DocumentIssueInput.Buyer(
                        "购方企业", "ABC123", "19123288779", "a@b.cn", "重庆", "银行", "6222", null),
                "1",
                "开票说明",
                items);
    }

    DocumentIssueInput.Item item(String type, String id, String amount) {
        return new DocumentIssueInput.Item(type, id, amount, "FULL", "0", null);
    }

    DocumentPreparationService.Preview preview(DocumentIssueInput input) {
        return preparation.preview(project, input, NOW).contextWrite(fixture.allowed()).block(WAIT);
    }

    @Test
    void defaultRuleAndPreviewFreezeTaxAmountsWithoutOccupationOrArchiveLookup() {
        var row = documentBill();
        var defaultRule = ruleService.get(project).contextWrite(fixture.allowed()).block(WAIT);
        assertEquals("NATURAL_YEAR", defaultRule.timeLimitType());
        assertEquals(0, defaultRule.maxReopenTimes());
        assertNull(defaultRule.version());
        var view = preview(input(List.of(item("RECEIVABLE", row.getId(), "50"))));
        assertEquals("50.00", view.amount());
        assertEquals("2.83", view.taxAmount());
        assertEquals("47.17", view.netAmount());
        assertEquals("原开票公司", view.companyName());
        assertEquals("BEFORE_PAYMENT", view.issueScene());
        assertEquals("0.06", view.lines().get(0).taxRate());
        assertEquals("2.830189", view.lines().get(0).outputTax());
        assertEquals(
                new BigDecimal("0.00"),
                bills.findById(row.getId()).block(WAIT).getInvoiceOccupiedAmount());
        assertEquals(0, ruleRows.createQuery().where("id", project).count().block(WAIT));
    }

    @Test
    void inputOrderAndAliasAmountsArePreservedWhileLocksStayCanonical() {
        var a = documentBill();
        var b = documentBill();
        var sorted = new ArrayList<>(List.of(a, b));
        sorted.sort(Comparator.comparing(ReceivableEntity::getId).reversed());
        var input =
                input(
                        List.of(
                                item("RECEIVABLE", sorted.get(0).getId(), "10"),
                                item("RECEIVABLE", sorted.get(1).getId(), "20")));
        var view = preview(input);
        assertEquals(sorted.get(0).getId(), view.lines().get(0).sourceId());
        assertEquals("10.00", view.lines().get(0).amount());
        assertEquals(sorted.get(1).getId(), view.lines().get(1).sourceId());
        assertEquals("20.00", view.lines().get(1).amount());
        assertEquals("30.00", view.amount());
        fixture.rejects(
                preparation.preview(
                        project, input(List.of(item("RECEIVABLE", a.getId(), "101"))), NOW),
                409);
    }

    @Test
    void differentCustomerCompanyAndDuplicateAliasesAreRejected() {
        var a = documentBill();
        var b = documentBill();
        bills.createUpdate()
                .set("customerId", "other-customer")
                .where("id", b.getId())
                .execute()
                .block(WAIT);
        fixture.rejects(
                preparation.preview(
                        project,
                        input(
                                List.of(
                                        item("RECEIVABLE", a.getId(), "10"),
                                        item("RECEIVABLE", b.getId(), "10"))),
                        NOW),
                409);
        var i = input(List.of(item("RECEIVABLE", a.getId(), "10")));
        i =
                new DocumentIssueInput(
                        i.documentType(),
                        "wrong-company",
                        i.buyer(),
                        i.invoiceType(),
                        i.remark(),
                        i.items());
        fixture.rejects(preparation.preview(project, i, NOW), 409);
        var collected =
                cashier.collect(project, fixture.submission(fixture.input(a, "10.00")), DATE)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        fixture.rejects(
                preparation.preview(
                        project,
                        input(
                                List.of(
                                        item("RECEIVABLE", a.getId(), "10"),
                                        item("RECEIVED", collected.lines().get(0).getId(), "10"))),
                        NOW),
                409);
    }

    @Test
    void ruleAndSourceVersionsChangePreviewTokenAndCustomDaysExpireFromPayment() {
        var row = documentBill();
        var collected =
                cashier.collect(project, fixture.submission(fixture.input(row, "10.00")), DATE)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        var input = input(List.of(item("RECEIVED", collected.lines().get(0).getId(), "10")));
        var initial = preview(input);
        var rule =
                ruleService
                        .save(
                                project,
                                new DocumentRuleService.Input(null, "CUSTOM_DAYS", 3, 2, "按项目设置时效"))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertNotEquals(initial.token(), preview(input).token());
        assertEquals("MIXED", preview(input).issueScene());
        fixture.rejects(preparation.preview(project, input, NOW.plusDays(4)), 409);
        var before = preview(input).token();
        bills.createUpdate().set("version", 99L).where("id", row.getId()).execute().block(WAIT);
        assertNotEquals(before, preview(input).token());
        assertEquals(0L, rule.version());
    }

    @Test
    void ruleConcurrentUpdatesRejectLostUpdateAndKeepSuccessfulAudit() {
        var first =
                ruleService
                        .save(
                                project,
                                new DocumentRuleService.Input(
                                        null, "NATURAL_YEAR", null, 0, "初始规则"))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        var results =
                Mono.zip(
                                ruleService
                                        .save(
                                                project,
                                                new DocumentRuleService.Input(
                                                        first.version(),
                                                        "CUSTOM_DAYS",
                                                        10,
                                                        2,
                                                        "调整一"))
                                        .contextWrite(fixture.allowed())
                                        .materialize(),
                                ruleService
                                        .save(
                                                project,
                                                new DocumentRuleService.Input(
                                                        first.version(),
                                                        "CUSTOM_DAYS",
                                                        20,
                                                        3,
                                                        "调整二"))
                                        .contextWrite(fixture.allowed())
                                        .materialize())
                        .block(WAIT);
        assertNotEquals(results.getT1().isOnNext(), results.getT2().isOnNext());
        assertTrue(
                TaxItemPersistenceTest.status(
                        results.getT1().isOnError()
                                ? results.getT1().getThrowable()
                                : results.getT2().getThrowable(),
                        409));
        assertEquals(2, ruleLogs.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                1L, ruleService.get(project).contextWrite(fixture.allowed()).block(WAIT).version());
    }

    @Test
    void ruleAuditFailureRollsBackAndHttpCannotDeleteConfiguration() throws Exception {
        var constraint = "document_rule_" + UUID.randomUUID().toString().replace("-", "");
        var sql =
                "ALTER TABLE pm_fin_document_rule_log ADD CONSTRAINT "
                        + constraint
                        + " CHECK (project_id <> '"
                        + project
                        + "')";
        assertEquals(
                0,
                HouseSettingPersistenceTest.DB
                        .execInContainer("psql", "-U", "postgres", "-d", "setting_test", "-c", sql)
                        .getExitCode());
        try {
            StepVerifier.create(
                            ruleService
                                    .save(
                                            project,
                                            new DocumentRuleService.Input(
                                                    null, "CUSTOM_DAYS", 2, 1, "审计不可缺失"))
                                    .contextWrite(fixture.allowed()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(0, ruleRows.createQuery().where("id", project).count().block(WAIT));
        } finally {
            HouseSettingPersistenceTest.DB.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "setting_test",
                    "-c",
                    "ALTER TABLE pm_fin_document_rule_log DROP CONSTRAINT " + constraint);
        }
        var controller =
                new org.jetlinks.pro.property.finance.web.DocumentRuleController(ruleService);
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                controller)
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange).contextWrite(fixture.allowed()))
                        .build();
        var path = "/property/finance/projects/" + project + "/document-rule";
        client.get()
                .uri(path)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.timeLimitType")
                .isEqualTo("NATURAL_YEAR");
        client.delete().uri(path).exchange().expectStatus().is4xxClientError();
        client.put()
                .uri(path)
                .bodyValue(new DocumentRuleService.Input(null, "CUSTOM_DAYS", 0, 1, "无效规则"))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Configuration
    @Import({
        DocumentSourcePersistenceTest.Config.class,
        DocumentRuleService.class,
        DocumentPreparationService.class,
        DocumentInputRules.class
    })
    @ImportAutoConfiguration(JacksonAutoConfiguration.class)
    static class Config {}

    @Test
    void previewHttpSeparatesInvoiceReceiptActionsAndNeverAcceptsSourceTaxOverrides() {
        var bill = documentBill();
        var invoiceController =
                new org.jetlinks.pro.property.finance.web.InvoiceDocumentController(
                        preparation, null, null, null, null, null, new BillingJobProperties());
        var receiptController =
                new org.jetlinks.pro.property.finance.web.ReceiptDocumentController(
                        preparation, null, null, null, null, null, new BillingJobProperties());
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                invoiceController, receiptController)
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange).contextWrite(fixture.allowed()))
                        .build();
        var path = "/property/finance/projects/" + project;
        var input = input(List.of(item("RECEIVABLE", bill.getId(), "50")));
        var tampered =
                Map.of(
                        "documentType",
                        "INVOICE",
                        "companyId",
                        "company",
                        "buyer",
                        input.buyer(),
                        "invoiceType",
                        "1",
                        "taxAmount",
                        "0",
                        "items",
                        List.of(
                                Map.of(
                                        "sourceType",
                                        "RECEIVABLE",
                                        "sourceId",
                                        bill.getId(),
                                        "amount",
                                        "50",
                                        "taxRate",
                                        "0",
                                        "taxAmount",
                                        "0",
                                        "goodsCode",
                                        "tampered")));
        client.post()
                .uri(path + "/invoices/_preview")
                .bodyValue(tampered)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.amount")
                .isEqualTo("50.00")
                .jsonPath("$.taxAmount")
                .isEqualTo("2.83")
                .jsonPath("$.lines[0].taxRate")
                .isEqualTo("0.06")
                .jsonPath("$.lines[0].goodsCode")
                .isEqualTo("3040801")
                .jsonPath("$.lines[0].snapshotJson")
                .doesNotExist();
        client.post()
                .uri(path + "/receipts/_preview")
                .bodyValue(input)
                .exchange()
                .expectStatus()
                .isBadRequest();
        var receipt =
                new DocumentIssueInput(
                        "RECEIPT", "company", input.buyer(), null, "只读预览", input.items());
        client.post()
                .uri(path + "/receipts/_preview")
                .bodyValue(receipt)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.documentType")
                .isEqualTo("RECEIPT");
        client.delete().uri(path + "/invoices/any").exchange().expectStatus().is4xxClientError();
        assertEquals(
                new BigDecimal("0.00"),
                bills.findById(bill.getId()).block(WAIT).getInvoiceOccupiedAmount());
    }

    @Test
    void differentialVoucherAndReadonlyReceiptRetainCompleteFrozenTax() {
        var row = documentBill();
        var voucher =
                new DocumentIssueInput.DifferenceVoucher(
                        "10",
                        null,
                        "E2024001",
                        null,
                        null,
                        "2024-02-15",
                        "10.00",
                        "已验证凭证",
                        "uploaded-file",
                        "扣除凭证.pdf");
        var input =
                input(
                        List.of(
                                new DocumentIssueInput.Item(
                                        "RECEIVABLE",
                                        row.getId(),
                                        "50",
                                        "DIFFERENTIAL",
                                        "10",
                                        voucher)));
        var view = preview(input);
        assertEquals("50.00", view.amount());
        assertEquals("2.26", view.taxAmount());
        assertEquals("47.74", view.netAmount());
        assertEquals("2.264151", view.lines().get(0).outputTax());
        assertEquals("uploaded-file", view.lines().get(0).voucher().attachmentFileId());
        assertEquals("10.00", view.lines().get(0).differenceAmount());
        assertEquals("原物业服务", view.lines().get(0).goodsName());
        assertEquals(
                view.token(),
                preparation
                        .preview(project, input, NOW.plusMinutes(5))
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .token());
        assertNotEquals(
                view.token(),
                preparation
                        .preview(project, input, NOW.plusDays(1))
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .token());
        var receipt =
                new DocumentIssueInput(
                        "RECEIPT",
                        "company",
                        input.buyer(),
                        null,
                        "收据",
                        List.of(item("RECEIVABLE", row.getId(), "20")));
        assertEquals("RECEIPT", preview(receipt).documentType());
        assertEquals(
                new BigDecimal("0.00"),
                bills.findById(row.getId()).block(WAIT).getInvoiceOccupiedAmount());
    }

    @Test
    void ruleCanReturnToNaturalYearAndUnlimitedReopenWithoutStaleFields() {
        var first =
                ruleService
                        .save(
                                project,
                                new DocumentRuleService.Input(null, "CUSTOM_DAYS", 30, 2, "自定义时效"))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        var next =
                ruleService
                        .save(
                                project,
                                new DocumentRuleService.Input(
                                        first.version(), "NATURAL_YEAR", null, null, "恢复自然年与不限次数"))
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertNull(next.limitDays());
        assertNull(next.maxReopenTimes());
        var persisted = ruleRows.findById(project).block(WAIT);
        assertNull(persisted.getLimitDays());
        assertNull(persisted.getMaxReopenTimes());
        StepVerifier.create(ruleService.get(project)).expectError().verify(WAIT);
        fixture.rejects(ruleService.get("forbidden"), 403);
        fixture.rejects(
                ruleService.save(
                        "forbidden",
                        new DocumentRuleService.Input(null, "NATURAL_YEAR", null, 0, "伪造项目")),
                403);
    }
}
