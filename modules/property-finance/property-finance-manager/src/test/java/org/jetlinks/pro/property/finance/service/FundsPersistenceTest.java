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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.core.publisher.*;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.*;

/** 真实PG验证余额守恒、幂等、资金流水及应收核销事务。 */
@SpringBootTest(
        classes = FundsPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FundsPersistenceTest {
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        HouseSettingPersistenceTest.database(r);
    }

    @Autowired FundsService service;
    @Autowired ReactiveRepository<FundAccountEntity, String> accounts;
    @Autowired ReactiveRepository<FundEntryEntity, String> entries;
    @Autowired ReactiveRepository<StandardEntity, String> standards;
    @Autowired ReactiveRepository<ProjectSubjectEntity, String> issued;
    @Autowired ReactiveRepository<SubjectEntity, String> subjects;
    @Autowired ReactiveRepository<ReceivableEntity, String> bills;
    @Autowired ReactiveRepository<PaymentEntity, String> payments;
    @Autowired ReactiveRepository<PaymentAllocationEntity, String> paymentLines;
    @Autowired CashierConfiguration configuration;
    @Autowired HouseArchiveSource archive;
    CashierPersistenceTest fixture;
    HouseSettingPersistenceTest setting;
    String project;

    @BeforeEach
    void setup() {
        fixture = new CashierPersistenceTest();
        fixture.configuration = configuration;
        fixture.archive = archive;
        fixture.bills = bills;
        fixture.setup();
        setting = new HouseSettingPersistenceTest();
        setting.archive = archive;
        setting.standards = standards;
        setting.subjects = issued;
        setting.subjectRows = subjects;
        setting.setup();
        project = setting.project;
        fixture.project = project;
        var company = new FinanceCompanyEntity();
        company.setId("company");
        company.setCompanyName("测试物业公司");
        company.setVersion(1L);
        when(configuration.balanceCompany(anyString(), anyString(), anyBoolean()))
                .thenReturn(Mono.just(company));
    }

    FundsInput input(String action, String amount) {
        var i = new FundsInput();
        i.setAction(action);
        i.setType("ADVANCE");
        i.setAmount(new BigDecimal(amount));
        i.setCompanyId("company");
        i.setSpaceId("room");
        i.setRelationshipId("link");
        i.setStandardId(setting.standardId);
        i.setMethod("1");
        i.setOccurredAt("2024-02-15T12:00:00");
        i.setPayerName("龚超");
        i.setReason("柜台办理");
        return i;
    }

    FundsService.Submission request(FundsInput i) {
        var p = service.preview(project, i, DATE).contextWrite(fixture.allowed()).block(WAIT);
        return new FundsService.Submission(UUID.randomUUID().toString(), p.token(), i);
    }

    FundsService.Result run(FundsInput i) {
        return service.execute(project, request(i), DATE)
                .contextWrite(fixture.allowed())
                .block(WAIT);
    }

    FundsInput debit(FundAccountEntity a, String action, String amount) {
        var i = input(action, amount);
        i.setAccountId(a.getId());
        i.setVersion(a.getVersion());
        return i;
    }

    void money(String n, BigDecimal actual) {
        assertEquals(new BigDecimal(n), actual);
    }

    void rejects(Mono<?> m, int status) {
        fixture.rejects(m, status);
    }

    @Test
    void topupFreezesOptionalBillingDatesForReportingWithoutChangingAmount() {
        var input = input("TOPUP", "20.01");
        input.setStartDate("2024-02-01");
        input.setEndDate("2024-02-29");
        var result = run(input);
        var saved = entries.findById(result.entry().getId()).block(WAIT);
        assertEquals("2024-02-01", saved.getCalcStartDate());
        assertEquals("2024-02-29", saved.getCalcEndDate());
        money("20.01", saved.getCashAmount());
        assertEquals(
                "2024-02-01",
                com.alibaba.fastjson.JSON.parseArray(saved.getInputSnapshotJson()).getString(17));
    }

    @Autowired FinancialPeriodTestSupport periods;

    @Test
    void closedPeriodBlocksEveryNewFundActionButPreservesOriginalTopup() {
        var original = request(input("TOPUP", "100.00"));
        var paid =
                service.execute(project, original, DATE)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        var requests = new ArrayList<FundsService.Submission>();
        requests.add(request(input("TOPUP", "10.00")));
        requests.add(request(debit(paid.account(), "REFUND", "10.00")));
        var transfer = debit(paid.account(), "TRANSFER", "10.00");
        transfer.setTargetSpaceId("other-room");
        transfer.setTargetRelationshipId("link");
        transfer.setTargetStandardId(setting.standardId);
        transfer.setTargetType("ADVANCE");
        requests.add(request(transfer));
        var bill = fixture.bill("period-offset");
        bills.createUpdate()
                .set("subjectId", setting.subjectId)
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        var offset = debit(paid.account(), "OFFSET", "10.00");
        offset.setItems(
                List.of(
                        new CashierInput.Item(
                                bill.getId(), bill.getVersion(), new BigDecimal("10.00"))));
        requests.add(request(offset));
        periods.close(project, DATE).contextWrite(fixture.allowed()).block(WAIT);
        for (var request : requests)
            FinancialPeriodTestSupport.rejectsClosed(
                    service.execute(project, request, DATE).contextWrite(fixture.allowed()));
        assertEquals(
                paid.entry().getId(),
                service.execute(project, original, DATE)
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .entry()
                        .getId());
        assertEquals(1, entries.createQuery().where("projectId", project).count().block(WAIT));
        money("100.00", accounts.findById(paid.account().getId()).block(WAIT).getBalance());
        money("0.00", bills.findById(bill.getId()).block(WAIT).getPaidAmount());
    }

    @Test
    void collectRefundAndTransferKeepImmutableCashAndBalance() {
        var a = run(input("TOPUP", "100.01")).account();
        money("100.01", a.getBalance());
        a = run(input("TOPUP", "20.00")).account();
        money("120.01", a.getBalance());
        a = run(debit(a, "REFUND", "10.01")).account();
        money("110.00", a.getBalance());
        var transfer = debit(a, "TRANSFER", "30.00");
        transfer.setTargetSpaceId("other-room");
        transfer.setTargetRelationshipId("link");
        transfer.setTargetStandardId(setting.standardId);
        transfer.setTargetType("ADVANCE");
        var preview =
                service.preview(project, transfer, DATE)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        var result = run(transfer);
        assertNotNull(preview.targetFullName());
        assertEquals(result.target().getFullName(), preview.targetFullName());
        assertEquals(result.target().getCustomerName(), preview.targetCustomerName());
        assertEquals(result.target().getStandardName(), preview.targetStandardName());
        assertEquals("30.00", preview.targetBalance());
        money("80.00", result.account().getBalance());
        money("30.00", result.target().getBalance());
        money(
                "110.00",
                accounts.createQuery()
                        .where("projectId", project)
                        .fetch()
                        .map(FundAccountEntity::getBalance)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .block(WAIT));
        money(
                "110.00",
                entries.createQuery()
                        .where("projectId", project)
                        .fetch()
                        .map(FundEntryEntity::getCashAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .block(WAIT));
        assertEquals(5, entries.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void sameRequestRetriesOnceAndChangedPayloadIsRejected() {
        var i = input("TOPUP", "9007199254740993.01");
        var r = request(i);
        var a = service.execute(project, r, DATE).contextWrite(fixture.allowed()).block(WAIT);
        var b = service.execute(project, r, DATE).contextWrite(fixture.allowed()).block(WAIT);
        assertEquals(a.entry().getId(), b.entry().getId());
        money("9007199254740993.01", b.account().getBalance());
        i.setAmount(new BigDecimal("2.00"));
        rejects(service.execute(project, r, DATE), 409);
        assertEquals(1, entries.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void cannotOverRefundUseStalePreviewOrTransferAcrossProjects() {
        var a = run(input("TOPUP", "10.00")).account();
        rejects(service.preview(project, debit(a, "REFUND", "10.01"), DATE), 409);
        var old = request(debit(a, "REFUND", "5.00"));
        run(input("TOPUP", "1.00"));
        rejects(service.execute(project, old, DATE), 409);
        rejects(service.detail("another", a.getId()).contextWrite(fixture.allowed()), 404);
        assertEquals(2, entries.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void offsetUsesOneLedgerAndDoesNotCreateAnotherCashReceipt() {
        var a = run(input("TOPUP", "80.00")).account();
        var bill = fixture.bill("offset");
        bill.setSubjectId(setting.subjectId);
        bills.createUpdate()
                .set("subjectId", setting.subjectId)
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        var i = debit(a, "OFFSET", "30.01");
        i.setItems(
                List.of(
                        new CashierInput.Item(
                                bill.getId(), bill.getVersion(), new BigDecimal("30.01"))));
        var r = run(i);
        money("49.99", r.account().getBalance());
        var actual = bills.findById(bill.getId()).block(WAIT);
        money("30.01", actual.getPaidAmount());
        money("69.99", actual.getArrears());
        var p = payments.findById(r.entry().getPaymentId()).block(WAIT);
        money("30.01", p.getAmount());
        assertEquals(
                "ADVANCE",
                paymentLines
                        .createQuery()
                        .where("paymentId", p.getId())
                        .fetchOne()
                        .block(WAIT)
                        .getMethod());
        money(
                "80.00",
                entries.createQuery()
                        .where("projectId", project)
                        .fetch()
                        .map(FundEntryEntity::getCashAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .block(WAIT));
    }

    @Test
    void twoConcurrentDebitsCannotSpendTheSameBalance() {
        var a = run(input("TOPUP", "10.00")).account();
        var one = request(debit(a, "REFUND", "8.00"));
        var two = request(debit(a, "REFUND", "8.00"));
        var results =
                Flux.mergeDelayError(
                                2,
                                service.execute(project, one, DATE)
                                        .contextWrite(fixture.allowed())
                                        .map(v -> true)
                                        .onErrorReturn(false),
                                service.execute(project, two, DATE)
                                        .contextWrite(fixture.allowed())
                                        .map(v -> true)
                                        .onErrorReturn(false))
                        .collectList()
                        .block(WAIT);
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        money("2.00", accounts.findById(a.getId()).block(WAIT).getBalance());
    }

    @Test
    void permissionFailureRejectsWithoutAnyBalanceOrFlow() {
        StepVerifier.create(service.preview(project, input("TOPUP", "1.00"), DATE))
                .expectError(
                        org.hswebframework.web.authorization.exception.UnAuthorizedException.class)
                .verify(WAIT);
        assertEquals(0, entries.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void transferLastWriteFailureRollsBackSourceBalanceAndFlow() throws Exception {
        var a = run(input("TOPUP", "10.00")).account();
        var i = debit(a, "TRANSFER", "4.00");
        i.setTargetSpaceId("other-room");
        i.setTargetRelationshipId("link");
        i.setTargetStandardId(setting.standardId);
        i.setTargetType("ADVANCE");
        var req = request(i);
        var constraint = "fund_test_" + UUID.randomUUID().toString().replace("-", "");
        var db = HouseSettingPersistenceTest.DB;
        assertEquals(
                0,
                db.execInContainer(
                                "psql",
                                "-U",
                                "postgres",
                                "-d",
                                "setting_test",
                                "-v",
                                "ON_ERROR_STOP=1",
                                "-c",
                                "ALTER TABLE pm_fin_fund_entry ADD CONSTRAINT "
                                        + constraint
                                        + " CHECK (action <> 'TRANSFER_IN') NOT VALID")
                        .getExitCode());
        try {
            StepVerifier.create(service.execute(project, req, DATE).contextWrite(fixture.allowed()))
                    .expectError()
                    .verify(WAIT);
            money("10.00", accounts.findById(a.getId()).block(WAIT).getBalance());
            assertEquals(1, entries.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(1, accounts.createQuery().where("projectId", project).count().block(WAIT));
        } finally {
            db.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "setting_test",
                    "-c",
                    "ALTER TABLE pm_fin_fund_entry DROP CONSTRAINT " + constraint);
        }
    }

    @Test
    void cashRefundCannotReleaseInvoicedBalanceAndDepositDeductionIsNotCash() {
        var a = run(input("TOPUP", "10.00")).account();
        accounts.createUpdate()
                .set("invoiceOccupiedAmount", new BigDecimal("1.00"))
                .where("id", a.getId())
                .execute()
                .block(WAIT);
        rejects(service.preview(project, debit(a, "REFUND", "1.00"), DATE), 409);
        var d = input("TOPUP", "12.00");
        d.setType("DEPOSIT");
        var deposit = run(d).account();
        var deduction = debit(deposit, "DEDUCT", "2.00");
        deduction.setType("DEPOSIT");
        deduction.setTargetStandardId(setting.standardId);
        var result = run(deduction);
        money("10.00", result.account().getBalance());
        money("0.00", result.entry().getCashAmount());
    }

    @Test
    void zeroBalanceCanUseNewStandardWithoutRewritingOldEntries() {
        var original = run(input("TOPUP", "10.00")).account();
        run(debit(original, "REFUND", "10.00"));
        var next = new StandardEntity();
        org.springframework.beans.BeanUtils.copyProperties(
                standards.findById(setting.standardId).block(WAIT), next);
        next.setId(UUID.randomUUID().toString());
        next.setStandardCode("NEW-" + next.getId());
        next.setCodeKey(next.getStandardCode());
        next.setStandardName("新版收费标准");
        next.setNameKey("新版收费标准");
        standards.insert(next).block(WAIT);
        var topup = input("TOPUP", "2.00");
        topup.setStandardId(next.getId());
        var result = run(topup);
        assertEquals(next.getId(), result.account().getStandardId());
        assertEquals(next.getId(), accounts.findById(original.getId()).block(WAIT).getStandardId());
        assertEquals("新版收费标准", result.entry().getStandardName());
        assertEquals(
                2,
                entries.createQuery()
                        .where("accountId", original.getId())
                        .and("standardName", original.getStandardName())
                        .count()
                        .block(WAIT));
    }

    @Test
    void httpReturnsExactMoneyHidesInternalSnapshotAndScopesOrQueries() {
        var controller =
                new org.jetlinks.pro.property.finance.web.FundsController(
                        mock(ReceivableService.class),
                        service,
                        mock(HouseSettingService.class),
                        mock(CashierService.class),
                        new BillingJobProperties());
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                controller)
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange).contextWrite(fixture.allowed()))
                        .build();
        var path = "/property/finance/projects/" + project + "/funds";
        var input = input("TOPUP", "9007199254740993.01");
        var preview =
                client.post()
                        .uri(path + "/topup/_preview")
                        .bodyValue(input)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(FundsService.Preview.class)
                        .returnResult()
                        .getResponseBody();
        var request =
                new FundsService.Submission(UUID.randomUUID().toString(), preview.token(), input);
        var response =
                client.post()
                        .uri(path + "/topup/_confirm")
                        .bodyValue(request)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("$.account.balance")
                        .isEqualTo("9007199254740993.01")
                        .jsonPath("$.entry.amount")
                        .isEqualTo("9007199254740993.01")
                        .jsonPath("$.entry.requestFingerprint")
                        .doesNotExist()
                        .jsonPath("$.entry.inputSnapshotJson")
                        .doesNotExist()
                        .returnResult();
        assertNotNull(response.getResponseBody());
        client.post()
                .uri(path + "/_query")
                .bodyValue(
                        Map.of(
                                "terms",
                                List.of(
                                        Map.of(
                                                "column",
                                                "projectId",
                                                "value",
                                                "foreign",
                                                "type",
                                                "or"))))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(0);
        input.setAction("REFUND");
        client.post()
                .uri(path + "/topup/_preview")
                .bodyValue(input)
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Configuration
    @Import({
        CashierPersistenceTest.Config.class,
        FundsService.class,
        FundSourceService.class,
        CollectionStandardSource.class,
        FundLedger.class,
        FundCreditLedger.class,
        FundOffsetService.class
    })
    static class Config {}
}
