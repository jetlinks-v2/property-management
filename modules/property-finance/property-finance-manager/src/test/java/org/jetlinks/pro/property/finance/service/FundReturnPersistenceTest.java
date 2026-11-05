package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.util.*;

/** 原冲抵退回余额必须原子恢复应收，不产生现金出款。 */
@SpringBootTest(
        classes = FundReturnPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FundReturnPersistenceTest {
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        HouseSettingPersistenceTest.database(r);
    }

    @Autowired FundReturnService service;
    @Autowired RefundService cashRefunds;
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
    String project, billId, entryId, accountId;

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
        project = fixture.project;
        var account = fixture.run(fixture.input("TOPUP", "80.00")).account();
        accountId = account.getId();
        var bill = fixture.fixture.bill("return");
        billId = bill.getId();
        bills.createUpdate()
                .set("subjectId", fixture.setting.subjectId)
                .where("id", billId)
                .execute()
                .block(WAIT);
        var offset = fixture.debit(account, "OFFSET", "30.01");
        offset.setItems(
                List.of(new CashierInput.Item(billId, bill.getVersion(), new BigDecimal("30.01"))));
        entryId = fixture.run(offset).entry().getId();
    }

    FundReturnInput input(String amount) {
        return new FundReturnInput(
                entryId,
                accounts.findById(accountId).block(WAIT).getVersion(),
                new BigDecimal(amount),
                "2024-02-15T13:00:00",
                "撤销部分结转",
                null,
                null);
    }

    FundReturnService.Submission request(FundReturnInput input) {
        var p =
                service.preview(project, input, DATE)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        return new FundReturnService.Submission(UUID.randomUUID().toString(), p.token(), input);
    }

    FundsService.Result run(String amount) {
        var i = input(amount);
        return service.confirm(project, request(i), DATE)
                .contextWrite(fixture.fixture.allowed())
                .block(WAIT);
    }

    void money(String n, BigDecimal v) {
        assertEquals(new BigDecimal(n), v);
    }

    @Autowired FinancialPeriodTestSupport periods;

    @Test
    void closedPeriodBlocksNewOffsetReturnButRecoversSuccessfulRequest() {
        var original = request(input("10.01"));
        var result =
                service.confirm(project, original, DATE)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        var next = request(input("10.00"));
        periods.close(project, DATE).contextWrite(fixture.fixture.allowed()).block(WAIT);
        FinancialPeriodTestSupport.rejectsClosed(
                service.confirm(project, next, DATE).contextWrite(fixture.fixture.allowed()));
        assertEquals(
                result.entry().getId(),
                service.confirm(project, original, DATE)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT)
                        .entry()
                        .getId());
        money("60.00", accounts.findById(accountId).block(WAIT).getBalance());
        money("20.00", bills.findById(billId).block(WAIT).getPaidAmount());
        assertEquals(1, refunds.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void partialAndFullReturnRestoreOriginalBalanceAndDebtWithZeroCash() {
        var partial = run("10.01");
        money("60.00", partial.account().getBalance());
        money("20.00", bills.findById(billId).block(WAIT).getPaidAmount());
        money("80.00", bills.findById(billId).block(WAIT).getArrears());
        money("0.00", partial.entry().getCashAmount());
        assertEquals(entryId, partial.entry().getSourceEntryId());
        var returned = refunds.findById(partial.entry().getRefundId()).block(WAIT);
        assertEquals("BALANCE", returned.getDestination());
        assertEquals(accountId, returned.getAccountId());
        var full = run("20.00");
        money("80.00", full.account().getBalance());
        money("0.00", bills.findById(billId).block(WAIT).getPaidAmount());
        money("100.00", bills.findById(billId).block(WAIT).getArrears());
        money(
                "80.00",
                entries.createQuery()
                        .where("projectId", project)
                        .fetch()
                        .map(FundEntryEntity::getCashAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .block(WAIT));
        fixture.rejects(service.preview(project, input("0.01"), DATE), 409);
    }

    @Test
    void sameRequestIsIdempotentButChangedPayloadAndStalePreviewAreRejected() {
        var i = input("1.00");
        var request = request(i);
        var first =
                service.confirm(project, request, DATE)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        var retry =
                service.confirm(project, request, DATE)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        assertEquals(first.entry().getId(), retry.entry().getId());
        money("50.99", retry.account().getBalance());
        var changed =
                new FundReturnInput(
                        entryId,
                        i.accountVersion(),
                        new BigDecimal("2.00"),
                        i.occurredAt(),
                        i.reason(),
                        null,
                        null);
        fixture.rejects(
                service.confirm(
                        project,
                        new FundReturnService.Submission(
                                request.requestId(), request.token(), changed),
                        DATE),
                409);
        var stale = request(input("2.00"));
        run("1.00");
        fixture.rejects(service.confirm(project, stale, DATE), 409);
    }

    @Test
    void concurrentDifferentRequestsCannotReturnSameAvailableAmountTwice() {
        var one = request(input("20.00"));
        var two = request(input("20.00"));
        var outcomes =
                Flux.merge(
                                service.confirm(project, one, DATE)
                                        .contextWrite(fixture.fixture.allowed())
                                        .map(x -> true)
                                        .onErrorReturn(false),
                                service.confirm(project, two, DATE)
                                        .contextWrite(fixture.fixture.allowed())
                                        .map(x -> true)
                                        .onErrorReturn(false))
                        .collectList()
                        .block(WAIT);
        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
        money("69.99", accounts.findById(accountId).block(WAIT).getBalance());
    }

    @Test
    void occupiedSourceWrongProjectAndNonOffsetEntryCannotReturn() {
        fixture.rejects(service.preview("foreign", input("1.00"), DATE), 404);
        var topup =
                entries.createQuery()
                        .where("accountId", accountId)
                        .and("action", "TOPUP")
                        .fetchOne()
                        .block(WAIT);
        var invalid = input("1.00");
        fixture.rejects(
                service.preview(
                        project,
                        new FundReturnInput(
                                topup.getId(),
                                invalid.accountVersion(),
                                invalid.amount(),
                                invalid.occurredAt(),
                                invalid.reason(),
                                null,
                                null),
                        DATE),
                409);
        lines.createUpdate()
                .set("invoiceOccupiedAmount", new BigDecimal("0.01"))
                .where("paymentId", entryId)
                .execute()
                .block(WAIT);
        fixture.rejects(service.preview(project, input("1.00"), DATE), 409);
        money("49.99", accounts.findById(accountId).block(WAIT).getBalance());
    }

    @Test
    void finalLedgerFailureRollsBackDebtRefundSourcesAndBalance() throws Exception {
        var request = request(input("10.00"));
        var name = "return_" + UUID.randomUUID().toString().replace("-", "");
        var sql =
                "ALTER TABLE pm_fin_fund_entry ADD CONSTRAINT "
                        + name
                        + " CHECK (project_id <> '"
                        + project
                        + "' OR action <> 'RETURN_OFFSET')";
        assertEquals(
                0,
                HouseSettingPersistenceTest.DB
                        .execInContainer("psql", "-U", "postgres", "-d", "setting_test", "-c", sql)
                        .getExitCode());
        try {
            reactor.test.StepVerifier.create(
                            service.confirm(project, request, DATE)
                                    .contextWrite(fixture.fixture.allowed()))
                    .expectError()
                    .verify(WAIT);
            money("49.99", accounts.findById(accountId).block(WAIT).getBalance());
            money("30.01", bills.findById(billId).block(WAIT).getPaidAmount());
            assertEquals(0, refunds.createQuery().where("projectId", project).count().block(WAIT));
            money(
                    "0.00",
                    lines.createQuery()
                            .where("paymentId", entryId)
                            .fetchOne()
                            .block(WAIT)
                            .getRefundedAmount());
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

    @Test
    void httpContractParsesRecordInputAndReturnsReadOnlyExactMoney() {
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web.FundReturnController(
                                        service, new BillingJobProperties()))
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange)
                                                .contextWrite(fixture.fixture.allowed()))
                        .build();
        var input = input("10.01");
        var path = "/property/finance/projects/" + project + "/funds/offset-returns";
        var preview =
                client.post()
                        .uri(path + "/_preview")
                        .bodyValue(input)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(FundReturnService.Preview.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(preview);
        assertEquals("10.01", preview.amount());
        assertEquals("60.00", preview.afterBalance());
        var request =
                new FundReturnService.Submission(
                        UUID.randomUUID().toString(), preview.token(), input);
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
                .jsonPath("$.entry.sourceEntryId")
                .isEqualTo(entryId)
                .jsonPath("$.entry.requestFingerprint")
                .doesNotExist()
                .jsonPath("$.account.balance")
                .isEqualTo("60.00");
        client.post().uri(path + "/_confirm").bodyValue(request).exchange().expectStatus().isOk();
        money("60.00", accounts.findById(accountId).block(WAIT).getBalance());
        client.delete().uri(path + "/fake").exchange().expectStatus().is4xxClientError();
    }

    @Test
    void cannotReturnFromForgedAccountAndCannotRefundOffsetInCash() {
        var forged = fixture.input("TOPUP", "1.00");
        forged.setSourceEntryId(entryId);
        fixture.rejects(funds.preview(project, forged, DATE), 400);
        var line = lines.createQuery().where("paymentId", entryId).fetchOne().block(WAIT);
        var refund = new RefundInput();
        refund.setItems(List.of(new RefundInput.Item(line.getId(), line.getVersion())));
        refund.setCompanyId("company");
        refund.setMethod("1");
        refund.setAmount(new BigDecimal("1.00"));
        refund.setRefundedAt("2024-02-15T13:00:00");
        refund.setReceiverName("客户");
        refund.setReason("不能现金退款");
        fixture.rejects(cashRefunds.preview(project, refund, DATE), 409);
        lines.createUpdate()
                .set("recordedId", "another-account")
                .where("id", line.getId())
                .execute()
                .block(WAIT);
        fixture.rejects(service.preview(project, input("1.00"), DATE), 409);
        money("49.99", accounts.findById(accountId).block(WAIT).getBalance());
    }

    @Test
    void sourceVersionChangeInvalidatesPreviewEvenWhenAccountBalanceUnchanged() {
        var request = request(input("10.00"));
        var line = lines.createQuery().where("paymentId", entryId).fetchOne().block(WAIT);
        lines.createUpdate()
                .set("version", line.getVersion() + 1)
                .where("id", line.getId())
                .execute()
                .block(WAIT);
        fixture.rejects(service.confirm(project, request, DATE), 409);
        money("49.99", accounts.findById(accountId).block(WAIT).getBalance());
        money("30.01", bills.findById(billId).block(WAIT).getPaidAmount());
        assertEquals(0, refunds.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void depositOffsetReturnsToDepositAndDoesNotTouchAdvanceAccount() {
        var topup = fixture.input("TOPUP", "80.00");
        topup.setType("DEPOSIT");
        var deposit = fixture.run(topup).account();
        var bill = fixture.fixture.bill("deposit-return");
        bills.createUpdate()
                .set("subjectId", fixture.setting.subjectId)
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        var offset = fixture.debit(deposit, "OFFSET", "30.01");
        offset.setType("DEPOSIT");
        offset.setItems(
                List.of(
                        new CashierInput.Item(
                                bill.getId(), bill.getVersion(), new BigDecimal("30.01"))));
        var entry = fixture.run(offset).entry();
        var input =
                new FundReturnInput(
                        entry.getId(),
                        1L,
                        new BigDecimal("10.01"),
                        "2024-02-15T13:00:00",
                        "押金冲抵退回",
                        null,
                        null);
        var result =
                service.confirm(project, request(input), DATE)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        assertEquals("DEPOSIT", result.account().getType());
        assertEquals(deposit.getId(), result.account().getId());
        money("60.00", result.account().getBalance());
        money("49.99", accounts.findById(accountId).block(WAIT).getBalance());
        money("20.00", bills.findById(bill.getId()).block(WAIT).getPaidAmount());
        money("0.00", result.entry().getCashAmount());
    }

    @Test
    void tinyPartialReturnAllocatesExactCentsAcrossMultipleBills() {
        var first = fixture.fixture.bill("cent-first");
        var second = fixture.fixture.bill("cent-second");
        bills.createUpdate()
                .set("subjectId", fixture.setting.subjectId)
                .where("id", first.getId())
                .execute()
                .block(WAIT);
        bills.createUpdate()
                .set("subjectId", fixture.setting.subjectId)
                .where("id", second.getId())
                .execute()
                .block(WAIT);
        var account = accounts.findById(accountId).block(WAIT);
        var offset = fixture.debit(account, "OFFSET", "0.03");
        offset.setItems(
                List.of(
                        new CashierInput.Item(
                                first.getId(), first.getVersion(), new BigDecimal("0.01")),
                        new CashierInput.Item(
                                second.getId(), second.getVersion(), new BigDecimal("0.02"))));
        entryId = fixture.run(offset).entry().getId();
        run("0.01");
        money("0.01", bills.findById(first.getId()).block(WAIT).getPaidAmount());
        money("0.01", bills.findById(second.getId()).block(WAIT).getPaidAmount());
        run("0.02");
        money("0.00", bills.findById(first.getId()).block(WAIT).getPaidAmount());
        money("0.00", bills.findById(second.getId()).block(WAIT).getPaidAmount());
        money("49.99", accounts.findById(accountId).block(WAIT).getBalance());
        money(
                "0.03",
                lines.createQuery()
                        .where("paymentId", entryId)
                        .fetch()
                        .map(PaymentAllocationEntity::getRefundedAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .block(WAIT));
    }

    @Configuration
    @Import({
        FundsPersistenceTest.Config.class,
        RefundPersistenceTest.Config.class,
        FundReturnService.class
    })
    static class Config {}
}
