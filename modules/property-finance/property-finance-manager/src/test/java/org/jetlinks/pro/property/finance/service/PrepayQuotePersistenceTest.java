package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.finance.activity.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 真PG验证报价的来源、账期占位、不可变恢复与权限；报价不得提前写收款或应收。 */
@SpringBootTest(
        classes = PrepayQuotePersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PrepayQuotePersistenceTest {
    static final Duration WAIT = HouseSettingPersistenceTest.WAIT;
    static final LocalDate DATE = LocalDate.of(2024, 2, 1);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        HouseSettingPersistenceTest.database(registry);
    }

    @Autowired PrepayQuoteService service;
    @Autowired HouseSettingService settingService;
    @Autowired HouseArchiveSource archive;
    @Autowired PrepayPeriodPricing pricing;
    @Autowired ActivitySource activitySource;
    @Autowired ReactiveRepository<StandardEntity, String> standards;
    @Autowired ReactiveRepository<ProjectSubjectEntity, String> issued;
    @Autowired ReactiveRepository<SubjectEntity, String> subjects;
    @Autowired ReactiveRepository<ReceivableEntity, String> bills;
    @Autowired ReactiveRepository<HouseSettingEntity, String> settings;
    @Autowired ReactiveRepository<PrepayQuoteEntity, String> quotes;
    @Autowired ReactiveRepository<ActivityEntity, String> activities;
    @Autowired ReactiveRepository<PaymentEntity, String> payments;
    HouseSettingPersistenceTest fixture;
    String settingId;

    @BeforeEach
    void setup() {
        fixture = new HouseSettingPersistenceTest();
        fixture.archive = archive;
        fixture.standards = standards;
        fixture.subjects = issued;
        fixture.subjectRows = subjects;
        fixture.setup();
        var saved =
                run(
                        settingService.create(
                                fixture.project,
                                fixture.input("room", "2024-01-01", "2026-12-31"),
                                DATE));
        settingId = saved.setting().getId();
        reset(activitySource);
        when(activitySource.spaces(fixture.project))
                .thenReturn(
                        Mono.just(
                                List.of(
                                        new PropertySpaceInfo(
                                                "root", null, "root", "园区", "园区", "park", "园区",
                                                false),
                                        new PropertySpaceInfo(
                                                "room", "root", "root", "101", "园区/101", "room",
                                                "房间", true))));
    }

    <T> T run(Mono<T> action) {
        return action.contextWrite(fixture.allowed()).block(WAIT);
    }

    PrepayQuoteInput input() {
        return new PrepayQuoteInput(UUID.randomUUID().toString(), settingId, "link", null, 2, 0);
    }

    PrepayQuoteService.View create(PrepayQuoteInput input) {
        return run(service.create(fixture.project, input, DATE));
    }

    void reject(Mono<?> action, int status) {
        StepVerifier.create(action.contextWrite(fixture.allowed()))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, status))
                .verify(WAIT);
    }

    ActivityEntity activity(boolean offset) {
        var input = new ActivityInput();
        input.setName("充二送一");
        input.setActivityType("prepay");
        input.setSubjectId(fixture.subjectId);
        input.setStandardId(fixture.standardId);
        input.setStartDate("2024-01-01");
        input.setEndDate("2024-12-31");
        input.setTargetIds(List.of("root"));
        input.setTiers(List.of(new ActivityInput.Tier(2, 1)));
        input.setOffsetArrears(offset);
        input.setOffsetStartDate(offset ? "2024-01-01" : null);
        input.setOffsetEndDate(offset ? "2024-01-31" : null);
        var snapshot = new ActivitySnapshot();
        snapshot.setInput(input);
        snapshot.setTargets(List.of());
        var row = new ActivityEntity();
        row.setId(UUID.randomUUID().toString());
        row.setProjectId(fixture.project);
        row.setName(input.getName());
        row.setSubjectId(fixture.subjectId);
        row.setStandardId(fixture.standardId);
        row.setStartDate(input.getStartDate());
        row.setEndDate(input.getEndDate());
        row.setEnabled(true);
        row.setConfigJson(ActivityCodec.encode(snapshot));
        StandardPersistenceTest.initialize(row);
        activities.insert(row).block(WAIT);
        return row;
    }

    PrepayQuoteInput activityInput(ActivityEntity row) {
        return new PrepayQuoteInput(
                UUID.randomUUID().toString(), settingId, "link", row.getId(), 2, 1);
    }

    @Test
    void serverPricesFrozenRulesAndQuoteWritesNoBillsOrPayments() {
        var before =
                bills.createQuery()
                        .where("projectId", fixture.project)
                        .fetch()
                        .collectList()
                        .block(WAIT);
        standards
                .createUpdate()
                .set("formulaExpression", "9999")
                .where("id", fixture.standardId)
                .execute()
                .block(WAIT);
        var result = create(input());
        assertEquals("246.62", result.payAmount());
        assertEquals("246.62", result.originalAmount());
        assertEquals("2024-02-01", result.lines().get(0).start());
        assertEquals("2024-03-31", result.lines().get(1).end());
        assertEquals("OPEN", result.status());
        assertEquals(0L, result.version());
        assertEquals(2, before.size());
        var after =
                bills.createQuery()
                        .where("projectId", fixture.project)
                        .fetch()
                        .collectList()
                        .block(WAIT);
        assertEquals(before.size(), after.size());
        assertEquals(
                before.stream().map(ReceivableEntity::getVersion).toList(),
                after.stream().map(ReceivableEntity::getVersion).toList());
        var saved = quotes.findById(result.id()).block(WAIT);
        assertEquals(600_000L, saved.getExpiresAt() - saved.getCreateTime());
        assertTrue(saved.getSnapshotJson().contains("STANDARD_PRICE"));
        assertFalse(saved.getSnapshotJson().contains("\"start\":{}"));
        assertEquals(
                0, payments.createQuery().where("projectId", fixture.project).count().block(WAIT));
    }

    @Test
    void retryReturnsOriginalQuoteButChangedInputCannotReuseRequest() {
        var input = input();
        var first = create(input);
        assertEquals(first, create(input));
        assertEquals(first, run(service.create(fixture.project, input, DATE.plusDays(1))));
        reject(
                service.create(
                        fixture.project,
                        new PrepayQuoteInput(input.requestId(), settingId, "link", null, 3, 0),
                        DATE),
                409);
        assertEquals(
                1, quotes.createQuery().where("projectId", fixture.project).count().block(WAIT));
        assertEquals(first, run(service.detail(fixture.project, first.id())));
        quotes.createUpdate().set("expiresAt", 1L).where("id", first.id()).execute().block(WAIT);
        assertEquals("EXPIRED", run(service.detail(fixture.project, first.id())).status());
    }

    @Test
    void partiallyPaidDeletedAndSplitPeriodsStayOccupiedAndAreSkipped() {
        var setting = settings.findById(settingId).block(WAIT);
        // 正式生成到四月后分别模拟已收、误删和拆分根占位，报价不能复活或重复这些期间。
        run(
                settingService.create(
                        fixture.project, fixture.input("other", "2024-02-01", "2024-02-29"), DATE));
        var feb =
                bills.createQuery()
                        .where("settingId", settingId)
                        .and("periodStart", "2024-02-01")
                        .fetchOne()
                        .block(WAIT);
        bills.createUpdate()
                .set("paidAmount", new BigDecimal("1.00"))
                .where("id", feb.getId())
                .execute()
                .block(WAIT);
        for (var month : List.of(3, 4)) {
            var start = LocalDate.of(2024, month, 1);
            var row =
                    pricing.price(
                                    setting,
                                    new PrepayQuoteRules.Window(
                                            start, start.withDayOfMonth(start.lengthOfMonth())))
                            .draft();
            row.setId(UUID.randomUUID().toString());
            StandardPersistenceTest.initialize(row);
            row.setAccruedAsOfDate(DATE.toString());
            row.setCalculatedAsOfDate(DATE.toString());
            row.setDeleted(month == 3);
            row.setSplit(month == 4);
            bills.insert(row).block(WAIT);
        }
        var result = create(input());
        assertEquals(
                List.of("2024-05-01", "2024-06-01"),
                result.lines().stream().map(PrepayQuoteService.Line::start).toList());
        assertEquals(4, bills.createQuery().where("settingId", settingId).count().block(WAIT));
    }

    @Test
    void activityGiftsArePeriodDiscountsAndEligibleArrearsComeFirst() {
        var result = create(activityInput(activity(true)));
        assertEquals("246.62", result.payAmount());
        assertEquals("369.93", result.originalAmount());
        assertEquals("ARREARS", result.lines().get(0).type());
        assertEquals("2024-01-01", result.lines().get(0).start());
        assertEquals("2024-03-31", result.lines().get(2).end());
        assertEquals(
                new BigDecimal("123.31"),
                result.lines().stream()
                        .map(l -> new BigDecimal(l.discountAmount()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(
                List.of("82.21", "82.21", "82.20"),
                result.lines().stream().map(PrepayQuoteService.Line::paidAmount).toList());
    }

    @Test
    void scopePayerTierDatesAndSettingStatusAreValidated() {
        var activity = activity(false);
        reject(service.create("other", input(), DATE), 404);
        reject(
                service.create(
                        fixture.project,
                        new PrepayQuoteInput(
                                UUID.randomUUID().toString(), settingId, "unknown", null, 2, 0),
                        DATE),
                409);
        reject(
                service.create(
                        fixture.project,
                        new PrepayQuoteInput(
                                UUID.randomUUID().toString(), settingId, "link", null, 2, 1),
                        DATE),
                400);
        reject(
                service.create(
                        fixture.project,
                        new PrepayQuoteInput(
                                UUID.randomUUID().toString(),
                                settingId,
                                "link",
                                activity.getId(),
                                3,
                                1),
                        DATE),
                409);
        reject(
                service.create(fixture.project, activityInput(activity), LocalDate.of(2025, 1, 1)),
                409);
        settings.createUpdate()
                .set("status", "STOPPED")
                .where("id", settingId)
                .execute()
                .block(WAIT);
        reject(service.create(fixture.project, input(), DATE), 409);
        assertEquals(
                0, quotes.createQuery().where("projectId", fixture.project).count().block(WAIT));
    }

    @Test
    void endedOrNotYetStartedPayerAndForeignSpaceAreRejected() {
        var context = fixture.context("room");
        when(archive.context(fixture.project, "room", true))
                .thenReturn(Mono.just(new PropertyHouseContext(context.house(), List.of())));
        reject(service.create(fixture.project, input(), DATE), 409);
        var original = context.customers().get(0);
        var late =
                new PropertyHouseCustomer(
                        original.relationshipId(),
                        original.customerId(),
                        original.userId(),
                        "person",
                        "龚超",
                        "123",
                        "owner",
                        LocalDate.of(2024, 3, 1)
                                .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                                .toInstant()
                                .toEpochMilli(),
                        3L,
                        4L);
        when(archive.context(fixture.project, "room", true))
                .thenReturn(Mono.just(new PropertyHouseContext(context.house(), List.of(late))));
        reject(service.create(fixture.project, input(), DATE), 409);
        when(archive.context(fixture.project, "room", true))
                .thenReturn(Mono.just(fixture.context("foreign")));
        reject(service.create(fixture.project, input(), DATE), 409);
    }

    @Test
    void assetDenialAlsoProtectsExistingQuoteRecovery() {
        var result = create(input());
        var holder = mock(AssetsHolder.class);
        when(holder.assertPermission(anyString(), anyCollection(), any()))
                .thenReturn(
                        Mono.error(
                                new org.hswebframework.web.exception.BusinessException(
                                        "denied", 403)));
        StepVerifier.create(
                        service.detail(fixture.project, result.id())
                                .contextWrite(fixture.allowed().put(AssetsHolder.class, holder)))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
    }

    @Test
    void concurrentRetryReturnsOneImmutableQuote() {
        var i = input();
        var results =
                Flux.merge(
                                service.create(fixture.project, i, DATE),
                                service.create(fixture.project, i, DATE))
                        .contextWrite(fixture.allowed())
                        .collectList()
                        .block(WAIT);
        assertEquals(2, results.size());
        assertEquals(results.get(0), results.get(1));
        assertEquals(
                1, quotes.createQuery().where("projectId", fixture.project).count().block(WAIT));
    }

    @Test
    void ordinaryPrepayIsNotEligibleForGiftAndEveryInvalidRequestWritesNothing() {
        for (var i :
                List.of(
                        new PrepayQuoteInput("short", settingId, "link", null, 2, 0),
                        new PrepayQuoteInput("valid-request", settingId, "link", null, 0, 0),
                        new PrepayQuoteInput("valid-request", settingId, "link", null, 1201, 0),
                        new PrepayQuoteInput("valid-request", settingId, "link", "", 2, 1))) {
            reject(service.create(fixture.project, i, DATE), 400);
        }
        reject(service.create(fixture.project, null, DATE), 400);
        assertEquals(
                0, quotes.createQuery().where("projectId", fixture.project).count().block(WAIT));
    }

    @Test
    void formerArrearsWithAdjustmentAreNotSilentlyIncludedInPromotion() {
        bills.createUpdate()
                .set("assessmentAmount", new BigDecimal("10.00"))
                .where("settingId", settingId)
                .and("periodStart", "2024-01-01")
                .execute()
                .block(WAIT);
        var result = create(activityInput(activity(true)));
        assertEquals("2024-02-01", result.lines().get(0).start());
        assertEquals("2024-04-30", result.lines().get(2).end());
    }

    @Test
    void disabledOrOutOfScopeActivityIsRejectedWithoutQuote() {
        var row = activity(false);
        activities
                .createUpdate()
                .set("enabled", false)
                .where("id", row.getId())
                .execute()
                .block(WAIT);
        reject(service.create(fixture.project, activityInput(row), DATE), 409);
        activities
                .createUpdate()
                .set("enabled", true)
                .where("id", row.getId())
                .execute()
                .block(WAIT);
        when(activitySource.spaces(fixture.project))
                .thenReturn(
                        Mono.just(
                                List.of(
                                        new PropertySpaceInfo(
                                                "room",
                                                "different-root",
                                                "different-root",
                                                "101",
                                                "其他/101",
                                                null,
                                                null,
                                                true))));
        reject(service.create(fixture.project, activityInput(row), DATE), 409);
        assertEquals(
                0, quotes.createQuery().where("projectId", fixture.project).count().block(WAIT));
    }

    @Test
    void detailIsOwnedAndHttpHidesCalculationSnapshots() {
        var result = create(input());
        var user = new org.hswebframework.web.authorization.simple.SimpleUser();
        user.setId("other-user");
        user.setUsername("other");
        var auth = new org.hswebframework.web.authorization.simple.SimpleAuthentication();
        auth.setUser(user);
        StepVerifier.create(
                        service.detail(fixture.project, result.id())
                                .contextWrite(
                                        fixture.allowed()
                                                .put(
                                                        org.hswebframework.web.authorization
                                                                .Authentication.class,
                                                        auth)))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 404))
                .verify(WAIT);
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web.PrepayQuoteController(
                                        service, new BillingJobProperties()))
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange).contextWrite(fixture.allowed()))
                        .build();
        String path =
                "/property/finance/projects/"
                        + fixture.project
                        + "/cashier/prepay/quotes/"
                        + result.id();
        client.get()
                .uri(path)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.payAmount")
                .isEqualTo("246.62")
                .jsonPath("$.lines[0].start")
                .isEqualTo("2024-02-01")
                .jsonPath("$.snapshotJson")
                .doesNotExist()
                .jsonPath("$.creatorId")
                .doesNotExist()
                .jsonPath("$.lines[0].billKey")
                .doesNotExist();
        client.put()
                .uri(path)
                .bodyValue(Map.of("payAmount", "0.01"))
                .exchange()
                .expectStatus()
                .isEqualTo(405);
        client.delete().uri(path).exchange().expectStatus().isEqualTo(405);
    }

    @Test
    void unselectedHouseCustomerCannotPayAndSelectedSecondCustomerCan() {
        var context = fixture.context("room");
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
        when(archive.context(eq(fixture.project), eq("room"), anyBoolean()))
                .thenReturn(
                        Mono.just(
                                new PropertyHouseContext(
                                        context.house(),
                                        List.of(context.customers().get(0), second))));
        reject(
                service.create(
                        fixture.project,
                        new PrepayQuoteInput(
                                UUID.randomUUID().toString(), settingId, "second-link", null, 2, 0),
                        DATE),
                409);
        var input = fixture.input("other-room", "2024-01-01", "2026-12-31");
        input.setCustomerRelationshipIds(List.of("link", "second-link"));
        when(archive.context(eq(fixture.project), eq("other-room"), anyBoolean()))
                .thenReturn(
                        Mono.just(
                                new PropertyHouseContext(
                                        fixture.context("other-room").house(),
                                        List.of(context.customers().get(0), second))));
        var setting = run(settingService.create(fixture.project, input, DATE));
        var result =
                create(
                        new PrepayQuoteInput(
                                UUID.randomUUID().toString(),
                                setting.setting().getId(),
                                "second-link",
                                null,
                                2,
                                0));
        assertEquals("张女士", result.payerName());
        assertEquals("246.62", result.payAmount());
        assertEquals(
                2,
                bills.createQuery()
                        .where("settingId", setting.setting().getId())
                        .count()
                        .block(WAIT));
    }

    @Test
    void effectiveEndAndQuarterlyMonthRulesCannotBeBypassed() {
        var input = fixture.input("short-room", "2024-02-01", "2024-02-29");
        var setting = run(settingService.create(fixture.project, input, DATE));
        reject(
                service.create(
                        fixture.project,
                        new PrepayQuoteInput(
                                UUID.randomUUID().toString(),
                                setting.setting().getId(),
                                "link",
                                null,
                                2,
                                0),
                        DATE),
                409);
        var original = settings.findById(settingId).block(WAIT);
        var snapshot = com.alibaba.fastjson.JSON.parseObject(original.getSnapshotJson());
        snapshot.getJSONObject("standard").put("settlementMethod", "QUARTERLY");
        settings.createUpdate()
                .set("snapshotJson", snapshot.toJSONString())
                .where("id", settingId)
                .execute()
                .block(WAIT);
        reject(service.create(fixture.project, input(), DATE), 400);
        assertEquals(
                0, quotes.createQuery().where("projectId", fixture.project).count().block(WAIT));
    }

    @Configuration
    @Import({
        ActivityPersistenceTest.Config.class,
        PrepayQuoteService.class,
        PrepayQuoteSource.class,
        PrepayPeriodPricing.class
    })
    static class Config {
        @Bean
        BillingJobProperties billingProperties() {
            return new BillingJobProperties();
        }
    }
}
