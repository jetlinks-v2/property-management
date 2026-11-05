package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.document.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.*;

/** 原存入和票据在真实PG中共享额度，历史税快照与账户当前标准无关。 */
@SpringBootTest(
        classes = FundDocumentPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FundDocumentPersistenceTest extends DocumentIssuePersistenceTest {
    @Autowired FundsService funds;
    @Autowired ReactiveRepository<FundAccountEntity, String> accounts;
    @Autowired ReactiveRepository<FundEntryEntity, String> fundEntries;
    @Autowired ReactiveRepository<FundCreditEntity, String> credits;
    @Autowired DocumentCandidateService candidates;

    @BeforeEach
    void fundingCompany() {
        var company = companyRows.findById("company").block(WAIT);
        when(configuration.balanceCompany(anyString(), anyString(), anyBoolean()))
                .thenReturn(Mono.just(company));
    }

    FundsInput topupInput(String type, String amount) {
        var value = new FundsInput();
        value.setAction("TOPUP");
        value.setType(type);
        value.setAmount(new BigDecimal(amount));
        value.setCompanyId("company");
        value.setSpaceId("room");
        value.setRelationshipId("link");
        value.setStandardId(setting.standardId);
        value.setMethod("1");
        value.setOccurredAt("2024-02-15T12:00:00");
        value.setPayerName("龚超");
        value.setReason("柜台存入");
        return value;
    }

    FundsService.Result executeFund(FundsInput value) {
        var preview =
                funds.preview(project, value, DATE).contextWrite(fixture.allowed()).block(WAIT);
        return funds.execute(
                        project,
                        new FundsService.Submission(
                                UUID.randomUUID().toString(), preview.token(), value),
                        DATE)
                .contextWrite(fixture.allowed())
                .block(WAIT);
    }

    DocumentSourceService.Source original(FundEntryEntity entry) {
        return documents
                .load(
                        project,
                        List.of(new DocumentSourceService.Reference("FUND_TOPUP", entry.getId())),
                        false)
                .contextWrite(fixture.allowed())
                .block(WAIT)
                .get(0);
    }

    @Test
    void originalAdvanceAndDepositUseFrozenTaxAfterCurrentStandardAndCustomerChange() {
        var advance = executeFund(topupInput("ADVANCE", "100.01"));
        var deposit = executeFund(topupInput("DEPOSIT", "20.00"));
        var frozenEntry = fundEntries.findById(advance.entry().getId()).block(WAIT);
        assertNotNull(frozenEntry.getDocumentSnapshotJson());
        var frozen = com.alibaba.fastjson.JSON.parseObject(frozenEntry.getDocumentSnapshotJson());
        assertEquals("ADVANCE", frozen.getString("fundType"));
        var frozenStandard = frozen.getJSONObject("standard");
        assertEquals("tax", frozenStandard.getString("taxItemId"));
        assertNotNull(frozenStandard.getString("taxSnapshotJson"));
        assertEquals(
                "0.06",
                com.alibaba
                        .fastjson
                        .JSON
                        .parseObject(frozenStandard.getString("taxSnapshotJson"))
                        .getString("taxRate"));
        assertEquals("龚超", original(advance.entry()).customerName());
        standards
                .createUpdate()
                .set("taxSnapshotJson", "{}")
                .set("deleted", true)
                .where("id", setting.standardId)
                .execute()
                .block(WAIT);
        clearInvocations(archive);
        var first = original(advance.entry());
        var second = original(deposit.entry());
        assertEquals("FUND_TOPUP", first.canonical().type());
        assertEquals("0.06", first.tax().taxRate());
        assertEquals("原开票公司", first.tax().companyName());
        assertEquals(new BigDecimal("100.01"), first.availableAmount());
        assertEquals(new BigDecimal("20.00"), second.paidAmount());
        assertEquals("面积月费", first.standardName());
        assertEquals("龚超", first.customerName());
        verifyNoInteractions(archive);
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void issueAndRedRestoreBothCountersWithoutChangingCashOrBalance() {
        var topup = executeFund(topupInput("ADVANCE", "100.01"));
        var req = submission(input(List.of(item("FUND_TOPUP", topup.entry().getId(), "80.00"))));
        var blue = issue(req);
        var again = issue(req);
        assertEquals(blue.document().getId(), again.document().getId());
        assertEquals("AFTER_PAYMENT", blue.document().getIssueScene());
        assertEquals(
                new BigDecimal("80.00"),
                credits.findById(topup.entry().getId()).block(WAIT).getInvoiceOccupiedAmount());
        var account = accounts.findById(topup.account().getId()).block(WAIT);
        assertEquals(new BigDecimal("80.00"), account.getInvoiceOccupiedAmount());
        assertEquals(new BigDecimal("100.01"), account.getBalance());
        assertEquals(new BigDecimal("20.01"), original(topup.entry()).availableAmount());
        var refund = topupInput("ADVANCE", "1.00");
        refund.setAction("REFUND");
        refund.setAccountId(account.getId());
        refund.setVersion(account.getVersion());
        fixture.rejects(funds.preview(project, refund, DATE), 409);
        var red = reverse(blue.document(), reversal(blue.document(), "重核开票资料"));
        assertEquals(new BigDecimal("-80.00"), red.document().getAmount());
        assertEquals(
                new BigDecimal("0.00"),
                credits.findById(topup.entry().getId()).block(WAIT).getInvoiceOccupiedAmount());
        account = accounts.findById(account.getId()).block(WAIT);
        assertEquals(new BigDecimal("0.00"), account.getInvoiceOccupiedAmount());
        refund.setVersion(account.getVersion());
        executeFund(refund);
        assertEquals(
                new BigDecimal("99.01"),
                accounts.findById(account.getId()).block(WAIT).getBalance());
        assertEquals(2, fundEntries.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void twoOriginalsOfSameAccountShareOneAtomicAccountCounter() {
        var a = executeFund(topupInput("ADVANCE", "40.00"));
        var b = executeFund(topupInput("ADVANCE", "60.00"));
        var blue =
                issue(
                        submission(
                                input(
                                        List.of(
                                                item("FUND_TOPUP", a.entry().getId(), "40.00"),
                                                item("FUND_TOPUP", b.entry().getId(), "60.00")))));
        assertEquals(
                new BigDecimal("100.00"),
                accounts.findById(a.account().getId()).block(WAIT).getInvoiceOccupiedAmount());
        reverse(blue.document(), reversal(blue.document(), "整张红冲"));
        assertEquals(
                new BigDecimal("0.00"),
                accounts.findById(a.account().getId()).block(WAIT).getInvoiceOccupiedAmount());
        assertEquals(new BigDecimal("40.00"), original(a.entry()).availableAmount());
        assertEquals(new BigDecimal("60.00"), original(b.entry()).availableAmount());
    }

    @Test
    void consumedOriginalAndNonTopupCannotBecomeInvoiceSource() {
        var result = executeFund(topupInput("DEPOSIT", "100.00"));
        var refund = topupInput("DEPOSIT", "1.00");
        refund.setAction("REFUND");
        refund.setAccountId(result.account().getId());
        refund.setVersion(result.account().getVersion());
        var debit = executeFund(refund);
        fixture.rejects(
                documents.load(
                        project,
                        List.of(
                                new DocumentSourceService.Reference(
                                        "FUND_TOPUP", result.entry().getId())),
                        false),
                409);
        fixture.rejects(
                documents.load(
                        project,
                        List.of(
                                new DocumentSourceService.Reference(
                                        "FUND_TOPUP", debit.entry().getId())),
                        false),
                409);
        fixture.rejects(
                documents.load(
                        "other",
                        List.of(
                                new DocumentSourceService.Reference(
                                        "FUND_TOPUP", result.entry().getId())),
                        false),
                404);
    }

    @Test
    void candidatePagingUsesFrozenCustomerAndOmitsConsumedOriginals() {
        var a = executeFund(topupInput("ADVANCE", "40.00"));
        var b = executeFund(topupInput("DEPOSIT", "60.00"));
        var customer = a.account().getCustomerId();
        var q =
                new DocumentCandidateService.Input(
                        "FUND_TOPUP", "company", customer, List.of("room"), "龚超", 0, 1);
        var page =
                candidates
                        .query(project, "INVOICE", q, NOW)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(2, page.getTotal());
        assertEquals(1, page.getData().size());
        assertTrue(Set.of("ADVANCE", "DEPOSIT").contains(page.getData().get(0).fundType()));
        assertEquals(customer, page.getData().get(0).customerId());
        var refund = topupInput("ADVANCE", "1");
        refund.setAction("REFUND");
        refund.setAccountId(a.account().getId());
        refund.setVersion(a.account().getVersion());
        executeFund(refund);
        page =
                candidates
                        .query(project, "INVOICE", q, NOW)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(1, page.getTotal());
        assertEquals(b.entry().getId(), page.getData().get(0).sourceId());
        assertEquals(
                0,
                candidates
                        .query(
                                project,
                                "INVOICE",
                                new DocumentCandidateService.Input(
                                        "FUND_TOPUP", "company", "other", null, null, 0, 12),
                                NOW)
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .getTotal());
    }

    @Test
    void missingSnapshotAndForgedCreditAreVisibleFailuresAndPermissionsRemainRequired() {
        var a = executeFund(topupInput("ADVANCE", "40.00"));
        var refs = List.of(new DocumentSourceService.Reference("FUND_TOPUP", a.entry().getId()));
        fundEntries
                .createUpdate()
                .setNull("documentSnapshotJson")
                .where("id", a.entry().getId())
                .execute()
                .block(WAIT);
        fixture.rejects(documents.load(project, refs, false), 409);
        var b = executeFund(topupInput("DEPOSIT", "60.00"));
        credits.createUpdate()
                .set("originalAmount", new BigDecimal("100.00"))
                .where("id", b.entry().getId())
                .execute()
                .block(WAIT);
        fixture.rejects(
                documents.load(
                        project,
                        List.of(
                                new DocumentSourceService.Reference(
                                        "FUND_TOPUP", b.entry().getId())),
                        false),
                409);
        var c = executeFund(topupInput("ADVANCE", "10.00"));
        var restricted = fixture.allowed();
        var holder =
                restricted.<org.jetlinks.pro.assets.AssetsHolder>get(
                        org.jetlinks.pro.assets.AssetsHolder.class);
        when(holder.assertPermission(anyString(), anyCollection(), any()))
                .thenReturn(Mono.error(FinanceScope.error("permission_denied", 403)));
        reactor.test.StepVerifier.create(
                        documents
                                .load(
                                        project,
                                        List.of(
                                                new DocumentSourceService.Reference(
                                                        "FUND_TOPUP", c.entry().getId())),
                                        false)
                                .contextWrite(restricted))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
    }

    @Test
    void lateAuditFailureRollsBackBothCountersAndEverySource() throws Exception {
        var a = executeFund(topupInput("ADVANCE", "40.00"));
        var b = executeFund(topupInput("ADVANCE", "60.00"));
        var req =
                submission(
                        input(
                                List.of(
                                        item("FUND_TOPUP", a.entry().getId(), "40.00"),
                                        item("FUND_TOPUP", b.entry().getId(), "60.00"))));
        var sql =
                "alter table pm_fin_doc_operation add constraint reject_fund_document check"
                        + " (project_id <> '"
                        + project
                        + "')";
        assertEquals(
                0,
                HouseSettingPersistenceTest.DB
                        .execInContainer("psql", "-U", "postgres", "-d", "setting_test", "-c", sql)
                        .getExitCode());
        try {
            reactor.test.StepVerifier.create(
                            issue.confirm(project, req, NOW).contextWrite(fixture.allowed()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(
                    new BigDecimal("0.00"),
                    accounts.findById(a.account().getId()).block(WAIT).getInvoiceOccupiedAmount());
            assertEquals(new BigDecimal("40.00"), original(a.entry()).availableAmount());
            assertEquals(new BigDecimal("60.00"), original(b.entry()).availableAmount());
            assertEquals(
                    0,
                    occupationRows.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(
                    0, documentRows.createQuery().where("projectId", project).count().block(WAIT));
        } finally {
            HouseSettingPersistenceTest.DB.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "setting_test",
                    "-c",
                    "alter table pm_fin_doc_operation drop constraint reject_fund_document");
        }
        assertEquals("100.00", issue(req).document().getAmount().toPlainString());
    }

    @Test
    void invoiceAndRefundCompeteForSameOriginalWithoutDoubleSpending() {
        var a = executeFund(topupInput("ADVANCE", "100.00"));
        var request = submission(input(List.of(item("FUND_TOPUP", a.entry().getId(), "100.00"))));
        var refund = topupInput("ADVANCE", "100.00");
        refund.setAction("REFUND");
        refund.setAccountId(a.account().getId());
        refund.setVersion(a.account().getVersion());
        var preview =
                funds.preview(project, refund, DATE).contextWrite(fixture.allowed()).block(WAIT);
        var fundRequest =
                new FundsService.Submission(UUID.randomUUID().toString(), preview.token(), refund);
        var results =
                Mono.zip(
                                issue.confirm(project, request, NOW).materialize(),
                                funds.execute(project, fundRequest, DATE).materialize())
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertNotEquals(results.getT1().isOnNext(), results.getT2().isOnNext());
        var failure =
                results.getT1().isOnError()
                        ? results.getT1().getThrowable()
                        : results.getT2().getThrowable();
        assertTrue(TaxItemPersistenceTest.status(failure, 409));
        var account = accounts.findById(a.account().getId()).block(WAIT);
        var credit = credits.findById(a.entry().getId()).block(WAIT);
        if (results.getT1().isOnNext()) {
            assertEquals(new BigDecimal("100.00"), account.getBalance());
            assertEquals(new BigDecimal("100.00"), account.getInvoiceOccupiedAmount());
            assertEquals(new BigDecimal("100.00"), credit.getInvoiceOccupiedAmount());
        } else {
            assertEquals(new BigDecimal("0.00"), account.getBalance());
            assertEquals(new BigDecimal("0.00"), account.getInvoiceOccupiedAmount());
            assertEquals(new BigDecimal("0.00"), credit.getRemainingAmount());
        }
    }

    @Test
    void sourceHttpUsesExactMoneyAndSeparateFundTypeWithoutExposingRawSnapshots() {
        var a = executeFund(topupInput("DEPOSIT", "9007199254740993.01"));
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web.InvoiceDocumentController(
                                        preparation,
                                        issue,
                                        reverse,
                                        null,
                                        candidates,
                                        null,
                                        new BillingJobProperties()))
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange).contextWrite(fixture.allowed()))
                        .build();
        client.post()
                .uri("/property/finance/projects/" + project + "/invoices/sources/_query")
                .bodyValue(
                        new DocumentCandidateService.Input(
                                "FUND_TOPUP",
                                "company",
                                a.account().getCustomerId(),
                                null,
                                null,
                                0,
                                12))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1)
                .jsonPath("$.data[0].sourceType")
                .isEqualTo("FUND_TOPUP")
                .jsonPath("$.data[0].fundType")
                .isEqualTo("DEPOSIT")
                .jsonPath("$.data[0].availableAmount")
                .isEqualTo("9007199254740993.01")
                .jsonPath("$.data[0].snapshotJson")
                .doesNotExist()
                .jsonPath("$.data[0].documentSnapshotJson")
                .doesNotExist();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertFalse(mapper.valueToTree(a.entry()).has("documentSnapshotJson"));
    }

    @Test
    void invoiceAndReceiptShareOriginalQuotaAndUsedReceiptCannotBeVoided() {
        var a = executeFund(topupInput("DEPOSIT", "100.01"));
        var blue =
                issue(submission(input(List.of(item("FUND_TOPUP", a.entry().getId(), "80.00")))));
        var value = input(List.of(item("FUND_TOPUP", a.entry().getId(), "20.01")));
        var receiptInput =
                new DocumentIssueInput(
                        "RECEIPT", "company", value.buyer(), null, null, value.items());
        var receipt = issue(submission(receiptInput));
        assertEquals(
                new BigDecimal("100.01"),
                accounts.findById(a.account().getId()).block(WAIT).getInvoiceOccupiedAmount());
        fixture.rejects(
                preparation.preview(
                        project,
                        input(List.of(item("FUND_TOPUP", a.entry().getId(), "0.01"))),
                        NOW),
                409);
        fixture.rejects(
                reverse.reverse(
                        project,
                        "RECEIPT",
                        receipt.document().getId(),
                        reversal(receipt.document(), "付款关联不许作废"),
                        NOW),
                409);
        reverse(blue.document(), reversal(blue.document(), "红冲原发票"));
        assertEquals(
                new BigDecimal("20.01"),
                accounts.findById(a.account().getId()).block(WAIT).getInvoiceOccupiedAmount());
        assertEquals(new BigDecimal("80.00"), original(a.entry()).availableAmount());
    }

    @Configuration
    @Import({
        DocumentIssuePersistenceTest.Config.class,
        FundsPersistenceTest.Config.class,
        DocumentCandidateService.class,
        DocumentQueryScope.class
    })
    static class Config {}
}
