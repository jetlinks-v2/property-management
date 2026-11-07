package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.fastjson.JSON;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.carry.*;
import org.jetlinks.pro.property.finance.entity.*;
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

/** 真实PG保证自动结转的减免、资金、应收、消费来源和执行结果不可部分成功。 */
@SpringBootTest(
        classes = CarryForwardApplicationPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CarryForwardApplicationPersistenceTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        HouseSettingPersistenceTest.database(r);
    }

    @Autowired CarryForwardApplyService service;
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
    @Autowired ReactiveRepository<PaymentEntity, String> payments;
    @Autowired ReactiveRepository<ReceivableLogEntity, String> logs;
    @Autowired ReactiveRepository<CarryForwardPlanEntity, String> plans;
    @Autowired ReactiveRepository<CarryForwardItemEntity, String> items;
    @Autowired ReactiveRepository<CarryForwardRemissionEntity, String> remissions;
    FundsPersistenceTest fixture;
    CarryForwardPlanEntity plan;
    CarryForwardPlanInput input;
    ReceivableEntity bill;
    String project;

    @BeforeEach
    void setup() {
        fixture = new FundsPersistenceTest();
        fixture.service = funds;
        fixture.accounts = accounts;
        fixture.entries = entries;
        fixture.standards = standards;
        fixture.issued = issued;
        fixture.subjects = subjects;
        fixture.bills = bills;
        fixture.payments = payments;
        fixture.configuration = configuration;
        fixture.archive = archive;
        fixture.setup();
        project = fixture.project;
        bill = fixture.fixture.bill("carry");
        bill.setSubjectId(fixture.setting.subjectId);
        bill.setStandardId(fixture.setting.standardId);
        var snapshot = JSON.parseObject(bill.getSnapshotJson());
        snapshot.getJSONObject("standard").put("companyId", "company");
        bill.setSnapshotJson(snapshot.toJSONString());
        bills.createUpdate()
                .set("subjectId", bill.getSubjectId())
                .set("standardId", bill.getStandardId())
                .set("snapshotJson", bill.getSnapshotJson())
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        input = new CarryForwardPlanInput();
        input.setName("物业费自动结转");
        input.setSubjectIds(List.of(bill.getSubjectId()));
        input.setStandardIds(List.of(bill.getStandardId()));
        input.setPeriodStart("2024-01-01");
        input.setPeriodEnd("2024-12-31");
        input.setFrequency("DAILY");
        input.setExecuteAt("13:00:00");
        input.setZone("Asia/Shanghai");
        input.setRemissionMode("FIXED");
        input.setRemissionValue(new BigDecimal("10.00"));
        plan = new CarryForwardPlanEntity();
        plan.setId(UUID.randomUUID().toString());
        StandardPersistenceTest.initialize(plan);
        plan.setProjectId(project);
        plan.setName(input.getName());
        plan.setEnabled(true);
        plan.setConfigJson(JSON.toJSONString(input));
        plans.insert(plan).block(WAIT);
    }

    CarryForwardApplyService.Request request(String account) {
        return new CarryForwardApplyService.Request(
                plan.getId(),
                plan.getVersion(),
                UUID.randomUUID().toString(),
                bill.getId(),
                account,
                "2024-02-15T13:00:00");
    }

    CarryForwardItemEntity run(CarryForwardApplyService.Request r) {
        return service.apply(project, r, DATE).contextWrite(fixture.fixture.allowed()).block(WAIT);
    }

    FundAccountEntity topup(String amount) {
        return fixture.run(fixture.input("TOPUP", amount)).account();
    }

    void money(String expected, BigDecimal actual) {
        assertEquals(new BigDecimal(expected), actual);
    }

    ReceivableEntity currentBill() {
        return bills.findById(bill.getId()).block(WAIT);
    }

    @Autowired FinancialPeriodTestSupport periods;

    @Test
    void anotherStandardCannotConsumeBalanceOrApplyRemission() {
        var account = topup("150.00");
        String otherStandard = UUID.randomUUID().toString();
        accounts.createUpdate().set("standardId", otherStandard)
                .where("id", account.getId()).execute().block(WAIT);
        fixture.rejects(service.apply(project, request(account.getId()), DATE), 409);
        money("150.00", accounts.findById(account.getId()).block(WAIT).getBalance());
        money("100.00", currentBill().getArrears());
        money("0.00", currentBill().getFeeReductionAmount());
        assertEquals(0, items.createQuery().where("planId", plan.getId()).count().block(WAIT));
        assertEquals(0, remissions.createQuery().where("planId", plan.getId()).count().block(WAIT));
        assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void closedPeriodAlsoBlocksRemissionWithoutAFundAccount() {
        input.setRemissionValue(new BigDecimal("200.00"));
        plans.createUpdate()
                .set("configJson", JSON.toJSONString(input))
                .where("id", plan.getId())
                .execute()
                .block(WAIT);
        periods.close(project, DATE).contextWrite(fixture.fixture.allowed()).block(WAIT);
        FinancialPeriodTestSupport.rejectsClosed(
                service.apply(project, request(null), DATE)
                        .contextWrite(fixture.fixture.allowed()));
        money("0.00", currentBill().getFeeReductionAmount());
        assertEquals(0, items.createQuery().where("planId", plan.getId()).count().block(WAIT));
        assertEquals(0, remissions.createQuery().where("planId", plan.getId()).count().block(WAIT));
    }

    @Test
    void completedCarryRecoversAfterClosingButNewRunCannotPost() {
        var account = topup("30.00");
        var request = request(account.getId());
        var result = run(request);
        periods.close(project, DATE).contextWrite(fixture.fixture.allowed()).block(WAIT);
        assertEquals(result.getId(), run(request).getId());
        FinancialPeriodTestSupport.rejectsClosed(
                service.apply(project, request(account.getId()), DATE)
                        .contextWrite(fixture.fixture.allowed()));
        money("30.00", currentBill().getPaidAmount());
        money("10.00", currentBill().getFeeReductionAmount());
        assertEquals(1, items.createQuery().where("planId", plan.getId()).count().block(WAIT));
    }

    @Test
    void partialCarryThenReplenishmentDoesNotApplyDiscountTwiceAndReplayDoesNotCollectAgain() {
        var a = topup("30.00");
        var first = request(a.getId());
        var result = run(first);
        money("30.00", result.getAmount());
        money("10.00", result.getFeeReduction());
        money("60.00", currentBill().getArrears());
        assertEquals(result.getId(), run(first).getId());
        topup("100.00");
        var next = run(request(a.getId()));
        money("60.00", next.getAmount());
        money("0.00", next.getFeeReduction());
        money("0.00", currentBill().getArrears());
        money("90.00", currentBill().getPaidAmount());
        money("10.00", currentBill().getFeeReductionAmount());
        money("40.00", accounts.findById(a.getId()).block(WAIT).getBalance());
        assertEquals(1, remissions.createQuery().where("planId", plan.getId()).count().block(WAIT));
        assertEquals(2, payments.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(2, items.createQuery().where("planId", plan.getId()).count().block(WAIT));
        money(
                "130.00",
                entries.createQuery()
                        .where("projectId", project)
                        .fetch()
                        .map(FundEntryEntity::getCashAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .block(WAIT));
    }

    @Test
    void fullRemissionWithoutAccountCreatesNoFictitiousPaymentOrCash() {
        input.setRemissionValue(new BigDecimal("200.00"));
        plans.createUpdate()
                .set("configJson", JSON.toJSONString(input))
                .where("id", plan.getId())
                .execute()
                .block(WAIT);
        var r = run(request(null));
        money("0.00", r.getAmount());
        money("100.00", r.getFeeReduction());
        money("0.00", currentBill().getArrears());
        money("0.00", currentBill().getPaidAmount());
        assertEquals("SUCCEEDED", r.getStatus());
        assertNull(r.getPaymentId());
        assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, entries.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void noMoneyDoesNotGrantAnIsolatedPartialRemission() {
        var r = run(request(null));
        assertEquals("SKIPPED", r.getStatus());
        money("0.00", r.getFeeReduction());
        money("100.00", currentBill().getArrears());
        assertEquals(0, remissions.createQuery().where("planId", plan.getId()).count().block(WAIT));
    }

    @Test
    void lastItemInsertFailureRollsBackAllFinancialWritesAndRemissionMarker() throws Exception {
        var a = topup("100.00");
        var r = request(a.getId());
        var constraint = "carry_item_" + UUID.randomUUID().toString().replace("-", "");
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
                                "ALTER TABLE pm_fin_carry_item ADD CONSTRAINT "
                                        + constraint
                                        + " CHECK (project_id <> '"
                                        + project
                                        + "')")
                        .getExitCode());
        try {
            StepVerifier.create(
                            service.apply(project, r, DATE).contextWrite(fixture.fixture.allowed()))
                    .expectErrorMatches(failure -> causedBy(failure, constraint))
                    .verify(WAIT);
            money("100.00", currentBill().getArrears());
            money("0.00", currentBill().getPaidAmount());
            money("0.00", currentBill().getFeeReductionAmount());
            money("100.00", accounts.findById(a.getId()).block(WAIT).getBalance());
            money(
                    "100.00",
                    credits.createQuery()
                            .where("accountId", a.getId())
                            .fetchOne()
                            .block(WAIT)
                            .getRemainingAmount());
            assertEquals(
                    0,
                    consumptions.createQuery().where("accountId", a.getId()).count().block(WAIT));
            assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(
                    0, remissions.createQuery().where("planId", plan.getId()).count().block(WAIT));
            assertEquals(
                    0, logs.createQuery().where("receivableId", bill.getId()).count().block(WAIT));
        } finally {
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
                                    "ALTER TABLE pm_fin_carry_item DROP CONSTRAINT " + constraint)
                            .getExitCode());
        }
    }

    boolean causedBy(Throwable failure, String text) {
        for (var cause = failure; cause != null; cause = cause.getCause())
            if (Objects.toString(cause.getMessage(), "").contains(text)) return true;
        return false;
    }

    @Test
    void concurrentSameSlotPostsOnceAndChangedPayloadCannotReuseItsIdentity() {
        var a = topup("100.00");
        var r = request(a.getId());
        var results =
                Flux.merge(
                                service.apply(project, r, DATE)
                                        .contextWrite(fixture.fixture.allowed()),
                                service.apply(project, r, DATE)
                                        .contextWrite(fixture.fixture.allowed()))
                        .collectList()
                        .block(WAIT);
        assertEquals(results.get(0).getId(), results.get(1).getId());
        money("90.00", currentBill().getPaidAmount());
        money("10.00", accounts.findById(a.getId()).block(WAIT).getBalance());
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
        var altered =
                new CarryForwardApplyService.Request(
                        r.planId(),
                        r.planVersion(),
                        r.runId(),
                        r.receivableId(),
                        r.accountId(),
                        "2024-02-15T14:00:00");
        fixture.rejects(service.apply(project, altered, DATE), 409);
    }

    @Test
    void disabledPlanOldVersionWrongProjectAndWrongSpaceAreRejectedBeforePosting() {
        var a = topup("100.00");
        var r = request(a.getId());
        plans.createUpdate().set("enabled", false).where("id", plan.getId()).execute().block(WAIT);
        fixture.rejects(service.apply(project, r, DATE), 409);
        plans.createUpdate()
                .set("enabled", true)
                .set("version", plan.getVersion() + 1)
                .where("id", plan.getId())
                .execute()
                .block(WAIT);
        fixture.rejects(service.apply(project, r, DATE), 409);
        plans.createUpdate()
                .set("version", plan.getVersion())
                .where("id", plan.getId())
                .execute()
                .block(WAIT);
        fixture.rejects(service.apply("foreign", r, DATE), 404);
        accounts.createUpdate()
                .set("spaceId", "other-room")
                .where("id", a.getId())
                .execute()
                .block(WAIT);
        fixture.rejects(service.apply(project, r, DATE), 409);
        money("0.00", currentBill().getFeeReductionAmount());
        assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
    }

    void percentageLateFee(boolean include, String remission) {
        var snapshot = JSON.parseObject(currentBill().getSnapshotJson());
        var standard = snapshot.getJSONObject("standard");
        standard.put("isDelay", 1);
        standard.put("delayCalcForm", 1);
        standard.put("delayRoundType", 0);
        standard.put("delayBaseType", "CURRENT_RECEIVABLE");
        standard.put("rateAmount", new BigDecimal("10"));
        bills.createUpdate()
                .set("snapshotJson", snapshot.toJSONString())
                .set("dueDate", "2024-02-14")
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        input.setIncludeLateFee(include);
        input.setRemissionValue(new BigDecimal(remission));
        plans.createUpdate()
                .set("configJson", JSON.toJSONString(input))
                .where("id", plan.getId())
                .execute()
                .block(WAIT);
    }

    @Test
    void principalRemissionRecalculatesPercentageLateFeeBeforeChoosingActualOffsetAmount() {
        percentageLateFee(true, "10.00");
        var a = topup("110.00");
        var r = run(request(a.getId()));
        money("99.00", r.getAmount());
        money("10.00", r.getFeeReduction());
        money("9.00", currentBill().getFinalLateFeeAmount());
        money("0.00", currentBill().getArrears());
        money("11.00", accounts.findById(a.getId()).block(WAIT).getBalance());
    }

    @Test
    void excludingLateFeeLeavesItsActualBalanceUncollected() {
        percentageLateFee(false, "10.00");
        var a = topup("110.00");
        var r = run(request(a.getId()));
        money("90.00", r.getAmount());
        money("9.00", currentBill().getArrears());
        money("20.00", accounts.findById(a.getId()).block(WAIT).getBalance());
    }

    @Test
    void fullRemissionDoesNotTryToWaivePercentageLateFeeAlreadyRemovedByReducedPrincipal() {
        percentageLateFee(true, "200.00");
        var r = run(request(null));
        money("0.00", r.getAmount());
        money("100.00", r.getFeeReduction());
        money("0.00", r.getLateFeeReduction());
        money("0.00", currentBill().getArrears());
        money("0.00", currentBill().getFinalLateFeeAmount());
        assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void replayStillRequiresCurrentSpacePermission() {
        var a = topup("100.00");
        var r = request(a.getId());
        run(r);
        var holder = org.mockito.Mockito.mock(org.jetlinks.pro.assets.AssetsHolder.class);
        org.mockito.Mockito.when(
                        holder.assertPermission(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyCollection(),
                                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        call ->
                                "space".equals(call.getArgument(0))
                                        ? Mono.error(FinanceScope.error("permission_denied", 403))
                                        : Mono.empty());
        StepVerifier.create(
                        service.apply(project, r, DATE)
                                .contextWrite(
                                        fixture.fixture
                                                .allowed()
                                                .put(
                                                        org.jetlinks.pro.assets.AssetsHolder.class,
                                                        holder)))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void completedReplaySurvivesPlanDisableButNewRunCannotPost() {
        var a = topup("100.00");
        var r = request(a.getId());
        var result = run(r);
        plans.createUpdate().set("enabled", false).where("id", plan.getId()).execute().block(WAIT);
        assertEquals(result.getId(), run(r).getId());
        fixture.rejects(service.apply(project, request(a.getId()), DATE), 409);
    }

    @Test
    void differentCompanyOrUnselectedCustomerCannotReceiveRemissionOrOffset() {
        var a = topup("100.00");
        accounts.createUpdate()
                .set("companyId", "other-company")
                .where("id", a.getId())
                .execute()
                .block(WAIT);
        fixture.rejects(service.apply(project, request(a.getId()), DATE), 409);
        accounts.createUpdate()
                .set("companyId", "company")
                .set("customerId", "unselected")
                .where("id", a.getId())
                .execute()
                .block(WAIT);
        fixture.rejects(service.apply(project, request(a.getId()), DATE), 409);
        money("0.00", currentBill().getFeeReductionAmount());
        assertEquals(0, remissions.createQuery().where("planId", plan.getId()).count().block(WAIT));
    }

    @Configuration
    @Import({FundsPersistenceTest.Config.class, CarryForwardApplyService.class})
    static class Config {}
}
