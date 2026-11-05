package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.finance.activity.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 实际PG事务验证预缴收款/优惠/资格统一落账，不以单独计算器通过代替入账正确。 */
@SpringBootTest(
        classes = PrepayConfirmationPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PrepayConfirmationPersistenceTest extends PrepayQuotePersistenceTest {
    @Autowired PrepayConfirmationService confirmation;
    @Autowired CashierConfiguration cashierConfiguration;
    @Autowired ReactiveRepository<PaymentAllocationEntity, String> paymentLines;
    @Autowired ReactiveRepository<PrepayConsumptionEntity, String> consumption;
    @Autowired ReactiveRepository<ReceivableLogEntity, String> logs;
    @Autowired ReceivableGenerationService generation;
    @Autowired ActivityService activityService;
    @Autowired RefundService refunds;

    @BeforeEach
    void funding() {
        reset(cashierConfiguration);
        var company = new FinanceCompanyEntity();
        company.setId("company");
        company.setCompanyName("测试物业");
        company.setVersion(1L);
        when(cashierConfiguration.resolve(eq(fixture.project), any(), anyBoolean()))
                .thenAnswer(
                        call -> {
                            CashierInput i = call.getArgument(1);
                            return Mono.just(
                                    new CashierConfiguration.Resolved(
                                            company,
                                            i.getTenders().stream()
                                                    .map(
                                                            t ->
                                                                    new CashierConfiguration
                                                                            .Funding(
                                                                            t.method(),
                                                                            "现金",
                                                                            t.recordedId(),
                                                                            null,
                                                                            null,
                                                                            null,
                                                                            1L))
                                                    .toList()));
                        });
    }

    PrepayConfirmationInput payment(String amount) {
        return new PrepayConfirmationInput(
                UUID.randomUUID().toString(),
                0L,
                "company",
                "2024-02-01T10:00:00",
                List.of(new CashierInput.Tender("1", null, new BigDecimal(amount))),
                "活动预缴",
                null,
                null);
    }

    PrepayConfirmationService.Detail confirm(PrepayQuoteService.View q, PrepayConfirmationInput p) {
        return run(confirmation.confirm(fixture.project, q.id(), p, DATE));
    }

    @Autowired FinancialPeriodTestSupport periods;

    @Test
    void closedPeriodBlocksPrepayBeforeGeneratingBillsOrConsumingBenefits() {
        var quote = create(input());
        var request = payment(quote.payAmount());
        long before = bills.createQuery().where("projectId", fixture.project).count().block(WAIT);
        run(periods.close(fixture.project, DATE));
        FinancialPeriodTestSupport.rejectsClosed(
                confirmation
                        .confirm(fixture.project, quote.id(), request, DATE)
                        .contextWrite(fixture.allowed()));
        assertEquals(
                0, payments.createQuery().where("projectId", fixture.project).count().block(WAIT));
        assertEquals(
                0,
                consumption.createQuery().where("projectId", fixture.project).count().block(WAIT));
        assertEquals(
                before,
                bills.createQuery()
                        .where("projectId", fixture.project)
                        .count()
                        .block(WAIT)
                        .longValue());
        assertEquals("OPEN", run(service.detail(fixture.project, quote.id())).status());
    }

    @Test
    void successfulPrepayRecoversAfterItsBusinessPeriodCloses() {
        var quote = create(input());
        var request = payment(quote.payAmount());
        var paid = confirm(quote, request);
        run(periods.close(fixture.project, DATE));
        assertEquals(
                paid.receipt().payment().getId(),
                confirm(quote, request).receipt().payment().getId());
        assertEquals(
                1, payments.createQuery().where("projectId", fixture.project).count().block(WAIT));
    }

    @Test
    void ordinaryPrepayCreatesFutureBillAndFullyPaysOnlyQuotedPeriods() {
        var quote = create(input());
        var result = confirm(quote, payment(quote.payAmount()));
        assertEquals("246.62", result.receipt().payment().getAmount().toPlainString());
        assertEquals("龚超", result.receipt().payment().getPayerName());
        assertEquals("user", result.receipt().payment().getPayerUserId());
        assertEquals(2, result.receipt().lines().size());
        assertEquals(3, bills.createQuery().where("settingId", settingId).count().block(WAIT));
        assertEquals(
                0,
                consumption.createQuery().where("projectId", fixture.project).count().block(WAIT));
        var rows =
                bills.createQuery()
                        .where("settingId", settingId)
                        .and("periodStart", "gte", "2024-02-01")
                        .fetch()
                        .collectList()
                        .block(WAIT);
        for (var row : rows) {
            assertEquals("123.31", row.getPaidAmount().toPlainString());
            assertEquals(0, row.getArrears().signum());
            assertEquals("CLOSED", row.getPeriodStatus());
            assertEquals("2024-02-01", row.getAccruedAsOfDate());
        }
        run(generation.generate(fixture.project, settingId, LocalDate.of(2024, 3, 15)));
        var march =
                bills.createQuery()
                        .where("settingId", settingId)
                        .and("periodStart", "2024-03-01")
                        .fetchOne()
                        .block(WAIT);
        assertEquals("123.31", march.getAccruedReceivableAmount().toPlainString());
        assertEquals(0, march.getArrears().signum());
        assertEquals("CONFIRMED", run(service.detail(fixture.project, quote.id())).status());
    }

    @Test
    void activityDiscountReceiptAndQualificationCommitTogether() {
        var activity = activity(true);
        var q = create(activityInput(activity));
        var result = confirm(q, payment(q.payAmount()));
        assertEquals(
                List.of("82.21", "82.21", "82.20"),
                result.receipt().lines().stream().map(l -> l.getAmount().toPlainString()).toList());
        var rows =
                bills.createQuery().where("settingId", settingId).fetch().collectList().block(WAIT);
        assertEquals(
                new BigDecimal("123.31"),
                rows.stream()
                        .map(ReceivableEntity::getFeeReductionAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(
                new BigDecimal("246.62"),
                rows.stream()
                        .map(ReceivableEntity::getPaidAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertTrue(rows.stream().allMatch(r -> r.getArrears().signum() == 0));
        var used =
                consumption
                        .createQuery()
                        .where("projectId", fixture.project)
                        .fetchOne()
                        .block(WAIT);
        assertEquals(activity.getId(), used.getActivityId());
        assertEquals(q.id(), used.getQuoteId());
        assertEquals(result.receipt().payment().getId(), used.getPaymentId());
        assertTrue(used.getPeriodsJson().contains("82.20"));
        assertEquals(
                3,
                logs.createQuery()
                        .where("projectId", fixture.project)
                        .and("operation", "PREPAY_COLLECT")
                        .count()
                        .block(WAIT));
    }

    @Test
    void sameConfirmationRetriesAndConcurrentSubmissionsReturnOneReceipt() {
        var q = create(activityInput(activity(false)));
        var p = payment(q.payAmount());
        var results =
                Flux.merge(
                                confirmation.confirm(fixture.project, q.id(), p, DATE),
                                confirmation.confirm(fixture.project, q.id(), p, DATE))
                        .contextWrite(fixture.allowed())
                        .collectList()
                        .block(WAIT);
        assertEquals(
                results.get(0).receipt().payment().getId(),
                results.get(1).receipt().payment().getId());
        var retried = run(confirmation.confirm(fixture.project, q.id(), p, DATE.plusDays(1)));
        assertEquals(
                results.get(0).receipt().payment().getId(), retried.receipt().payment().getId());
        assertEquals(
                1, payments.createQuery().where("projectId", fixture.project).count().block(WAIT));
        assertEquals(
                1,
                consumption.createQuery().where("projectId", fixture.project).count().block(WAIT));
        reject(confirmation.confirm(fixture.project, q.id(), payment(q.payAmount()), DATE), 409);
    }

    @Test
    void expiredVersionChangedAndAmountTamperedQuotesWriteNothing() {
        var q = create(input());
        reject(confirmation.confirm(fixture.project, q.id(), payment("0.01"), DATE), 400);
        var p = payment(q.payAmount());
        var wrong =
                new PrepayConfirmationInput(
                        p.requestId(),
                        9L,
                        p.companyId(),
                        p.paidAt(),
                        p.tenders(),
                        p.remark(),
                        null,
                        null);
        reject(confirmation.confirm(fixture.project, q.id(), wrong, DATE), 409);
        quotes.createUpdate().set("expiresAt", 1L).where("id", q.id()).execute().block(WAIT);
        reject(confirmation.confirm(fixture.project, q.id(), p, DATE), 409);
        assertEquals(
                0, payments.createQuery().where("projectId", fixture.project).count().block(WAIT));
        assertEquals(
                2, bills.createQuery().where("projectId", fixture.project).count().block(WAIT));
    }

    @Test
    void changedSourceBillAndActivityRequireANewQuote() {
        var q = create(input());
        bills.createUpdate()
                .set("version", 1L)
                .where("settingId", settingId)
                .and("periodStart", "2024-02-01")
                .execute()
                .block(WAIT);
        reject(confirmation.confirm(fixture.project, q.id(), payment(q.payAmount()), DATE), 409);
        var second = create(input());
        settings.createUpdate().set("version", 1L).where("id", settingId).execute().block(WAIT);
        reject(
                confirmation.confirm(
                        fixture.project, second.id(), payment(second.payAmount()), DATE),
                409);
        assertEquals(
                0, payments.createQuery().where("projectId", fixture.project).count().block(WAIT));
    }

    @Test
    void anotherSelectedPayerCannotClaimSameHouseActivityAgain() {
        var original = fixture.context("room");
        var second =
                new PropertyHouseCustomer(
                        "second-link",
                        "second-customer",
                        "second-user",
                        "person",
                        "张女士",
                        "123",
                        "tenant",
                        1L,
                        1L,
                        1L);
        when(archive.context(eq(fixture.project), eq("two"), anyBoolean()))
                .thenReturn(
                        Mono.just(
                                new PropertyHouseContext(
                                        fixture.context("two").house(),
                                        List.of(original.customers().get(0), second))));
        var i = fixture.input("two", "2024-01-01", "2026-12-31");
        i.setCustomerRelationshipIds(List.of("link", "second-link"));
        var setting = run(settingService.create(fixture.project, i, DATE));
        when(activitySource.spaces(fixture.project))
                .thenReturn(
                        Mono.just(
                                List.of(
                                        new PropertySpaceInfo(
                                                "two", "root", "root", "102", "园区/102", "room",
                                                "房间", true))));
        var activity = activity(false);
        var qa =
                create(
                        new PrepayQuoteInput(
                                UUID.randomUUID().toString(),
                                setting.setting().getId(),
                                "link",
                                activity.getId(),
                                2,
                                1));
        var qb =
                create(
                        new PrepayQuoteInput(
                                UUID.randomUUID().toString(),
                                setting.setting().getId(),
                                "second-link",
                                activity.getId(),
                                2,
                                1));
        confirm(qa, payment(qa.payAmount()));
        reject(confirmation.confirm(fixture.project, qb.id(), payment(qb.payAmount()), DATE), 409);
        reject(
                service.create(
                        fixture.project,
                        new PrepayQuoteInput(
                                UUID.randomUUID().toString(),
                                setting.setting().getId(),
                                "second-link",
                                activity.getId(),
                                2,
                                1),
                        DATE),
                409);
        assertEquals(
                1, payments.createQuery().where("projectId", fixture.project).count().block(WAIT));
        assertEquals(
                1,
                consumption.createQuery().where("projectId", fixture.project).count().block(WAIT));
    }

    @Test
    void failedReceiptAuditRollsBackDiscountFutureBillsPaymentAndQualification() throws Exception {
        var q = create(activityInput(activity(false)));
        String name = "reject_prepay_" + UUID.randomUUID().toString().replace("-", "");
        var db = HouseSettingPersistenceTest.DB;
        assertEquals(
                0,
                db.execInContainer(
                                "psql",
                                "-U",
                                "postgres",
                                "-d",
                                "setting_test",
                                "-c",
                                "ALTER TABLE pm_fin_receivable_log ADD CONSTRAINT "
                                        + name
                                        + " CHECK (project_id <> '"
                                        + fixture.project
                                        + "') NOT VALID")
                        .getExitCode());
        var p = payment(q.payAmount());
        try {
            reactor.test.StepVerifier.create(
                            confirmation
                                    .confirm(fixture.project, q.id(), p, DATE)
                                    .contextWrite(fixture.allowed()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(
                    2, bills.createQuery().where("projectId", fixture.project).count().block(WAIT));
            assertEquals(
                    0,
                    payments.createQuery().where("projectId", fixture.project).count().block(WAIT));
            assertEquals(
                    0,
                    consumption
                            .createQuery()
                            .where("projectId", fixture.project)
                            .count()
                            .block(WAIT));
            assertEquals("OPEN", run(service.detail(fixture.project, q.id())).status());
            assertTrue(
                    bills.createQuery()
                            .where("projectId", fixture.project)
                            .fetch()
                            .all(
                                    r ->
                                            r.getPaidAmount().signum() == 0
                                                    && r.getFeeReductionAmount().signum() == 0)
                            .block(WAIT));
        } finally {
            db.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "setting_test",
                    "-c",
                    "ALTER TABLE pm_fin_receivable_log DROP CONSTRAINT " + name);
        }
        assertNotNull(confirm(q, p).receipt().payment().getId());
    }

    @Test
    void consumedActivityCannotChangeProtectedConfigurationOrBeDeleted() {
        var activity = activity(false);
        var q = create(activityInput(activity));
        confirm(q, payment(q.payAmount()));
        reject(activityService.delete(fixture.project, activity.getId(), 0L), 409);
        var state = run(activityService.state(fixture.project, activity.getId(), 0L, false));
        assertFalse(state.activity().getEnabled());
        var input = state.config().getInput();
        input.setVersion(1L);
        input.setTiers(List.of(new ActivityInput.Tier(3, 1)));
        when(activitySource.types())
                .thenReturn(
                        Mono.just(
                                List.of(
                                        new org.jetlinks.pro.property.base.dictionary
                                                .PropertyDictionaryOption("prepay", "充值赠送"))));
        input.setActivityType("prepay");
        reject(activityService.save(fixture.project, activity.getId(), input), 409);
        assertEquals(1L, activities.findById(activity.getId()).block(WAIT).getVersion());
        input.setTiers(List.of(new ActivityInput.Tier(2, 1)));
        input.setName("只改活动名称");
        assertEquals(
                "只改活动名称",
                run(activityService.save(fixture.project, activity.getId(), input))
                        .activity()
                        .getName());
    }

    @Test
    void refundCannotTurnGiftIntoCashAndLeavesOriginalActivityFact() {
        var activity = activity(true);
        var q = create(activityInput(activity));
        var receipt = confirm(q, payment(q.payAmount())).receipt();
        var input = new RefundInput();
        input.setCompanyId("company");
        input.setMethod("1");
        input.setReceiverName("龚超");
        input.setRefundedAt("2024-02-01T11:00:00");
        input.setReason("预缴退款");
        input.setItems(
                receipt.lines().stream()
                        .map(l -> new RefundInput.Item(l.getId(), l.getVersion()))
                        .toList());
        input.setAmount(new BigDecimal("369.93"));
        reject(refunds.preview(fixture.project, input, DATE), 409);
        input.setAmount(new BigDecimal("246.62"));
        var preview = run(refunds.preview(fixture.project, input, DATE));
        var result =
                run(
                        refunds.confirm(
                                fixture.project,
                                new RefundService.Submission(
                                        UUID.randomUUID().toString(), preview.token(), input),
                                DATE));
        assertEquals(new BigDecimal("246.62"), result.refund().getAmount());
        var rows =
                bills.createQuery().where("settingId", settingId).fetch().collectList().block(WAIT);
        assertEquals(
                new BigDecimal("123.31"),
                rows.stream()
                        .map(ReceivableEntity::getFeeReductionAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(
                new BigDecimal("246.62"),
                rows.stream()
                        .map(ReceivableEntity::getArrears)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertTrue(rows.stream().allMatch(r -> r.getPaidAmount().signum() == 0));
        assertEquals(
                1,
                consumption.createQuery().where("projectId", fixture.project).count().block(WAIT));
        assertEquals(
                new BigDecimal("246.62"),
                payments.findById(receipt.payment().getId()).block(WAIT).getAmount());
    }

    @Test
    void endedRelationshipRefusesFirstConfirmationButDoesNotDestroySuccessfulRetry() {
        var q = create(input());
        var p = payment(q.payAmount());
        when(archive.context(eq(fixture.project), eq("room"), anyBoolean()))
                .thenReturn(
                        Mono.just(
                                new PropertyHouseContext(
                                        fixture.context("room").house(), List.of())));
        reject(confirmation.confirm(fixture.project, q.id(), p, DATE), 409);
        when(archive.context(eq(fixture.project), eq("room"), anyBoolean()))
                .thenReturn(Mono.just(fixture.context("room")));
        var first = confirm(q, p);
        when(archive.context(eq(fixture.project), eq("room"), anyBoolean()))
                .thenReturn(
                        Mono.just(
                                new PropertyHouseContext(
                                        fixture.context("room").house(), List.of())));
        assertEquals(first.receipt().payment().getId(), confirm(q, p).receipt().payment().getId());
    }

    @Test
    void confirmationNeedsCurrentSpacePermissionEvenForSuccessfulRetry() {
        var q = create(input());
        var p = payment(q.payAmount());
        confirm(q, p);
        var holder = mock(org.jetlinks.pro.assets.AssetsHolder.class);
        when(holder.assertPermission(anyString(), anyCollection(), any()))
                .thenReturn(
                        Mono.error(
                                new org.hswebframework.web.exception.BusinessException(
                                        "denied", 403)));
        reactor.test.StepVerifier.create(
                        confirmation
                                .confirm(fixture.project, q.id(), p, DATE)
                                .contextWrite(
                                        fixture.allowed()
                                                .put(
                                                        org.jetlinks.pro.assets.AssetsHolder.class,
                                                        holder)))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        reject(confirmation.confirm("other", q.id(), p, DATE), 404);
        assertEquals(
                1, payments.createQuery().where("projectId", fixture.project).count().block(WAIT));
    }

    @Test
    void tinyGiftAllocationsCanSettleZeroCashPeriodsWithoutFakePaymentLines() {
        // 未出账的其他房屋使用该极小金额设置；原有账单不被修改。
        standards
                .createUpdate()
                .set("price", new BigDecimal("0.01"))
                .set("formulaExpression", "${STANDARD_PRICE}*${PERIOD_MONTHS}")
                .where("id", fixture.standardId)
                .execute()
                .block(WAIT);
        var created =
                run(
                        settingService.create(
                                fixture.project,
                                fixture.input("tiny", "2024-02-01", "2024-12-31"),
                                DATE));
        var a = activity(false);
        var config = ActivityCodec.decode(a.getConfigJson(), ActivitySnapshot.class);
        config.getInput().setTargetIds(List.of("tiny"));
        config.getInput().setTiers(List.of(new ActivityInput.Tier(1, 2)));
        activities
                .createUpdate()
                .set("configJson", ActivityCodec.encode(config))
                .where("id", a.getId())
                .execute()
                .block(WAIT);
        when(activitySource.spaces(fixture.project))
                .thenReturn(
                        Mono.just(
                                List.of(
                                        new PropertySpaceInfo(
                                                "tiny", null, "tiny", "1栋", "1栋", null, null,
                                                true))));
        var q =
                create(
                        new PrepayQuoteInput(
                                UUID.randomUUID().toString(),
                                created.setting().getId(),
                                "link",
                                a.getId(),
                                1,
                                2));
        var result = confirm(q, payment(q.payAmount()));
        assertEquals("0.01", q.payAmount());
        assertEquals("0.03", q.originalAmount());
        assertEquals(1, result.receipt().lines().size());
        assertTrue(result.receipt().lines().stream().allMatch(l -> l.getAmount().signum() > 0));
        assertEquals(
                3,
                bills.createQuery()
                        .where("settingId", created.setting().getId())
                        .count()
                        .block(WAIT));
        assertTrue(
                bills.createQuery()
                        .where("settingId", created.setting().getId())
                        .fetch()
                        .all(r -> r.getArrears().signum() == 0)
                        .block(WAIT));
    }

    @Test
    void differentQuotesRaceForOneHouseActivityWithOneWinner() {
        var activity = activity(false);
        var a = create(activityInput(activity));
        var b = create(activityInput(activity));
        var results =
                Flux.merge(
                                confirmation
                                        .confirm(
                                                fixture.project,
                                                a.id(),
                                                payment(a.payAmount()),
                                                DATE)
                                        .materialize(),
                                confirmation
                                        .confirm(
                                                fixture.project,
                                                b.id(),
                                                payment(b.payAmount()),
                                                DATE)
                                        .materialize())
                        .contextWrite(fixture.allowed())
                        .collectList()
                        .block(WAIT);
        assertEquals(1, results.stream().filter(reactor.core.publisher.Signal::isOnNext).count());
        assertEquals(1, results.stream().filter(reactor.core.publisher.Signal::isOnError).count());
        assertTrue(
                results.stream()
                        .filter(reactor.core.publisher.Signal::isOnError)
                        .allMatch(s -> TaxItemPersistenceTest.status(s.getThrowable(), 409)));
        assertEquals(
                1,
                consumption.createQuery().where("projectId", fixture.project).count().block(WAIT));
        assertEquals(
                1, payments.createQuery().where("projectId", fixture.project).count().block(WAIT));
    }

    @Test
    void failedFinalQuoteWriteRollsBackAlreadyWrittenReceiptAndParticipation() throws Exception {
        var q = create(activityInput(activity(false)));
        String name = "reject_quote_" + UUID.randomUUID().toString().replace("-", "");
        var db = HouseSettingPersistenceTest.DB;
        assertEquals(
                0,
                db.execInContainer(
                                "psql",
                                "-U",
                                "postgres",
                                "-d",
                                "setting_test",
                                "-c",
                                "ALTER TABLE pm_fin_prepay_quote ADD CONSTRAINT "
                                        + name
                                        + " CHECK (project_id <> '"
                                        + fixture.project
                                        + "' OR status <> 'CONFIRMED') NOT VALID")
                        .getExitCode());
        try {
            reactor.test.StepVerifier.create(
                            confirmation
                                    .confirm(fixture.project, q.id(), payment(q.payAmount()), DATE)
                                    .contextWrite(fixture.allowed()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(
                    0,
                    payments.createQuery().where("projectId", fixture.project).count().block(WAIT));
            assertEquals(
                    0,
                    paymentLines
                            .createQuery()
                            .where("projectId", fixture.project)
                            .count()
                            .block(WAIT));
            assertEquals(
                    0,
                    consumption
                            .createQuery()
                            .where("projectId", fixture.project)
                            .count()
                            .block(WAIT));
            assertEquals(
                    2, bills.createQuery().where("projectId", fixture.project).count().block(WAIT));
            assertTrue(
                    bills.createQuery()
                            .where("projectId", fixture.project)
                            .fetch()
                            .all(r -> r.getFeeReductionAmount().signum() == 0)
                            .block(WAIT));
        } finally {
            db.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "setting_test",
                    "-c",
                    "ALTER TABLE pm_fin_prepay_quote DROP CONSTRAINT " + name);
        }
    }

    @Test
    void httpConfirmationReturnsReceiptAndRejectsForgedProjectsAndUnknownVersions() {
        var properties = new BillingJobProperties();
        var today = LocalDate.now(ZoneId.of(properties.getZone()));
        settings.createUpdate()
                .set("effectiveStartDate", today.withDayOfMonth(1).toString())
                .set("effectiveEndDate", today.plusYears(1).toString())
                .where("id", settingId)
                .execute()
                .block(WAIT);
        var q =
                run(
                        service.create(
                                fixture.project,
                                new PrepayQuoteInput(
                                        UUID.randomUUID().toString(),
                                        settingId,
                                        "link",
                                        null,
                                        1,
                                        0),
                                today));
        var p = payment(q.payAmount());
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web
                                        .PrepayConfirmationController(confirmation, properties))
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange).contextWrite(fixture.allowed()))
                        .build();
        String path =
                "/property/finance/projects/"
                        + fixture.project
                        + "/cashier/prepay/quotes/"
                        + q.id()
                        + "/_confirm";
        client.post()
                .uri(path.replace(fixture.project, "other"))
                .bodyValue(p)
                .exchange()
                .expectStatus()
                .isNotFound();
        client.post()
                .uri(path)
                .bodyValue(
                        new PrepayConfirmationInput(
                                p.requestId(),
                                8L,
                                p.companyId(),
                                p.paidAt(),
                                p.tenders(),
                                p.remark(),
                                null,
                                null))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        client.post()
                .uri(path)
                .bodyValue(p)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.quoteId")
                .isEqualTo(q.id())
                .jsonPath("$.receipt.payment.amount")
                .isEqualTo(q.payAmount())
                .jsonPath("$.receipt.payment.requestFingerprint")
                .doesNotExist();
        client.post().uri(path).bodyValue(p).exchange().expectStatus().isOk();
        assertNotNull(run(service.detail(fixture.project, q.id())).paymentId());
    }

    @Test
    void prepayOverOneHundredPeriodsIsNotTruncatedByOrdinaryCashierItemLimit() {
        settings.createUpdate()
                .set("effectiveEndDate", "2035-12-31")
                .where("id", settingId)
                .execute()
                .block(WAIT);
        var q =
                create(
                        new PrepayQuoteInput(
                                UUID.randomUUID().toString(), settingId, "link", null, 120, 0));
        assertEquals(120, q.lines().size());
        var result = confirm(q, payment(q.payAmount()));
        assertEquals("14797.20", q.payAmount());
        assertEquals(120, result.receipt().lines().size());
        assertEquals(121, bills.createQuery().where("settingId", settingId).count().block(WAIT));
        assertEquals(
                120,
                paymentLines
                        .createQuery()
                        .where("paymentId", result.receipt().payment().getId())
                        .count()
                        .block(WAIT));
        assertEquals(
                new BigDecimal(q.payAmount()),
                bills.createQuery()
                        .where("settingId", settingId)
                        .fetch()
                        .map(ReceivableEntity::getPaidAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .block(WAIT));
    }

    @Test
    void oneConfirmationRequestCannotBeReusedForAnotherHouseQuote() {
        var first = create(input());
        var setting =
                run(
                        settingService.create(
                                fixture.project,
                                fixture.input("other-house", "2024-01-01", "2026-12-31"),
                                DATE));
        var second =
                create(
                        new PrepayQuoteInput(
                                UUID.randomUUID().toString(),
                                setting.setting().getId(),
                                "link",
                                null,
                                2,
                                0));
        var request = payment(first.payAmount());
        confirm(first, request);
        reactor.test.StepVerifier.create(
                        confirmation
                                .confirm(fixture.project, second.id(), request, DATE)
                                .contextWrite(fixture.allowed()))
                .expectErrorMatches(
                        e ->
                                TaxItemPersistenceTest.status(e, 409)
                                        && e.getMessage().contains("prepay_request_conflict"))
                .verify(WAIT);
        assertEquals(
                1, payments.createQuery().where("projectId", fixture.project).count().block(WAIT));
        assertEquals("OPEN", run(service.detail(fixture.project, second.id())).status());
        assertTrue(
                bills.createQuery()
                        .where("settingId", setting.setting().getId())
                        .fetch()
                        .all(r -> r.getPaidAmount().signum() == 0)
                        .block(WAIT));
    }

    @Configuration
    @Import({
        PrepayQuotePersistenceTest.Config.class,
        RefundPersistenceTest.Config.class,
        PrepayConfirmationService.class,
        PrepayBillStore.class,
        PrepayPostingRules.class
    })
    static class Config {}
}
