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

/** 来源账验证曾用后补足不能撤销，同时不误扣同账户的其他存入。 */
@SpringBootTest(
        classes = FundRevokePersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FundRevokePersistenceTest {
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        HouseSettingPersistenceTest.database(r);
    }

    @Autowired FundRevokeService service;
    @Autowired FundsService funds;
    @Autowired CashierConfiguration configuration;
    @Autowired HouseArchiveSource archive;
    @Autowired ReactiveRepository<FundAccountEntity, String> accounts;
    @Autowired ReactiveRepository<FundEntryEntity, String> entries;
    @Autowired ReactiveRepository<FundCreditEntity, String> credits;
    @Autowired ReactiveRepository<FundConsumptionEntity, String> consumptions;
    @Autowired ReactiveRepository<StandardEntity, String> standards;
    @Autowired ReactiveRepository<ProjectSubjectEntity, String> issued;
    @Autowired ReactiveRepository<SubjectEntity, String> subjects;
    @Autowired ReactiveRepository<ReceivableEntity, String> bills;
    FundsPersistenceTest fixture;
    String project, accountId, entryId;

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
        var result = fixture.run(fixture.input("TOPUP", "100.01"));
        accountId = result.account().getId();
        entryId = result.entry().getId();
    }

    FundRevokeInput input() {
        return new FundRevokeInput(
                entryId,
                accounts.findById(accountId).block(WAIT).getVersion(),
                "2024-02-15T13:00:00",
                "撤销误收预存",
                null,
                null);
    }

    FundRevokeService.Submission request(FundRevokeInput i) {
        var p =
                service.preview(project, i, DATE)
                        .contextWrite(fixture.fixture.allowed())
                        .block(WAIT);
        return new FundRevokeService.Submission(UUID.randomUUID().toString(), p.token(), i);
    }

    FundsService.Result confirm(FundRevokeService.Submission r) {
        return service.confirm(project, r, DATE)
                .contextWrite(fixture.fixture.allowed())
                .block(WAIT);
    }

    void money(String expected, BigDecimal actual) {
        assertEquals(new BigDecimal(expected), actual);
    }

    FundCreditEntity credit(String entry) {
        return credits.findById(entry).block(WAIT);
    }

    @Test
    void untouchedTopupIsReversedInFullWithImmutableOriginalAndExactSourceConsumption() {
        var r = request(input());
        var result = confirm(r);
        money("0.00", result.account().getBalance());
        money("-100.01", result.entry().getCashAmount());
        money("-100.01", result.entry().getAmount());
        assertEquals("REVOKE_TOPUP", result.entry().getAction());
        assertEquals(entryId, result.entry().getSourceEntryId());
        money("100.01", entries.findById(entryId).block(WAIT).getAmount());
        money("0.00", credit(entryId).getRemainingAmount());
        assertEquals(result.entry().getId(), confirm(r).entry().getId());
        fixture.rejects(service.preview(project, input(), DATE), 409);
        money(
                "0.00",
                entries.createQuery()
                        .where("accountId", accountId)
                        .fetch()
                        .map(FundEntryEntity::getCashAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .block(WAIT));
    }

    @Test
    void fifoConsumptionThenReplenishmentDoesNotReviveOriginalTopup() {
        var second = fixture.run(fixture.input("TOPUP", "20.00"));
        fixture.run(fixture.debit(second.account(), "REFUND", "110.01"));
        money("0.00", credit(entryId).getRemainingAmount());
        money("10.00", credit(second.entry().getId()).getRemainingAmount());
        var newest = fixture.run(fixture.input("TOPUP", "100.01"));
        fixture.rejects(service.preview(project, input(), DATE), 409);
        entryId = second.entry().getId();
        fixture.rejects(service.preview(project, input(), DATE), 409);
        entryId = newest.entry().getId();
        var reversal = confirm(request(input()));
        money("10.00", reversal.account().getBalance());
        money("10.00", credit(second.entry().getId()).getRemainingAmount());
    }

    @Test
    void selectingSecondTopupConsumesOnlySecondAndKeepsEarlierSourceIntact() {
        var second = fixture.run(fixture.input("TOPUP", "20.00"));
        var first = entryId;
        entryId = second.entry().getId();
        confirm(request(input()));
        money("100.01", credit(first).getRemainingAmount());
        money("0.00", credit(entryId).getRemainingAmount());
        var use = consumptions.createQuery().where("creditId", entryId).fetchOne().block(WAIT);
        money("20.00", use.getAmount());
    }

    @Test
    void invoiceDayClosingMonthClosingAndDepositCannotBeRevoked() {
        credits.createUpdate()
                .set("invoiceOccupiedAmount", new BigDecimal("0.01"))
                .where("id", entryId)
                .execute()
                .block(WAIT);
        fixture.rejects(service.preview(project, input(), DATE), 409);
        credits.createUpdate()
                .set("invoiceOccupiedAmount", BigDecimal.ZERO)
                .set("dayClosed", true)
                .where("id", entryId)
                .execute()
                .block(WAIT);
        fixture.rejects(service.preview(project, input(), DATE), 409);
        credits.createUpdate()
                .set("dayClosed", false)
                .set("monthClosed", true)
                .where("id", entryId)
                .execute()
                .block(WAIT);
        fixture.rejects(service.preview(project, input(), DATE), 409);
        var deposit = fixture.input("TOPUP", "1.00");
        deposit.setType("DEPOSIT");
        var d = fixture.run(deposit);
        accountId = d.account().getId();
        entryId = d.entry().getId();
        fixture.rejects(service.preview(project, input(), DATE), 409);
    }

    @Test
    void concurrentAndStaleRequestsCannotReverseSameSourceTwice() {
        var a = request(input());
        var b = request(input());
        var results =
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
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(
                1, consumptions.createQuery().where("accountId", accountId).count().block(WAIT));
        money("0.00", accounts.findById(accountId).block(WAIT).getBalance());
    }

    @Test
    void laterLedgerFailureRollsBackBalanceCreditAndConsumption() throws Exception {
        var r = request(input());
        var constraint = "revoke_" + UUID.randomUUID().toString().replace("-", "");
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
                                        + constraint
                                        + " CHECK (project_id <> '"
                                        + project
                                        + "' OR action <> 'REVOKE_TOPUP')")
                        .getExitCode());
        try {
            reactor.test.StepVerifier.create(
                            service.confirm(project, r, DATE)
                                    .contextWrite(fixture.fixture.allowed()))
                    .expectError()
                    .verify(WAIT);
            money("100.01", accounts.findById(accountId).block(WAIT).getBalance());
            money("100.01", credit(entryId).getRemainingAmount());
            assertEquals(
                    0,
                    consumptions.createQuery().where("accountId", accountId).count().block(WAIT));
        } finally {
            HouseSettingPersistenceTest.DB.execInContainer(
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
    void moreThanOneBatchSourcesAreConsumedInOrderWithoutTruncation() {
        // 129笔不同日期的真实来源覆盖第二批，避免“只消费前128笔”留下不平账。
        var sources = new ArrayList<FundCreditEntity>();
        var original = credit(entryId);
        original.setCreateTime(0L);
        credits.createUpdate().set("createTime", 0L).where("id", entryId).execute().block(WAIT);
        for (int n = 0; n < 129; n++) {
            var row = new FundCreditEntity();
            row.setId(UUID.randomUUID().toString());
            StandardPersistenceTest.initialize(row);
            row.setCreateTime((long) n + 1);
            row.setProjectId(project);
            row.setAccountId(accountId);
            row.setEntryId(row.getId());
            row.setOriginalAmount(new BigDecimal("0.01"));
            row.setRemainingAmount(new BigDecimal("0.01"));
            row.setInvoiceOccupiedAmount(BigDecimal.ZERO);
            row.setDayClosed(false);
            row.setMonthClosed(false);
            sources.add(row);
        }
        credits.insertBatch(Mono.just(sources)).block(WAIT);
        accounts.createUpdate()
                .set("balance", new BigDecimal("101.30"))
                .where("id", accountId)
                .execute()
                .block(WAIT);
        var account = accounts.findById(accountId).block(WAIT);
        var result = fixture.run(fixture.debit(account, "REFUND", "101.30"));
        money("0.00", result.account().getBalance());
        assertEquals(
                130, consumptions.createQuery().where("accountId", accountId).count().block(WAIT));
        money(
                "101.30",
                consumptions
                        .createQuery()
                        .where("accountId", accountId)
                        .fetch()
                        .map(FundConsumptionEntity::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .block(WAIT));
        money(
                "0.00",
                credits.createQuery()
                        .where("accountId", accountId)
                        .fetch()
                        .map(FundCreditEntity::getRemainingAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .block(WAIT));
    }

    @Test
    void originalWayAndNamesAreFrozenAndCurrentCollectionConfigIsNotRequired() {
        var original = entries.findById(entryId).block(WAIT);
        accounts.createUpdate()
                .set("customerName", "当前名称")
                .set("standardName", "当前标准")
                .where("id", accountId)
                .execute()
                .block(WAIT);
        org.mockito.Mockito.doReturn(
                        Mono.error(FinanceScope.error("cashier_configuration_limit", 409)))
                .when(configuration)
                .resolve(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyBoolean());
        var result = confirm(request(input()));
        assertEquals(original.getMethod(), result.entry().getMethod());
        assertEquals(original.getCustomerName(), result.entry().getCustomerName());
        assertEquals(original.getStandardName(), result.entry().getStandardName());
        assertEquals("当前标准", result.account().getStandardName());
        assertEquals("当前名称", accounts.findById(accountId).block(WAIT).getCustomerName());
    }

    @Test
    void stalePreviewChangedRequestWrongProjectAndMissingSpacePermissionsReject() {
        var old = request(input());
        fixture.run(fixture.input("TOPUP", "1.00"));
        fixture.rejects(service.confirm(project, old, DATE), 409);
        fixture.rejects(service.preview("foreign", input(), DATE), 404);
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
                                call.<Collection<String>>getArgument(1).contains("room")
                                        ? Mono.error(FinanceScope.error("permission_denied", 403))
                                        : Mono.empty());
        reactor.test.StepVerifier.create(
                        service.preview(project, input(), DATE).contextWrite(denied))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        var i = input();
        var r = request(i);
        confirm(r);
        fixture.rejects(
                service.confirm(
                        project,
                        new FundRevokeService.Submission(
                                r.requestId(),
                                r.token(),
                                new FundRevokeInput(
                                        i.sourceEntryId(),
                                        i.accountVersion(),
                                        i.occurredAt(),
                                        "改原因",
                                        null,
                                        null)),
                        DATE),
                409);
    }

    @Test
    void httpUsesFixedOriginalAmountAndNeverExposesGenericMutationOrInternalFingerprint() {
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web.FundRevokeController(
                                        service, new BillingJobProperties()))
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter((e, c) -> c.filter(e).contextWrite(fixture.fixture.allowed()))
                        .build();
        var path = "/property/finance/projects/" + project + "/funds/topup-revocations";
        var i = input();
        var p =
                client.post()
                        .uri(path + "/_preview")
                        .bodyValue(i)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(FundRevokeService.Preview.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(p);
        assertEquals("100.01", p.amount());
        assertEquals("-100.01", p.cashAmount());
        client.post()
                .uri(path + "/_confirm")
                .bodyValue(
                        new FundRevokeService.Submission(
                                UUID.randomUUID().toString(), p.token(), i))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.account.balance")
                .isEqualTo("0.00")
                .jsonPath("$.entry.cashAmount")
                .isEqualTo("-100.01")
                .jsonPath("$.entry.sourceEntryId")
                .isEqualTo(entryId)
                .jsonPath("$.entry.requestFingerprint")
                .doesNotExist()
                .jsonPath("$.entry.inputSnapshotJson")
                .doesNotExist();
        client.delete().uri(path + "/any").exchange().expectStatus().is4xxClientError();
    }

    @Configuration
    @Import({FundsPersistenceTest.Config.class, FundRevokeService.class})
    static class Config {}
}
