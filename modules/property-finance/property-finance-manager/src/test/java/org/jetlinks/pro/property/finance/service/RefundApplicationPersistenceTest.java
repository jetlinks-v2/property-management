package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

/** 使用真实数据库核对申请不动账、额度占用与审核幂等。 */
@SpringBootTest(
        classes = RefundApplicationPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RefundApplicationPersistenceTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        HouseSettingPersistenceTest.database(registry);
    }

    @Autowired RefundApplicationService applications;
    @Autowired RefundService refunds;
    @Autowired CashierService cashier;
    @Autowired CashierConfiguration configuration;
    @Autowired HouseArchiveSource archive;
    @Autowired HistoricalReceiptFixture receipts;
    @Autowired ReactiveRepository<ReceivableEntity, String> bills;
    @Autowired ReactiveRepository<PaymentAllocationEntity, String> sources;
    @Autowired FundsService funds;
    @Autowired ReactiveRepository<FundAccountEntity, String> accounts;
    @Autowired ReactiveRepository<FundEntryEntity, String> fundEntries;
    @Autowired ReactiveRepository<StandardEntity, String> standards;
    @Autowired ReactiveRepository<ProjectSubjectEntity, String> issued;
    @Autowired ReactiveRepository<SubjectEntity, String> subjects;
    RefundPersistenceTest fixture;

    @BeforeEach
    void setup() {
        fixture = new RefundPersistenceTest();
        fixture.cashier = cashier;
        fixture.configuration = configuration;
        fixture.archive = archive;
        fixture.bills = bills;
        fixture.refunds = refunds;
        fixture.historicalReceipts = receipts;
        fixture.setup();
    }

    @Test
    void applicationDoesNotRefundUntilApprovedAndRetriesDoNotPayTwice() {
        var bill = fixture.fixture.bill("application");
        var paid = fixture.collect(bill, "60.00");
        var input = fixture.input(paid.lines(), "10.00");
        var request = fixture.submission(input);
        var row =
                applications
                        .submitPayment(fixture.project, request, DATE)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        assertEquals("PENDING", row.getStatus());
        fixture.money("60.00", bills.findById(bill.getId()).block(WAIT).getPaidAmount());
        var source = sources.findById(paid.lines().get(0).getId()).block(WAIT);
        fixture.money("10.00", source.getRefundReservedAmount());
        assertEquals(
                row.getId(),
                applications
                        .submitPayment(fixture.project, request, DATE)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT)
                        .getId());
        var action =
                new RefundApplicationService.Action(
                        UUID.randomUUID().toString(), row.getVersion(), "核对通过");
        var done =
                applications
                        .act(fixture.project, row.getId(), "APPROVE", action, DATE)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        assertEquals("SUCCEEDED", done.getStatus());
        applications
                .act(fixture.project, row.getId(), "APPROVE", action, DATE)
                .contextWrite(fixture.fixture.allowed())
                .block(WAIT);
        fixture.money("50.00", bills.findById(bill.getId()).block(WAIT).getPaidAmount());
        fixture.money(
                "0.00", sources.findById(source.getId()).block(WAIT).getRefundReservedAmount());
    }

    @Test
    void rejectionReleasesReservationAndDoesNotChangeOriginalReceipt() {
        var bill = fixture.fixture.bill("rejection");
        var paid = fixture.collect(bill, "40.00");
        var row =
                applications
                        .submitPayment(
                                fixture.project,
                                fixture.submission(fixture.input(paid.lines(), "15.00")),
                                DATE)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        var rejected =
                applications
                        .act(
                                fixture.project,
                                row.getId(),
                                "REJECT",
                                new RefundApplicationService.Action(
                                        UUID.randomUUID().toString(), row.getVersion(), "资料错误"),
                                DATE)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        assertEquals("REJECTED", rejected.getStatus());
        fixture.money("40.00", bills.findById(bill.getId()).block(WAIT).getPaidAmount());
        fixture.money(
                "0.00",
                sources.findById(paid.lines().get(0).getId())
                        .block(WAIT)
                        .getRefundReservedAmount());
    }

    @Test
    void pendingAdvanceRefundProtectsBalanceAndApprovalPaysOnlyOnce() {
        var fund = new FundsPersistenceTest();
        fund.service = funds;
        fund.configuration = configuration;
        fund.archive = archive;
        fund.bills = bills;
        fund.accounts = accounts;
        fund.entries = fundEntries;
        fund.standards = standards;
        fund.issued = issued;
        fund.subjects = subjects;
        fund.setup();
        var account = fund.run(fund.input("TOPUP", "100.00")).account();
        var request = fund.request(fund.debit(account, "REFUND", "30.00"));
        var row =
                applications
                        .submitFund(fund.project, request, DATE)
                        .contextWrite(fund.fixture.allowed())
                        .block(WAIT);
        fund.money("100.00", accounts.findById(account.getId()).block(WAIT).getBalance());
        fund.money(
                "30.00", accounts.findById(account.getId()).block(WAIT).getRefundReservedAmount());
        var current = accounts.findById(account.getId()).block(WAIT);
        fund.rejects(
                funds.preview(fund.project, fund.debit(current, "DEDUCT", "80.00"), DATE), 400);
        var action =
                new RefundApplicationService.Action(
                        UUID.randomUUID().toString(), row.getVersion(), "核对退款");
        applications
                .act(fund.project, row.getId(), "APPROVE", action, DATE)
                .contextWrite(fund.fixture.allowed())
                .block(WAIT);
        applications
                .act(fund.project, row.getId(), "APPROVE", action, DATE)
                .contextWrite(fund.fixture.allowed())
                .block(WAIT);
        fund.money("70.00", accounts.findById(account.getId()).block(WAIT).getBalance());
        fund.money(
                "0.00", accounts.findById(account.getId()).block(WAIT).getRefundReservedAmount());
        assertEquals(
                1,
                fundEntries
                        .createQuery()
                        .where("projectId", fund.project)
                        .and("action", "REFUND")
                        .count()
                        .block(WAIT));
    }

    @Test
    void editRechecksEntireAmountAndRollsBackReservationWhenInvalid() {
        var bill = fixture.fixture.bill("edit-application");
        var paid = fixture.collect(bill, "60.00");
        var row =
                applications
                        .submitPayment(
                                fixture.project,
                                fixture.submission(fixture.input(paid.lines(), "10.00")),
                                DATE)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        var edit =
                new RefundApplicationService.Edit(
                        row.getVersion(),
                        new java.math.BigDecimal("20.00"),
                        "新收款人",
                        "2024-02-15T13:00:00",
                        "调整资料");
        var updated =
                applications
                        .edit(fixture.project, row.getId(), edit, DATE)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        fixture.money(
                "20.00",
                sources.findById(paid.lines().get(0).getId())
                        .block(WAIT)
                        .getRefundReservedAmount());
        fixture.rejects(
                applications.edit(
                        fixture.project,
                        row.getId(),
                        new RefundApplicationService.Edit(
                                updated.getVersion(),
                                new java.math.BigDecimal("99.00"),
                                "新收款人",
                                "2024-02-15T13:00:00",
                                "超额"),
                        DATE),
                409);
        fixture.money(
                "20.00",
                sources.findById(paid.lines().get(0).getId())
                        .block(WAIT)
                        .getRefundReservedAmount());
        assertEquals(
                2,
                applications
                        .logs(fixture.project, row.getId())
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT)
                        .size());
        applications
                .act(
                        fixture.project,
                        row.getId(),
                        "APPROVE",
                        new RefundApplicationService.Action(
                                UUID.randomUUID().toString(), updated.getVersion(), "核对无误"),
                        DATE)
                .contextWrite(fixture.fixture.allowed())
                .block(WAIT);
        fixture.money("40.00", bills.findById(bill.getId()).block(WAIT).getPaidAmount());
    }

    @Test
    void depositRefundRejectionReleasesBalanceWithoutWritingCashThenCanApplyAgain() {
        var fund = new FundsPersistenceTest();
        fund.service = funds;
        fund.configuration = configuration;
        fund.archive = archive;
        fund.bills = bills;
        fund.accounts = accounts;
        fund.entries = fundEntries;
        fund.standards = standards;
        fund.issued = issued;
        fund.subjects = subjects;
        fund.setup();
        var topup = fund.input("TOPUP", "100.00");
        topup.setType("DEPOSIT");
        topup.setStandardId(null);
        topup.setSubjectId(fund.setting.subjectId);
        topup.setStartDate(null);
        topup.setEndDate(null);
        var account = fund.run(topup).account();
        var debit = fund.debit(account, "REFUND", "30.00");
        debit.setType("DEPOSIT");
        var row =
                applications
                        .submitFund(fund.project, fund.request(debit), DATE)
                        .contextWrite(fund.fixture.allowed())
                        .block(WAIT);
        fund.money("100.00", accounts.findById(account.getId()).block(WAIT).getBalance());
        applications
                .act(
                        fund.project,
                        row.getId(),
                        "REJECT",
                        new RefundApplicationService.Action(
                                UUID.randomUUID().toString(), row.getVersion(), "资料有误"),
                        DATE)
                .contextWrite(fund.fixture.allowed())
                .block(WAIT);
        var restored = accounts.findById(account.getId()).block(WAIT);
        fund.money("0.00", restored.getRefundReservedAmount());
        assertEquals(
                0,
                fundEntries
                        .createQuery()
                        .where("projectId", fund.project)
                        .and("action", "REFUND")
                        .count()
                        .block(WAIT));
        debit = fund.debit(restored, "REFUND", "30.00");
        debit.setType("DEPOSIT");
        var next =
                applications
                        .submitFund(fund.project, fund.request(debit), DATE)
                        .contextWrite(fund.fixture.allowed())
                        .block(WAIT);
        applications
                .act(
                        fund.project,
                        next.getId(),
                        "APPROVE",
                        new RefundApplicationService.Action(
                                UUID.randomUUID().toString(), next.getVersion(), "核对无误"),
                        DATE)
                .contextWrite(fund.fixture.allowed())
                .block(WAIT);
        fund.money("70.00", accounts.findById(account.getId()).block(WAIT).getBalance());
        assertEquals(
                1,
                fundEntries
                        .createQuery()
                        .where("projectId", fund.project)
                        .and("action", "REFUND")
                        .count()
                        .block(WAIT));
    }

    @Configuration
    @Import({
        RefundPersistenceTest.Config.class,
        FundsPersistenceTest.Config.class,
        RefundApplicationService.class
    })
    static class Config {}
}
