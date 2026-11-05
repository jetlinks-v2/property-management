package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.util.*;

/** 实收转预存必须原子恢复欠费、占用原可退额，不能重复计算现金流。 */
@SpringBootTest(
        classes = PaymentToAdvancePersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PaymentToAdvancePersistenceTest {
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        HouseSettingPersistenceTest.database(r);
    }

    @Autowired PaymentToAdvanceService service;
    @Autowired CashierService cashier;
    @Autowired TemporaryService temporary;
    @Autowired FundsService funds;
    @Autowired CashierConfiguration configuration;
    @Autowired HouseArchiveSource archive;
    @Autowired ReactiveRepository<FundAccountEntity, String> accounts;
    @Autowired ReactiveRepository<FundEntryEntity, String> entries;
    @Autowired ReactiveRepository<StandardEntity, String> standards;
    @Autowired ReactiveRepository<ProjectSubjectEntity, String> issued;
    @Autowired ReactiveRepository<SubjectEntity, String> subjects;
    @Autowired ReactiveRepository<ReceivableEntity, String> bills;
    @Autowired ReactiveRepository<PaymentAllocationEntity, String> lines;
    @Autowired ReactiveRepository<RefundEntity, String> refunds;
    FundsPersistenceTest fixture;
    String project, billId, lineId;

    @BeforeEach
    void setup() {
        fixture = new FundsPersistenceTest();
        fixture.service = funds;
        fixture.configuration = configuration;
        fixture.archive = archive;
        fixture.accounts = accounts;
        fixture.entries = entries;
        fixture.standards = standards;
        fixture.issued = issued;
        fixture.subjects = subjects;
        fixture.bills = bills;
        fixture.setup();
        fixture.fixture.cashier = cashier;
        project = fixture.project;
        var bill = fixture.fixture.bill("convert");
        billId = bill.getId();
        var payment =
                cashier.collect(
                                project,
                                fixture.fixture.submission(fixture.fixture.input(bill, "60.01")),
                                DATE)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        lineId = payment.lines().get(0).getId();
    }

    PaymentToAdvanceInput input(String amount) {
        var i = new PaymentToAdvanceInput();
        var line = lines.findById(lineId).block(WAIT);
        i.setItems(List.of(new RefundInput.Item(lineId, line.getVersion())));
        i.setCompanyId("company");
        i.setTargetCompanyId("company");
        i.setSpaceId("room");
        i.setRelationshipId("link");
        i.setStandardId(fixture.setting.standardId);
        i.setAmount(new BigDecimal(amount));
        i.setOccurredAt("2024-02-15T13:00:00");
        i.setReason("实收转预存");
        return i;
    }

    PaymentToAdvanceService.Submission request(PaymentToAdvanceInput input) {
        var p =
                service.preview(project, input, DATE)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        return new PaymentToAdvanceService.Submission(
                UUID.randomUUID().toString(), p.token(), input);
    }

    FundsService.Result confirm(PaymentToAdvanceService.Submission r) {
        return service.confirm(project, r, DATE)
                .contextWrite(fixture.fixture.allowed())
                .block(WAIT);
    }

    void money(String expected, BigDecimal actual) {
        assertEquals(new BigDecimal(expected), actual);
    }

    @Autowired FinancialPeriodTestSupport periods;

    @Test
    void closedPeriodBlocksNewPaymentConversionButRecoversSuccessfulRequest() {
        var original = request(input("10.01"));
        var result = confirm(original);
        var next = request(input("5.00"));
        periods.close(project, DATE).contextWrite(fixture.fixture.allowed()).block(WAIT);
        FinancialPeriodTestSupport.rejectsClosed(
                service.confirm(project, next, DATE).contextWrite(fixture.fixture.allowed()));
        assertEquals(result.entry().getId(), confirm(original).entry().getId());
        money("10.01", accounts.findById(result.account().getId()).block(WAIT).getBalance());
        money("50.00", bills.findById(billId).block(WAIT).getPaidAmount());
        assertEquals(1, refunds.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void partialAndRepeatedConversionRestoreDebtWithoutCashOrSecondReceipt() {
        var first = confirm(request(input("10.01")));
        money("10.01", first.account().getBalance());
        money("50.00", bills.findById(billId).block(WAIT).getPaidAmount());
        money("50.00", bills.findById(billId).block(WAIT).getArrears());
        money("0.00", first.entry().getCashAmount());
        assertEquals("TO_ADVANCE", first.entry().getAction());
        var refund = refunds.findById(first.entry().getRefundId()).block(WAIT);
        assertEquals("BALANCE", refund.getDestination());
        assertEquals(first.account().getId(), refund.getAccountId());
        assertEquals("TO_ADVANCE", refund.getMethod());
        var second = confirm(request(input("50.00")));
        money("60.01", second.account().getBalance());
        money("100.00", bills.findById(billId).block(WAIT).getArrears());
        var origin = lines.findById(lineId).block(WAIT);
        money("60.01", origin.getAmount());
        money("60.01", origin.getRefundedAmount());
        assertEquals(1, lines.createQuery().where("projectId", project).count().block(WAIT));
        fixture.rejects(service.preview(project, input("0.01"), DATE), 409);
    }

    @Test
    void targetCanBeAnotherHouseAndCompanyWhileRefundKeepsOriginalCompany() {
        var standard = standards.findById(fixture.setting.standardId).block(WAIT);
        standards
                .createUpdate()
                .set("companyId", "target-company")
                .where("id", standard.getId())
                .execute()
                .block(WAIT);
        org.mockito.Mockito.when(
                        configuration.balanceCompany(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(
                        call -> {
                            var c = new FinanceCompanyEntity();
                            c.setId(call.getArgument(1));
                            c.setCompanyName("公司-" + c.getId());
                            c.setVersion(1L);
                            return Mono.just(c);
                        });
        var i = input("20.00");
        i.setSpaceId("other-room");
        i.setTargetCompanyId("target-company");
        var result = confirm(request(i));
        assertEquals("other-room", result.account().getSpaceId());
        assertEquals("target-company", result.account().getCompanyId());
        assertEquals(
                "company",
                refunds.findById(result.entry().getRefundId()).block(WAIT).getCompanyId());
        money("40.01", bills.findById(billId).block(WAIT).getPaidAmount());
    }

    @Test
    void originalRequestReplaysButChangedInputAndStaleSourcesAreRejected() {
        var request = request(input("10.00"));
        var first = confirm(request);
        assertEquals(first.entry().getId(), confirm(request).entry().getId());
        request.input().setAmount(new BigDecimal("11.00"));
        fixture.rejects(service.confirm(project, request, DATE), 409);
        var stale = request(input("10.00"));
        confirm(request(input("1.00")));
        fixture.rejects(service.confirm(project, stale, DATE), 409);
        money("11.00", accounts.findById(first.account().getId()).block(WAIT).getBalance());
    }

    @Test
    void competingRequestsCannotBothSpendSameOriginalBalance() {
        var a = request(input("40.00"));
        var b = request(input("40.00"));
        var result =
                Flux.merge(
                                service.confirm(project, a, DATE)
                                        .contextWrite(fixture.fixture.allowed())
                                        .map(x -> true)
                                        .onErrorReturn(false),
                                service.confirm(project, b, DATE)
                                        .contextWrite(fixture.fixture.allowed())
                                        .map(x -> true)
                                        .onErrorReturn(false))
                        .collectList()
                        .block(WAIT);
        assertEquals(1, result.stream().filter(Boolean::booleanValue).count());
        money("40.00", lines.findById(lineId).block(WAIT).getRefundedAmount());
    }

    @Test
    void occupiedSourcesWrongProjectAndInvalidPeriodRejectWithoutWrites() {
        fixture.rejects(service.preview("foreign", input("1.00"), DATE), 404);
        var i = input("1.00");
        i.setStartDate("2024-02-01");
        fixture.rejects(service.preview(project, i, DATE), 400);
        lines.createUpdate()
                .set("invoiceOccupiedAmount", new BigDecimal("0.01"))
                .where("id", lineId)
                .execute()
                .block(WAIT);
        fixture.rejects(service.preview(project, input("1.00"), DATE), 409);
        assertEquals(0, accounts.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void temporaryReceiptConversionHasNoReceivableAndUsesRemainingCentWeights() {
        var i = new TemporaryInput();
        i.setSpaceId("room");
        i.setRelationshipId("link");
        i.setStandardId(fixture.setting.standardId);
        i.setCompanyId("company");
        i.setPaidAt("2024-02-15T12:00:00");
        i.setPayerName("付款人");
        i.setStartDate("2024-02-01");
        i.setEndDate("2024-02-29");
        i.setAccountPeriod("2024-02");
        i.setAmount(new BigDecimal("0.03"));
        i.setTenders(
                List.of(
                        new CashierInput.Tender("1", null, new BigDecimal("0.01")),
                        new CashierInput.Tender("3", "bank", new BigDecimal("0.02"))));
        var preview =
                temporary
                        .preview(project, i, DATE)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        var receipt =
                temporary
                        .collect(
                                project,
                                new TemporaryService.Submission(
                                        UUID.randomUUID().toString(), preview.token(), i),
                                DATE)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        var conversion = input("0.01");
        conversion.setItems(
                receipt.lines().stream()
                        .map(l -> new RefundInput.Item(l.getId(), l.getVersion()))
                        .toList());
        var result = confirm(request(conversion));
        money("0.01", result.account().getBalance());
        money("60.01", bills.findById(billId).block(WAIT).getPaidAmount());
        var source = lines.findById(receipt.lines().get(1).getId()).block(WAIT);
        money("0.01", source.getRefundedAmount());
        assertNull(source.getReceivableId());
        conversion.setItems(
                lines.createQuery()
                        .where("paymentId", receipt.payment().getId())
                        .fetch()
                        .map(l -> new RefundInput.Item(l.getId(), l.getVersion()))
                        .collectList()
                        .block(WAIT));
        conversion.setAmount(new BigDecimal("0.02"));
        confirm(request(conversion));
        money(
                "0.03",
                lines.createQuery()
                        .where("paymentId", receipt.payment().getId())
                        .fetch()
                        .map(PaymentAllocationEntity::getRefundedAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .block(WAIT));
        assertEquals(1, bills.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void standardOrTargetBalanceChangesInvalidatePreviewAndBothSpacePermissionsApply() {
        var stale = request(input("1.00"));
        standards
                .createUpdate()
                .set("version", 2L)
                .where("id", fixture.setting.standardId)
                .execute()
                .block(WAIT);
        fixture.rejects(service.confirm(project, stale, DATE), 409);
        var balancePreview = request(input("1.00"));
        fixture.run(fixture.input("TOPUP", "1.00"));
        fixture.rejects(service.confirm(project, balancePreview, DATE), 409);
        var denied = fixture.fixture.allowed();
        var holder =
                denied.<org.jetlinks.pro.assets.AssetsHolder>get(
                        org.jetlinks.pro.assets.AssetsHolder.class);
        org.mockito.Mockito.when(
                        holder.assertPermission(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyCollection(),
                                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        call ->
                                call.<Collection<String>>getArgument(1).contains("other-room")
                                        ? Mono.error(FinanceScope.error("permission_denied", 403))
                                        : Mono.empty());
        var forbidden = input("1.00");
        forbidden.setSpaceId("other-room");
        reactor.test.StepVerifier.create(
                        service.preview(project, forbidden, DATE).contextWrite(denied))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        org.mockito.Mockito.when(
                        holder.assertPermission(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyCollection(),
                                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        call ->
                                call.<Collection<String>>getArgument(1).contains("room")
                                        ? Mono.error(FinanceScope.error("permission_denied", 403))
                                        : Mono.empty());
        reactor.test.StepVerifier.create(
                        service.preview(project, forbidden, DATE).contextWrite(denied))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        money("0.00", lines.findById(lineId).block(WAIT).getRefundedAmount());
    }

    @Test
    void httpContractHasExactAmountsHiddenFingerprintAndNoDelete() {
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web
                                        .PaymentToAdvanceController(
                                        service, null, null, null, new BillingJobProperties()))
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange)
                                                .contextWrite(fixture.fixture.allowed()))
                        .build();
        var path = "/property/finance/projects/" + project + "/payment-to-advance";
        var i = input("10.01");
        var p =
                client.post()
                        .uri(path + "/_preview")
                        .bodyValue(i)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(PaymentToAdvanceService.Preview.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(p);
        assertEquals("10.01", p.afterBalance());
        var request =
                new PaymentToAdvanceService.Submission(UUID.randomUUID().toString(), p.token(), i);
        client.post()
                .uri(path + "/_confirm")
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.entry.amount")
                .isEqualTo("10.01")
                .jsonPath("$.entry.cashAmount")
                .isEqualTo("0.00")
                .jsonPath("$.entry.refundId")
                .isNotEmpty()
                .jsonPath("$.entry.requestFingerprint")
                .doesNotExist()
                .jsonPath("$.entry.inputSnapshotJson")
                .doesNotExist();
        client.post().uri(path + "/_confirm").bodyValue(request).exchange().expectStatus().isOk();
        client.delete().uri(path + "/fake").exchange().expectStatus().is4xxClientError();
        money("10.01", lines.findById(lineId).block(WAIT).getRefundedAmount());
    }

    @Test
    void lastWriteFailureRollsBackSourceDebtRefundAndTargetBalance() throws Exception {
        var request = request(input("10.00"));
        var name = "convert_" + UUID.randomUUID().toString().replace("-", "");
        assertEquals(
                0,
                HouseSettingPersistenceTest.DB
                        .execInContainer(
                                "psql",
                                "-U",
                                "postgres",
                                "-d",
                                "setting_test",
                                "-c",
                                "ALTER TABLE pm_fin_fund_entry ADD CONSTRAINT "
                                        + name
                                        + " CHECK (project_id <> '"
                                        + project
                                        + "' OR action <> 'TO_ADVANCE')")
                        .getExitCode());
        try {
            reactor.test.StepVerifier.create(
                            service.confirm(project, request, DATE)
                                    .contextWrite(fixture.fixture.allowed()))
                    .expectError()
                    .verify(WAIT);
            money("60.01", bills.findById(billId).block(WAIT).getPaidAmount());
            money("0.00", lines.findById(lineId).block(WAIT).getRefundedAmount());
            assertEquals(0, refunds.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(0, accounts.createQuery().where("projectId", project).count().block(WAIT));
        } finally {
            HouseSettingPersistenceTest.DB.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "setting_test",
                    "-c",
                    "ALTER TABLE pm_fin_fund_entry DROP CONSTRAINT " + name);
        }
    }

    @Configuration
    @Import({
        FundsPersistenceTest.Config.class,
        TemporaryPersistenceTest.Config.class,
        PaymentToAdvanceService.class
    })
    static class Config {}
}
