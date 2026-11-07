package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.InvoiceCompanyPersistenceTest.auth;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.finance.calculation.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.setting.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 真实数据库验证设置保存到出账的事务，不把计算器或持久层替换成桩。 */
@SpringBootTest(
        classes = HouseSettingPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HouseSettingPersistenceTest {
    static final Duration WAIT = Duration.ofSeconds(30);
    static final GenericContainer<?> DB =
            new GenericContainer<>("postgres:16-alpine")
                    .withEnv("POSTGRES_PASSWORD", "test-password")
                    .withEnv("POSTGRES_DB", "setting_test")
                    .withExposedPorts(5432);
    static final LocalDate DATE = LocalDate.of(2024, 2, 15);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.start();
        registry.add(
                "spring.r2dbc.url",
                () -> "r2dbc:postgresql://127.0.0.1:" + DB.getMappedPort(5432) + "/setting_test");
        registry.add("spring.r2dbc.username", () -> "postgres");
        registry.add("spring.r2dbc.password", () -> "test-password");
        registry.add("easyorm.dialect", () -> "postgres");
        registry.add("easyorm.default-schema", () -> "public");
    }

    @Autowired HouseSettingService settings;
    @Autowired HouseSettingReviewService settingReview;
    @Autowired ReceivableGenerationService generation;
    @Autowired ReceivableCalculationService calculation;
    @Autowired HouseArchiveSource archive;
    @Autowired ReactiveRepository<StandardEntity, String> standards;
    @Autowired ReactiveRepository<ProjectSubjectEntity, String> subjects;
    @Autowired ReactiveRepository<SubjectEntity, String> subjectRows;
    @Autowired ReactiveRepository<HouseSettingEntity, String> rows;
    @Autowired ReactiveRepository<ReceivableEntity, String> bills;
    @Autowired BillingTaskService tasks;
    @Autowired ReceivableService receivables;
    @Autowired ReceivablePresentationService presentation;
    @Autowired ReactiveRepository<DocumentEntity, String> documents;
    @Autowired ReactiveRepository<DocumentLineEntity, String> documentLines;
    @Autowired ReceivableBatchService batches;
    @Autowired ReceivableAdjustmentService adjustments;
    @Autowired ReactiveRepository<ReceivableImportEntity, String> importRecords;
    @Autowired HistoricalReceivableService historical;
    @Autowired HistoricalImportAuditService importAudit;
    @Autowired HistoricalImportEditService importEdits;
    @Autowired ReactiveRepository<ReceivableImportLineEntity, String> importDrafts;
    @Autowired ReceivableSplitService splits;
    @Autowired ReceivableChangeAuditService changeAudit;
    @Autowired ReactiveRepository<ReceivableChangeEntity, String> changeRows;
    @Autowired org.jetlinks.pro.property.finance.receivable.SplitRules splitRules;
    @Autowired org.jetlinks.pro.property.finance.receivable.AdjustmentRules adjustmentRules;
    @Autowired ReactiveRepository<ReceivableLogEntity, String> receivableLogs;
    @Autowired FinanceScope financeScope;
    @Autowired FinancialPeriodTestSupport financialPeriods;
    @Autowired FinancialPeriodGate periodGate;
    @Autowired ReactiveRepository<FinancialPeriodControlEntity, String> financialControls;
    @Autowired org.springframework.transaction.ReactiveTransactionManager transactionManager;
    @Autowired ReactiveRepository<BillingTaskEntity, String> taskRows;
    String project;
    String standardId;
    String subjectId;
    String relationId;

    @BeforeEach
    void setup() {
        reset(archive);
        project = UUID.randomUUID().toString();
        subjectId = UUID.randomUUID().toString();
        relationId = UUID.randomUUID().toString();
        var subject = new SubjectEntity();
        subject.setId(subjectId);
        subject.setCategoryId("category");
        subject.setCode("K" + subjectId.replace("-", "").substring(0, 16));
        subject.setName("物业费");
        subject.setNameKey(subjectId);
        subject.setFeeType("1");
        subject.setArrearsConfigId("arrears");
        subject.setEnabled(true);
        subject.setAutoInvoice(false);
        StandardPersistenceTest.initialize(subject);
        subjectRows.insert(subject).block(WAIT);
        var issued = new ProjectSubjectEntity();
        issued.setId(relationId);
        issued.setProjectId(project);
        issued.setSubjectId(subjectId);
        StandardPersistenceTest.initialize(issued);
        subjects.insert(issued).block(WAIT);
        var standard = new StandardEntity();
        standard.setId(UUID.randomUUID().toString());
        standardId = standard.getId();
        standard.setProjectId(project);
        standard.setSubjectId(subjectId);
        standard.setCategoryId("category");
        standard.setSubjectName("物业费");
        standard.setStandardCode("STD");
        standard.setStandardName("面积月费");
        standard.setCodeKey("std");
        standard.setNameKey("面积月费");
        standard.setBillingMethod("AREA");
        standard.setFormulaExpression("${STANDARD_PRICE}*${BILLING_AREA}*${PERIOD_MONTHS}");
        standard.setFormulaDisplay("[费用_金额]=[收费标准_单价]*[计费面积]*[起止时间（月）]");
        standard.setFormulaVersion(0L);
        standard.setPrice(new BigDecimal("1.23"));
        standard.setChargeRoundType(2);
        standard.setMantissa(0);
        standard.setSettlementMethod("MONTHLY");
        standard.setCompanyId("company");
        standard.setTaxItemId("tax");
        standard.setIsDelay(0);
        standard.setVariablesSnapshotJson(
                "[{\"variableCode\":\"STANDARD_PRICE\",\"variableName\":\"收费标准_单价\",\"enabled\":true},{\"variableCode\":\"BILLING_AREA\",\"variableName\":\"计费面积\",\"enabled\":true},{\"variableCode\":\"PERIOD_MONTHS\",\"variableName\":\"起止时间（月）\",\"enabled\":true}]");
        standard.setTaxSnapshotJson("{\"taxRate\":0.06,\"taxationMethod\":\"1\"}");
        StandardPersistenceTest.initialize(standard);
        standards.insert(standard).block(WAIT);
        when(archive.context(eq(project), anyString(), anyBoolean()))
                .thenAnswer(call -> Mono.just(context(call.getArgument(1))));
    }

    PropertyHouseContext context(String space) {
        return new PropertyHouseContext(
                new PropertyHouseInfo(
                        space,
                        project,
                        "101",
                        "园区/1栋/101",
                        "room",
                        "房间",
                        "楼栋：1栋 / 房间：101",
                        new BigDecimal("100.25"),
                        null,
                        null,
                        null,
                        2L),
                List.of(
                        new PropertyHouseCustomer(
                                "link",
                                "customer",
                                "user",
                                "person",
                                "龚超",
                                "123",
                                "owner",
                                1L,
                                3L,
                                4L)));
    }

    SettingInput input(String space, String start, String end) {
        var input = new SettingInput();
        input.setSpaceId(space);
        input.setProjectSubjectId(relationId);
        input.setStandardId(standardId);
        input.setCustomerRelationshipIds(List.of("link"));
        input.setEffectiveStartDate(start);
        input.setEffectiveEndDate(end);
        input.setVariableValues(Map.of());
        return input;
    }

    @BeforeAll
    static void importMessages() {
        CollectionImportPersistenceTest.messages();
    }

    @Test
    void multipleChargeCustomersShareOneBillAndEverySelectionMustBeCurrent() {
        var context = context("room");
        var second =
                new PropertyHouseCustomer(
                        "second-link",
                        "second-customer",
                        "second-user",
                        "person",
                        "张女士",
                        "13800000000",
                        "tenant",
                        1L,
                        1L,
                        1L);
        when(archive.context(eq(project), eq("room"), anyBoolean()))
                .thenReturn(
                        Mono.just(
                                new PropertyHouseContext(
                                        context.house(),
                                        List.of(context.customers().get(0), second))));
        var input = input("room", "2024-02-01", "2024-02-29");
        input.setCustomerRelationshipIds(List.of("link", "second-link"));
        var result = createApproved(project, input, DATE).contextWrite(allowed()).block(WAIT);
        assertEquals(1, result.generated());
        assertEquals(List.of("link", "second-link"), result.setting().getCustomerRelationshipIds());
        assertEquals(List.of("龚超", "张女士"), result.setting().getCustomerNames());
        assertEquals(1, bills.createQuery().where("projectId", project).count().block(WAIT));
        var bill = bills.createQuery().where("projectId", project).fetchOne().block(WAIT);
        assertTrue(SettingRules.selectedCustomer(bill, "customer"));
        assertTrue(SettingRules.selectedCustomer(bill, "second-customer"));
        assertFalse(SettingRules.selectedCustomer(bill, "unselected"));
        assertFalse(bill.getSnapshotJson().contains("familyId"));
        var other = input("other", "2024-02-01", "2024-02-29");
        other.setCustomerRelationshipIds(List.of("link", "missing"));
        StepVerifier.create(createApproved(project, other, DATE).contextWrite(allowed()))
                .expectError()
                .verify(WAIT);
        assertEquals(1, bills.createQuery().where("projectId", project).count().block(WAIT));
    }

    Context allowed() {
        var holder = mock(AssetsHolder.class);
        when(holder.isScopeIgnored(any(org.jetlinks.pro.assets.AssetType.class)))
                .thenReturn(Mono.just(false));
        when(holder.assertPermission(anyString(), anyCollection(), any())).thenReturn(Mono.empty());
        when(holder.injectQuery(
                        any(org.hswebframework.web.api.crud.entity.QueryParamEntity.class),
                        anyString(),
                        anyString(),
                        any(org.jetlinks.pro.assets.AssetPermission[].class)))
                .thenAnswer(
                        call -> {
                            org.hswebframework.web.api.crud.entity.QueryParamEntity query =
                                    call.getArgument(0);
                            query.toNestQuery(
                                    nested ->
                                            nested.and(
                                                    "spaceId",
                                                    "in",
                                                    List.of("room", "job", "failed-job")));
                            return Mono.just(query);
                        });
        when(holder.filter(
                        any(Flux.class),
                        any(org.jetlinks.pro.assets.AssetType.class),
                        any(java.util.function.Function.class),
                        any(org.jetlinks.pro.assets.AssetPermission[].class)))
                .thenAnswer(
                        call ->
                                ((Flux<?>) call.getArgument(0))
                                        .filter(
                                                value ->
                                                        List.of("room", "job", "failed-job")
                                                                .contains(
                                                                        ((java.util.function
                                                                                                        .Function<
                                                                                                Object,
                                                                                                ?>)
                                                                                        call
                                                                                                .getArgument(
                                                                                                        2))
                                                                                .apply(value))));
        return auth().put(AssetsHolder.class, holder);
    }

    @Test
    void prepayQuoteMatchesPersistedBillAndIgnoresLaterLiveStandardChanges() {
        var saved =
                createApproved(project, input("room", "2024-02-10", "2024-02-29"), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var bill =
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        var setting = rows.findById(saved.setting().getId()).block(WAIT);
        standards
                .createUpdate()
                .set("formulaExpression", "9999")
                .where("id", standardId)
                .execute()
                .block(WAIT);
        var pricing = new PrepayPeriodPricing(new NaturalBillingPeriodService(), calculation);
        var quote =
                pricing.price(
                        setting,
                        new org.jetlinks.pro.property.finance.activity.PrepayQuoteRules.Window(
                                LocalDate.of(2024, 2, 10), LocalDate.of(2024, 2, 29)));
        assertEquals(new BigDecimal("85.04"), quote.amount());
        assertEquals(bill.getContractReceivableAmount(), quote.amount());
        assertEquals(bill.getBillKey(), quote.draft().getBillKey());
        assertEquals(bill.getSnapshotJson(), quote.draft().getSnapshotJson());
        assertNull(quote.draft().getId());
        assertNull(quote.draft().getCreateTime());
        assertEquals(1, bills.createQuery().where("projectId", project).count().block(WAIT));
        var unchanged = bills.findById(bill.getId()).block(WAIT);
        assertEquals(bill.getAccruedReceivableAmount(), unchanged.getAccruedReceivableAmount());
        assertEquals(bill.getVersion(), unchanged.getVersion());
    }

    @Test
    void saveImmediatelyGeneratesProratedLeapMonthAndFreezesTrustedSources() {
        var saved =
                createApproved(project, input("room", "2024-02-10", "2024-02-29"), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(1, saved.generated());
        var bill =
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        assertEquals(new BigDecimal("85.04"), bill.getContractReceivableAmount());
        assertEquals(new BigDecimal("25.51"), bill.getAccruedReceivableAmount());
        assertEquals("OPEN", bill.getPeriodStatus());
        assertEquals("customer", bill.getCustomerId());
        assertEquals("link", bill.getCustomerRelationshipId());
        assertEquals("龚超", bill.getCustomerName());
        verify(archive).context(project, "room", true);
        standards
                .createUpdate()
                .set("price", new BigDecimal("99"))
                .where("id", standardId)
                .execute()
                .block(WAIT);
        generation
                .generate(project, saved.setting().getId(), DATE.plusDays(1))
                .contextWrite(allowed())
                .block(WAIT);
        assertEquals(
                new BigDecimal("85.04"),
                bills.findById(bill.getId()).block(WAIT).getContractReceivableAmount());
    }

    @Test
    void futureSettingCreatesNoBillAndExpiredCoverageClosesWithLateFee() {
        var future =
                createApproved(project, input("future", "2024-03-01", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(0, future.generated());
        standards
                .createUpdate()
                .set("isDelay", 1)
                .set("delayCalcForm", 0)
                .set("delayRoundType", 0)
                .set("rateAmount", new BigDecimal("2"))
                .set("delayBaseType", "CURRENT_RECEIVABLE")
                .where("id", standardId)
                .execute()
                .block(WAIT);
        var past =
                createApproved(project, input("past", "2024-01-01", "2024-01-31"), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var bill =
                bills.createQuery()
                        .where("settingId", past.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        assertEquals("CLOSED", bill.getPeriodStatus());
        assertEquals(new BigDecimal("123.31"), bill.getContractReceivableAmount());
        assertEquals(new BigDecimal("30.00"), bill.getAccruedLateFeeAmount());
    }

    @Test
    void untrustedVariablesWrongCustomerAndUncoveredStandardAreRejectedBeforeAnyWrite() {
        var request = input("room", "2024-02-01", null);
        request.setVariableValues(Map.of("STANDARD_PRICE", "0.01"));
        conflict(createApproved(project, request, DATE), 400);
        request.setVariableValues(Map.of());
        request.setCustomerRelationshipIds(List.of("other-house-link"));
        conflict(createApproved(project, request, DATE), 409);
        request.setCustomerRelationshipIds(List.of("link"));
        standards
                .createUpdate()
                .set("cancelDate", "2024-02-29")
                .where("id", standardId)
                .execute()
                .block(WAIT);
        conflict(createApproved(project, request, DATE), 409);
        assertEquals(0, rows.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void sameHouseSubjectOverlapIsSerializedAndRepeatedGenerationIsIdempotent() {
        var actions =
                Flux.merge(
                                createApproved(project, input("room", "2024-01-01", null), DATE)
                                        .contextWrite(allowed())
                                        .materialize(),
                                createApproved(project, input("room", "2024-02-01", null), DATE)
                                        .contextWrite(allowed())
                                        .materialize())
                        .collectList()
                        .block(WAIT);
        assertEquals(1, actions.stream().filter(Signal::isOnNext).count());
        assertEquals(1, actions.stream().filter(Signal::isOnError).count());
        var setting = rows.createQuery().where("projectId", project).fetchOne().block(WAIT);
        long count = bills.createQuery().where("settingId", setting.getId()).count().block(WAIT);
        Flux.merge(
                        generation.generate(project, setting.getId(), DATE).contextWrite(allowed()),
                        generation.generate(project, setting.getId(), DATE).contextWrite(allowed()))
                .blockLast(WAIT);
        assertEquals(
                count,
                bills.createQuery()
                        .where("settingId", setting.getId())
                        .count()
                        .block(WAIT)
                        .longValue());
        var bill = bills.createQuery().where("settingId", setting.getId()).fetchOne().block(WAIT);
        bills.createUpdate().set("deleted", true).where("id", bill.getId()).execute().block(WAIT);
        generation.generate(project, setting.getId(), DATE).contextWrite(allowed()).block(WAIT);
        assertEquals(
                count,
                bills.createQuery()
                        .where("settingId", setting.getId())
                        .count()
                        .block(WAIT)
                        .longValue());
        assertTrue(bills.findById(bill.getId()).block(WAIT).getDeleted());
    }

    @Test
    void calculationFailureLeavesPendingSettingAndRollsBackApprovalAndEveryBill() {
        standards
                .createUpdate()
                .set("formulaExpression", "${STANDARD_PRICE}/0")
                .where("id", standardId)
                .execute()
                .block(WAIT);
        StepVerifier.create(
                        createApproved(project, input("room", "2024-01-01", null), DATE)
                                .contextWrite(allowed()))
                .expectError()
                .verify(WAIT);
        assertEquals(1, rows.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                "PENDING",
                rows.createQuery()
                        .where("projectId", project)
                        .fetchOne()
                        .block(WAIT)
                        .getReviewState());
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void issuedSettingsAreImmutableWhileFutureUnbilledCanBeEditedWithVersion() {
        var active =
                createApproved(project, input("room", "2024-01-01", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        var change = input("room", "2024-01-01", null);
        change.setVersion(active.getVersion());
        conflict(updateApproved(project, active.getId(), change, DATE), 409);
        conflict(settings.delete(project, active.getId(), active.getVersion()), 409);
        var future =
                createApproved(project, input("future", "2024-04-01", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        var replacement = input("future", "2024-05-01", null);
        replacement.setVersion(future.getVersion());
        var edited =
                updateApproved(project, future.getId(), replacement, DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        assertEquals(future.getVersion() + 2, edited.getVersion());
        conflict(updateApproved(project, future.getId(), replacement, DATE), 409);
        settings.delete(project, future.getId(), edited.getVersion())
                .contextWrite(allowed())
                .block(WAIT);
        assertTrue(rows.findById(future.getId()).block(WAIT).getDeleted());
    }

    @Test
    void missingSourceAnonymousAndForbiddenProjectNeverCreateSettings() {
        when(archive.context(eq(project), anyString(), anyBoolean()))
                .thenReturn(Mono.error(FinanceScope.error("house_source_unavailable", 503)));
        conflict(createApproved(project, input("room", "2024-01-01", null), DATE), 503);
        StepVerifier.create(createApproved(project, input("room", "2024-01-01", null), DATE))
                .expectError(
                        org.hswebframework.web.authorization.exception.UnAuthorizedException.class)
                .verify(WAIT);
        conflict(createApproved("forbidden", input("room", "2024-01-01", null), DATE), 403);
        assertEquals(0, rows.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void batchFailureLeavesNoConfigurationOrReceivableAndSuccessCreatesEveryHouse() {
        var bad = input("bad", "2024-02-01", null);
        bad.setCustomerRelationshipIds(List.of("missing"));
        conflict(
                createBatchApproved(project, List.of(input("good", "2024-02-01", null), bad), DATE),
                409);
        assertEquals(0, rows.createQuery().where("projectId", project).count().block(WAIT));
        var saved =
                createBatchApproved(
                                project,
                                List.of(
                                        input("a", "2024-02-01", null),
                                        input("b", "2024-02-01", null)),
                                DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(2, saved.size());
        assertEquals(2, bills.createQuery().where("projectId", project).count().block(WAIT));
        var overlapA = input("c", "2024-02-01", null);
        var overlapB = input("c", "2024-03-01", null);
        conflict(createBatchApproved(project, List.of(overlapA, overlapB), DATE), 409);
        assertEquals(2, rows.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void nextVersionPreservesOldBillsAndRejectsDateThatWouldRewriteAnExistingBill() {
        var saved =
                createApproved(project, input("room", "2024-01-01", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        var next = input("room", "2024-03-01", null);
        next.setVersion(saved.getVersion());
        var successor =
                nextVersionApproved(project, saved.getId(), next, DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        assertEquals(saved.getId(), successor.getPreviousVersionId());
        assertEquals(2, successor.getConfigVersion());
        assertEquals("2024-02-29", rows.findById(saved.getId()).block(WAIT).getEffectiveEndDate());
        assertEquals("SUPERSEDED", rows.findById(saved.getId()).block(WAIT).getStatus());
        assertEquals(2, bills.createQuery().where("settingId", saved.getId()).count().block(WAIT));
        var another =
                createApproved(project, input("other", "2024-01-01", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        var collision = input("other", "2024-02-15", null);
        collision.setVersion(another.getVersion());
        conflict(nextVersionApproved(project, another.getId(), collision, DATE), 409);
        assertNull(rows.findById(another.getId()).block(WAIT).getEffectiveEndDate());
    }

    @Test
    void stopDoesNotTouchExistingBillsAndPreventsLaterGeneration() {
        var setting =
                createApproved(project, input("room", "2024-01-01", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        conflict(
                settings.stop(
                        project,
                        setting.getId(),
                        new HouseSettingService.StopInput(setting.getVersion(), "2024-02-15"),
                        DATE),
                409);
        var stopped =
                settings.stop(
                                project,
                                setting.getId(),
                                new HouseSettingService.StopInput(
                                        setting.getVersion(), "2024-02-29"),
                                DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("STOPPED", stopped.getStatus());
        generation
                .generate(project, setting.getId(), LocalDate.of(2024, 4, 1))
                .contextWrite(allowed())
                .block(WAIT);
        assertEquals(
                2, bills.createQuery().where("settingId", setting.getId()).count().block(WAIT));
    }

    @Test
    void httpReturnsStructuredSnapshotsAndGenerationResultWithServerBusinessDate() {
        var controller =
                new org.jetlinks.pro.property.finance.web.HouseSettingController(
                        settings, generation);
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                controller)
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) -> chain.filter(exchange).contextWrite(allowed()))
                        .build();
        client.post()
                .uri("/property/finance/projects/" + project + "/settings")
                .bodyValue(input("room", "2099-01-01", null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.generated")
                .isEqualTo(0)
                .jsonPath("$.setting.snapshot.house.fullName")
                .isEqualTo("园区/1栋/101")
                .jsonPath("$.setting.snapshot.customer.name")
                .isEqualTo("龚超")
                .jsonPath("$.setting.snapshot.standard.price")
                .isEqualTo("1.23")
                .jsonPath("$.setting.snapshotJson")
                .doesNotExist();
    }

    @Test
    void dailyStagesHaveSeparateEffectsAndPersistOneTaskForConcurrentExecutions() {
        standards
                .createUpdate()
                .set("isDelay", 1)
                .set("delayCalcForm", 0)
                .set("delayRoundType", 0)
                .set("rateAmount", new BigDecimal("2"))
                .set("delayBaseType", "CURRENT_RECEIVABLE")
                .where("id", standardId)
                .execute()
                .block(WAIT);
        var saved =
                createApproved(
                                project,
                                input("job", "2024-01-01", null),
                                LocalDate.of(2023, 12, 31))
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        var setting = rows.findById(saved.getId()).block(WAIT);
        var results =
                Flux.merge(
                                tasks.execute(setting, BillingTaskKind.GENERATE, DATE),
                                tasks.execute(setting, BillingTaskKind.GENERATE, DATE))
                        .collectList()
                        .block(WAIT);
        assertEquals(
                1, results.stream().filter(result -> "SUCCEEDED".equals(result.status())).count());
        assertEquals(
                2, bills.createQuery().where("settingId", setting.getId()).count().block(WAIT));
        var january =
                bills.createQuery()
                        .where("settingId", setting.getId())
                        .and("periodStart", "2024-01-01")
                        .fetchOne()
                        .block(WAIT);
        assertEquals("OPEN", january.getPeriodStatus());
        assertEquals(0, january.getAccruedLateFeeAmount().signum());
        generation.scheduled(project, setting.getId(), DATE, BillingTaskKind.ACCRUE).block(WAIT);
        assertEquals("OPEN", bills.findById(january.getId()).block(WAIT).getPeriodStatus());
        generation.scheduled(project, setting.getId(), DATE, BillingTaskKind.LATE_FEE).block(WAIT);
        assertEquals(
                new BigDecimal("30.00"),
                bills.findById(january.getId()).block(WAIT).getAccruedLateFeeAmount());
        generation.scheduled(project, setting.getId(), DATE, BillingTaskKind.CLOSE).block(WAIT);
        assertEquals("CLOSED", bills.findById(january.getId()).block(WAIT).getPeriodStatus());
        var job =
                tasks.query(project, new org.hswebframework.web.api.crud.entity.QueryParamEntity())
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(1, job.getTotal());
        assertEquals("SUCCEEDED", job.getData().get(0).getStatus());
        assertEquals(1, job.getData().get(0).getAttempts());
    }

    @Test
    void failedTaskRollsBackBillsAndCanBeRetriedWithOriginalIdentityAndTenant() {
        var saved =
                createApproved(
                                project,
                                input("failed-job", "2024-01-01", null),
                                LocalDate.of(2023, 12, 31))
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        var setting = rows.findById(saved.getId()).block(WAIT);
        var original = setting.getSnapshotJson();
        var broken = com.alibaba.fastjson.JSON.parseObject(original);
        broken.getJSONObject("standard").put("formulaExpression", "${STANDARD_PRICE}/0");
        rows.createUpdate()
                .set("snapshotJson", broken.toJSONString())
                .where("id", setting.getId())
                .execute()
                .block(WAIT);
        assertEquals(
                "FAILED",
                tasks.execute(setting, BillingTaskKind.GENERATE, DATE).block(WAIT).status());
        assertEquals(
                0, bills.createQuery().where("settingId", setting.getId()).count().block(WAIT));
        var task =
                tasks.query(project, new org.hswebframework.web.api.crud.entity.QueryParamEntity())
                        .contextWrite(allowed())
                        .block(WAIT)
                        .getData()
                        .get(0);
        assertEquals("FAILED", task.getStatus());
        assertNotNull(task.getErrorCode());
        rows.createUpdate()
                .set("snapshotJson", original)
                .where("id", setting.getId())
                .execute()
                .block(WAIT);
        conflict(tasks.retry("forbidden", task.getId()), 403);
        tasks.retry(project, task.getId()).contextWrite(allowed()).block(WAIT);
        tasks.resume().block(WAIT);
        var retried =
                tasks.query(project, new org.hswebframework.web.api.crud.entity.QueryParamEntity())
                        .contextWrite(allowed())
                        .block(WAIT)
                        .getData()
                        .get(0);
        assertEquals(task.getId(), retried.getId());
        assertEquals("SUCCEEDED", retried.getStatus());
        assertEquals(2, retried.getAttempts());
        conflict(
                generation
                        .scheduled(project, setting.getId(), DATE, BillingTaskKind.ALL)
                        .contextWrite(
                                org.jetlinks.pro.tenant.holder.TenantHolder.makeCurrent("other")),
                409);
    }

    @Test
    void expiredWorkerIsRecoveredAndTaskQueryCannotEscapeSpaceScope() {
        var visible =
                createApproved(
                                project,
                                input("job", "2024-01-01", null),
                                LocalDate.of(2023, 12, 31))
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        var hidden =
                createApproved(
                                project,
                                input("hidden", "2024-01-01", null),
                                LocalDate.of(2023, 12, 31))
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        tasks.execute(rows.findById(visible.getId()).block(WAIT), BillingTaskKind.GENERATE, DATE)
                .block(WAIT);
        tasks.execute(rows.findById(hidden.getId()).block(WAIT), BillingTaskKind.GENERATE, DATE)
                .block(WAIT);
        var page =
                tasks.query(project, new org.hswebframework.web.api.crud.entity.QueryParamEntity())
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(1, page.getTotal());
        assertEquals("job", page.getData().get(0).getSpaceId());
        var task = page.getData().get(0);
        taskRows.createUpdate()
                .set("status", "RUNNING")
                .set("leaseOwner", "crashed-worker")
                .set("leaseUntil", 0L)
                .where("id", task.getId())
                .execute()
                .block(WAIT);
        tasks.resume().block(WAIT);
        var restored = taskRows.findById(task.getId()).block(WAIT);
        assertEquals("SUCCEEDED", restored.getStatus());
        assertEquals(2, restored.getAttempts());
        assertEquals(
                2, bills.createQuery().where("settingId", visible.getId()).count().block(WAIT));
        var controller = new org.jetlinks.pro.property.finance.web.BillingTaskController(tasks);
        org.springframework.test.web.reactive.server.WebTestClient.bindToController(controller)
                .webFilter((exchange, chain) -> chain.filter(exchange).contextWrite(allowed()))
                .build()
                .post()
                .uri("/property/finance/projects/" + project + "/billing-tasks/_query")
                .bodyValue(new org.hswebframework.web.api.crud.entity.QueryParamEntity())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1)
                .jsonPath("$.data[0].tenantId")
                .doesNotExist()
                .jsonPath("$.data[0].leaseOwner")
                .doesNotExist()
                .jsonPath("$.data[0].leaseUntil")
                .doesNotExist();
    }

    @Test
    void receivableFreezeUnfreezeUsesVersionAndPreservesSameTransactionAudit() {
        var saved =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var bill =
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        receivables
                .change(
                        project,
                        bill.getId(),
                        new ReceivableService.ChangeInput(bill.getVersion(), "核对费用"),
                        "FREEZE")
                .contextWrite(allowed())
                .block(WAIT);
        var frozen = receivables.detail(project, bill.getId()).contextWrite(allowed()).block(WAIT);
        assertTrue(frozen.getFrozen());
        assertEquals(Set.of("DETAIL", "LOG", "UNFREEZE"), frozen.getAllowedOperations());
        conflict(
                receivables.change(
                        project,
                        bill.getId(),
                        new ReceivableService.ChangeInput(bill.getVersion(), "过期编辑"),
                        "UNFREEZE"),
                409);
        receivables
                .change(
                        project,
                        bill.getId(),
                        new ReceivableService.ChangeInput(frozen.getVersion(), "核对完成"),
                        "UNFREEZE")
                .contextWrite(allowed())
                .block(WAIT);
        var audit =
                receivables
                        .logs(
                                project,
                                bill.getId(),
                                new org.hswebframework.web.api.crud.entity.QueryParamEntity())
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(2, audit.getTotal());
        assertEquals("UNFREEZE", audit.getData().get(0).getOperation());
        assertEquals(true, audit.getData().get(0).getBefore().get("frozen"));
        assertEquals(false, audit.getData().get(0).getAfter().get("frozen"));
        assertEquals("龚超", frozen.getCustomerName());
        assertNotNull(frozen.getSnapshot());
    }

    @Test
    void receivableDeleteIsLogicalAndDoesNotRegenerateWhileAuditRemains() {
        var saved =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var bill =
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        receivables
                .change(
                        project,
                        bill.getId(),
                        new ReceivableService.ChangeInput(bill.getVersion(), "错误配置"),
                        "DELETE")
                .contextWrite(allowed())
                .block(WAIT);
        generation
                .generate(project, saved.setting().getId(), DATE.plusDays(1))
                .contextWrite(allowed())
                .block(WAIT);
        assertEquals(
                1,
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .count()
                        .block(WAIT));
        assertTrue(bills.findById(bill.getId()).block(WAIT).getDeleted());
        conflict(receivables.detail(project, bill.getId()), 404);
        assertEquals(
                1,
                receivables
                        .logs(
                                project,
                                bill.getId(),
                                new org.hswebframework.web.api.crud.entity.QueryParamEntity())
                        .contextWrite(allowed())
                        .block(WAIT)
                        .getTotal());
        assertEquals(
                0,
                receivables
                        .query(
                                project,
                                new org.hswebframework.web.api.crud.entity.QueryParamEntity())
                        .contextWrite(allowed())
                        .block(WAIT)
                        .getTotal());
    }

    @Test
    void receivableConcurrentChangesOnlyOneWinsAndCannotDeleteClosedOrPaidBills() {
        var saved =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var bill =
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        var input = new ReceivableService.ChangeInput(bill.getVersion(), "并发核对");
        var results =
                Flux.merge(
                                receivables
                                        .change(project, bill.getId(), input, "FREEZE")
                                        .thenReturn(true)
                                        .onErrorReturn(false),
                                receivables
                                        .change(project, bill.getId(), input, "FREEZE")
                                        .thenReturn(true)
                                        .onErrorReturn(false))
                        .collectList()
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(
                1,
                receivableLogs
                        .createQuery()
                        .where("receivableId", bill.getId())
                        .count()
                        .block(WAIT));
        bills.createUpdate()
                .set("frozen", false)
                .set("periodStatus", "CLOSED")
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        var version = bills.findById(bill.getId()).block(WAIT).getVersion();
        conflict(
                receivables.change(
                        project,
                        bill.getId(),
                        new ReceivableService.ChangeInput(version, "不能删关期"),
                        "DELETE"),
                409);
        bills.createUpdate()
                .set("periodStatus", "OPEN")
                .set("paidAmount", BigDecimal.ONE)
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        conflict(
                receivables.change(
                        project,
                        bill.getId(),
                        new ReceivableService.ChangeInput(version, "不能删已收"),
                        "DELETE"),
                409);
    }

    @Test
    void receivableQueryAndExcelRetainProjectSpaceBoundaryAndExactAmounts() throws Exception {
        var saved =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        createApproved(project, input("hidden", "2024-02-10", null), DATE)
                .contextWrite(allowed())
                .block(WAIT);
        var bill =
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        var query = new org.hswebframework.web.api.crud.entity.QueryParamEntity();
        query.toQuery().where("projectId", "other").or("spaceId", "hidden").or("spaceId", "room");
        assertEquals(
                1,
                receivables.query(project, query).contextWrite(allowed()).block(WAIT).getTotal());
        bills.createUpdate()
                .set("actualReceivableAmount", new BigDecimal("999999999999999999.99"))
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        byte[] bytes = receivables.export(project, query).contextWrite(allowed()).block(WAIT);
        try (var book =
                new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                        new java.io.ByteArrayInputStream(bytes))) {
            var sheet = book.getSheetAt(0);
            assertEquals(1, sheet.getLastRowNum());
            assertEquals("实际应收", sheet.getRow(0).getCell(11).getStringCellValue());
            assertEquals("999999999999999999.99", sheet.getRow(1).getCell(11).getStringCellValue());
        }
        var holder = mock(AssetsHolder.class);
        when(holder.assertPermission(anyString(), anyCollection(), any()))
                .thenReturn(Mono.error(FinanceScope.error("forbidden", 403)));
        StepVerifier.create(
                        receivables
                                .detail(project, bill.getId())
                                .contextWrite(auth().put(AssetsHolder.class, holder)))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        conflict(receivables.detail("other", bill.getId()), 404);
        conflict(receivables.query("forbidden", query), 403);
        StepVerifier.create(receivables.query(project, query)).expectError().verify(WAIT);
    }

    @Test
    void receivableAuditFailureRollsBackStatusAndHttpOnlyExposesParsedReadOnlySnapshot() {
        var saved =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var bill =
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        @SuppressWarnings("unchecked")
        ReactiveRepository<ReceivableLogEntity, String> failedLogs = mock(ReactiveRepository.class);
        when(failedLogs.insert(any(ReceivableLogEntity.class)))
                .thenReturn(Mono.error(new IllegalStateException("audit unavailable")));
        var failedService =
                new ReceivableService(financeScope, periodGate, bills, failedLogs, presentation);
        StepVerifier.create(
                        org.springframework.transaction.reactive.TransactionalOperator.create(
                                        transactionManager)
                                .transactional(
                                        failedService.change(
                                                project,
                                                bill.getId(),
                                                new ReceivableService.ChangeInput(
                                                        bill.getVersion(), "测试事务回滚"),
                                                "FREEZE"))
                                .contextWrite(allowed()))
                .expectErrorMessage("audit unavailable")
                .verify(WAIT);
        var retained = bills.findById(bill.getId()).block(WAIT);
        assertFalse(retained.getFrozen());
        assertEquals(bill.getVersion(), retained.getVersion());
        assertEquals(
                0,
                receivableLogs
                        .createQuery()
                        .where("receivableId", bill.getId())
                        .count()
                        .block(WAIT));
        conflict(
                receivables.change(
                        project,
                        bill.getId(),
                        new ReceivableService.ChangeInput(bill.getVersion(), " "),
                        "FREEZE"),
                400);
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web.ReceivableController(
                                        receivables))
                        .webFilter(
                                (exchange, chain) -> chain.filter(exchange).contextWrite(allowed()))
                        .build();
        client.get()
                .uri("/property/finance/projects/" + project + "/receivables/" + bill.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.actualReceivableAmount")
                .isEqualTo("25.51")
                .jsonPath("$.snapshot.standard.price")
                .isEqualTo("1.23")
                .jsonPath("$.snapshotJson")
                .doesNotExist()
                .jsonPath("$.allowedOperations")
                .isArray();
    }

    @Test
    void adjustmentPreviewHasNoWritesAndPublishMatchesPreviewWithImmutableSourceAndAudit() {
        var saved =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var bill =
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        var input = new org.jetlinks.pro.property.finance.receivable.AdjustmentInput();
        input.setVersion(bill.getVersion());
        input.setAdjustmentMode("DIRECT");
        input.setDirectDirection("INCREASE");
        input.setDirectAmount("10.00");
        input.setAssessmentAmount("2.00");
        input.setTemporaryDispatchCount(2);
        input.setTemporaryDispatchUnitPrice("3.50");
        input.setFeeReductionAmount("1.00");
        input.setReason("补充本期费用");
        input.setAttachmentFileIds(List.of("file-id"));
        var preview =
                adjustments
                        .preview(project, bill.getId(), input, DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(bill.getVersion(), bills.findById(bill.getId()).block(WAIT).getVersion());
        assertEquals(
                0,
                receivableLogs
                        .createQuery()
                        .where("receivableId", bill.getId())
                        .count()
                        .block(WAIT));
        input.setRemark("改动输入");
        conflict(
                adjustments.submit(
                        project,
                        bill.getId(),
                        new ReceivableAdjustmentService.SubmitInput(input, preview.token()),
                        DATE),
                409);
        input.setRemark(null);
        conflict(
                adjustments.submit(
                        project,
                        bill.getId(),
                        new ReceivableAdjustmentService.SubmitInput(input, preview.token()),
                        DATE.plusDays(1)),
                409);
        var published =
                adjustments
                        .submit(
                                project,
                                bill.getId(),
                                new ReceivableAdjustmentService.SubmitInput(input, preview.token()),
                                DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(
                preview.effectiveAfter().getActualReceivableAmount(),
                published.getActualReceivableAmount());
        assertEquals(new BigDecimal("43.51"), published.getActualReceivableAmount());
        assertEquals(
                bill.getSnapshotJson(), bills.findById(bill.getId()).block(WAIT).getSnapshotJson());
        var log =
                receivables
                        .logs(
                                project,
                                bill.getId(),
                                new org.hswebframework.web.api.crud.entity.QueryParamEntity())
                        .contextWrite(allowed())
                        .block(WAIT)
                        .getData()
                        .get(0);
        assertEquals("ADJUST", log.getOperation());
        var logInput = (Map<?, ?>) log.getDetail().get("input");
        assertEquals(List.of("file-id"), logInput.get("attachmentFileIds"));
        assertEquals("25.51", log.getBefore().get("actualReceivableAmount"));
        assertEquals("43.51", log.getAfter().get("actualReceivableAmount"));
        conflict(
                adjustments.submit(
                        project,
                        bill.getId(),
                        new ReceivableAdjustmentService.SubmitInput(input, preview.token()),
                        DATE),
                409);
        generation
                .generate(project, saved.setting().getId(), DATE.plusDays(1))
                .contextWrite(allowed())
                .block(WAIT);
        assertEquals(
                new BigDecimal("47.76"),
                bills.findById(bill.getId()).block(WAIT).getActualReceivableAmount());
    }

    @Test
    void formulaAdjustmentUsesFrozenStandardAndConcurrentPublishOnlySucceedsOnce() {
        var saved =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var bill =
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        var input = new org.jetlinks.pro.property.finance.receivable.AdjustmentInput();
        input.setVersion(bill.getVersion());
        input.setAdjustmentMode("FORMULA");
        input.setReason("调整计费面积");
        var line = new org.jetlinks.pro.property.finance.receivable.AdjustmentInput.FormulaLine();
        line.setSubjectId(subjectId);
        line.setStandardId(standardId);
        line.setAfterVariableValues(Map.of("BILLING_AREA", "200.50"));
        input.setFormulaLine(line);
        standards
                .createUpdate()
                .set("price", new BigDecimal("99"))
                .where("id", standardId)
                .execute()
                .block(WAIT);
        var preview =
                adjustments
                        .preview(project, bill.getId(), input, DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(new BigDecimal("85.04"), preview.delta());
        var submit = new ReceivableAdjustmentService.SubmitInput(input, preview.token());
        var results =
                Flux.merge(
                                adjustments
                                        .submit(project, bill.getId(), submit, DATE)
                                        .map(row -> true)
                                        .onErrorReturn(false),
                                adjustments
                                        .submit(project, bill.getId(), submit, DATE)
                                        .map(row -> true)
                                        .onErrorReturn(false))
                        .collectList()
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(
                1,
                receivableLogs
                        .createQuery()
                        .where("receivableId", bill.getId())
                        .count()
                        .block(WAIT));
        conflict(adjustments.preview("other", bill.getId(), input, DATE), 404);
        conflict(adjustments.preview("forbidden", bill.getId(), input, DATE), 403);
    }

    @Test
    void adjustmentAuditFailureRollsBackAmountsAndHttpPreviewUsesPreciseSafeContract() {
        var saved =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var bill =
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        var input = new org.jetlinks.pro.property.finance.receivable.AdjustmentInput();
        input.setVersion(bill.getVersion());
        input.setAdjustmentMode("DIRECT");
        input.setDirectDirection("INCREASE");
        input.setDirectAmount("10.00");
        input.setReason("核对金额");
        var preview =
                adjustments
                        .preview(project, bill.getId(), input, DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        @SuppressWarnings("unchecked")
        ReactiveRepository<ReceivableLogEntity, String> failedLogs = mock(ReactiveRepository.class);
        when(failedLogs.insert(any(ReceivableLogEntity.class)))
                .thenReturn(Mono.error(new IllegalStateException("audit unavailable")));
        var failedService =
                new ReceivableAdjustmentService(
                        financeScope, periodGate, adjustmentRules, bills, failedLogs);
        StepVerifier.create(
                        org.springframework.transaction.reactive.TransactionalOperator.create(
                                        transactionManager)
                                .transactional(
                                        failedService.submit(
                                                project,
                                                bill.getId(),
                                                new ReceivableAdjustmentService.SubmitInput(
                                                        input, preview.token()),
                                                DATE))
                                .contextWrite(allowed()))
                .expectErrorMessage("audit unavailable")
                .verify(WAIT);
        var retained = bills.findById(bill.getId()).block(WAIT);
        assertEquals(bill.getVersion(), retained.getVersion());
        assertEquals(bill.getActualReceivableAmount(), retained.getActualReceivableAmount());
        assertEquals(bill.getSnapshotJson(), retained.getSnapshotJson());
        assertEquals(
                0,
                receivableLogs
                        .createQuery()
                        .where("receivableId", bill.getId())
                        .count()
                        .block(WAIT));
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web
                                        .ReceivableAdjustmentController(adjustments, changeAudit))
                        .webFilter(
                                (exchange, chain) -> chain.filter(exchange).contextWrite(allowed()))
                        .build();
        client.post()
                .uri(
                        "/property/finance/projects/"
                                + project
                                + "/receivables/"
                                + bill.getId()
                                + "/adjustment/_preview")
                .bodyValue(input)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.delta")
                .isEqualTo("10.00")
                .jsonPath("$.effectiveAfter.actualReceivableAmount")
                .isEqualTo("95.04")
                .jsonPath("$.effectiveAfter.snapshotJson")
                .doesNotExist()
                .jsonPath("$.after")
                .doesNotExist()
                .jsonPath("$.token")
                .isNotEmpty();
    }

    @Test
    void splitArchivesSourceAndConcurrentSubmitConservesAmountsThroughFutureAccrual() {
        var saved =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var source =
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        var input =
                new org.jetlinks.pro.property.finance.receivable.SplitInput(
                        source.getVersion(),
                        LocalDate.of(2024, 2, 12),
                        0,
                        "link",
                        "link",
                        "按搬迁日期拆分");
        var preview =
                splits.preview(project, source.getId(), input, DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("13.00", preview.parts().get(0).amount());
        assertEquals("12.51", preview.parts().get(1).amount());
        assertEquals("25.51", preview.total());
        assertEquals(
                1,
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .count()
                        .block(WAIT));
        var invalid =
                new org.jetlinks.pro.property.finance.receivable.SplitInput(
                        source.getVersion(), input.splitDate(), 2, "link", "not-this-house", "拆分");
        conflict(splits.preview(project, source.getId(), invalid, DATE), 400);
        var request = new ReceivableSplitService.Submit(input, preview.token());
        var results =
                Flux.merge(
                                splits.submit(project, source.getId(), request, DATE)
                                        .map(rows -> true)
                                        .onErrorReturn(false),
                                splits.submit(project, source.getId(), request, DATE)
                                        .map(rows -> true)
                                        .onErrorReturn(false))
                        .collectList()
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertTrue(bills.findById(source.getId()).block(WAIT).getSplit());
        var active =
                receivables
                        .query(
                                project,
                                new org.hswebframework.web.api.crud.entity.QueryParamEntity())
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(2, active.getTotal());
        assertEquals(
                new BigDecimal("25.51"),
                active.getData().stream()
                        .map(ReceivableEntity::getActualReceivableAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        generation
                .generate(project, saved.setting().getId(), DATE.plusDays(1))
                .contextWrite(allowed())
                .block(WAIT);
        var children =
                bills.createQuery()
                        .where("sourceReceivableId", source.getId())
                        .fetch()
                        .collectList()
                        .block(WAIT);
        assertEquals(
                new BigDecimal("29.76"),
                children.stream()
                        .map(ReceivableEntity::getActualReceivableAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(
                new BigDecimal("85.04"),
                children.stream()
                        .map(ReceivableEntity::getContractReceivableAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(
                3,
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .count()
                        .block(WAIT));
        assertEquals(
                "25.51",
                bills.findById(source.getId())
                        .block(WAIT)
                        .getActualReceivableAmount()
                        .toPlainString());
        assertEquals(
                1,
                receivableLogs
                        .createQuery()
                        .where("receivableId", source.getId())
                        .count()
                        .block(WAIT));
        conflict(splits.submit(project, source.getId(), request, DATE), 409);
    }

    @Test
    void splitAuditFailureRollsBackArchivedFlagAndBothChildren() {
        var saved =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var source =
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        var input =
                new org.jetlinks.pro.property.finance.receivable.SplitInput(
                        source.getVersion(), LocalDate.of(2024, 2, 12), 2, "link", "link", "拆分");
        var preview =
                splits.preview(project, source.getId(), input, DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        @SuppressWarnings("unchecked")
        ReactiveRepository<ReceivableLogEntity, String> failedLogs = mock(ReactiveRepository.class);
        when(failedLogs.insert(any(ReceivableLogEntity.class)))
                .thenReturn(Mono.error(new IllegalStateException("audit unavailable")));
        var service =
                new ReceivableSplitService(
                        financeScope, periodGate, archive, splitRules, bills, failedLogs);
        StepVerifier.create(
                        org.springframework.transaction.reactive.TransactionalOperator.create(
                                        transactionManager)
                                .transactional(
                                        service.submit(
                                                project,
                                                source.getId(),
                                                new ReceivableSplitService.Submit(
                                                        input, preview.token()),
                                                DATE))
                                .contextWrite(allowed()))
                .expectErrorMessage("audit unavailable")
                .verify(WAIT);
        assertFalse(bills.findById(source.getId()).block(WAIT).getSplit());
        assertEquals(source.getVersion(), bills.findById(source.getId()).block(WAIT).getVersion());
        assertEquals(
                1,
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .count()
                        .block(WAIT));
    }

    @Test
    void splitUpgradeSqlBackfillsRootIdentityAndCanBeRepeated() throws Exception {
        var saved =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var source =
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        var legacyNullable =
                DB.execInContainer(
                        "psql",
                        "-U",
                        "postgres",
                        "-d",
                        "setting_test",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-c",
                        "ALTER TABLE public.pm_fin_receivable ALTER COLUMN bill_key DROP NOT NULL");
        assertEquals(0, legacyNullable.getExitCode(), legacyNullable.getStderr());
        bills.createUpdate().setNull("billKey").where("id", source.getId()).execute().block(WAIT);
        var oldIndex =
                DB.execInContainer(
                        "psql",
                        "-U",
                        "postgres",
                        "-d",
                        "setting_test",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-c",
                        "CREATE UNIQUE INDEX uk_pm_fin_recv_period ON public.pm_fin_receivable"
                                + " (setting_id,period_start,period_end)");
        assertEquals(0, oldIndex.getExitCode(), oldIndex.getStderr());
        var sql =
                java.nio.file.Files.readString(
                        java.nio.file.Path.of("../sql/migrate-receivable-split.sql"));
        for (int i = 0; i < 2; i++) {
            var result =
                    DB.execInContainer(
                            "psql",
                            "-U",
                            "postgres",
                            "-d",
                            "setting_test",
                            "-v",
                            "ON_ERROR_STOP=1",
                            "-c",
                            sql);
            assertEquals(0, result.getExitCode(), result.getStderr());
        }
        assertEquals(source.getBillKey(), bills.findById(source.getId()).block(WAIT).getBillKey());
        var input =
                new org.jetlinks.pro.property.finance.receivable.SplitInput(
                        source.getVersion(), LocalDate.of(2024, 2, 12), 2, "link", "link", "拆分");
        var preview =
                splits.preview(project, source.getId(), input, DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(
                2,
                splits.submit(
                                project,
                                source.getId(),
                                new ReceivableSplitService.Submit(input, preview.token()),
                                DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .size());
    }

    @Test
    void splitHttpUsesStringsForMoneyAndReturnsOnlyImplementedActions() {
        var saved =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var source =
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        var row = receivables.detail(project, source.getId()).contextWrite(allowed()).block(WAIT);
        assertTrue(row.getAllowedOperations().containsAll(Set.of("ADJUST", "SPLIT")));
        var input =
                new org.jetlinks.pro.property.finance.receivable.SplitInput(
                        source.getVersion(), LocalDate.of(2024, 2, 12), 2, "link", "link", "拆分");
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web.ReceivableSplitController(
                                        splits, changeAudit))
                        .webFilter(
                                (exchange, chain) -> chain.filter(exchange).contextWrite(allowed()))
                        .build();
        var path =
                "/property/finance/projects/"
                        + project
                        + "/receivables/"
                        + source.getId()
                        + "/split";
        client.get()
                .uri(path + "/_options")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].name")
                .isEqualTo("龚超");
        var body =
                client.post()
                        .uri(path + "/_preview")
                        .bodyValue(input)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("$.parts[0].amount")
                        .isEqualTo("12.76")
                        .jsonPath("$.total")
                        .isEqualTo("25.51")
                        .returnResult()
                        .getResponseBody();
        var token =
                com.alibaba
                        .fastjson
                        .JSON
                        .parseObject(new String(body, java.nio.charset.StandardCharsets.UTF_8))
                        .getString("token");
        client.post()
                .uri(path)
                .bodyValue(new ReceivableSplitService.Submit(input, token))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("PENDING")
                .jsonPath("$.receivableId")
                .isEqualTo(source.getId())
                .jsonPath("$.previewJson")
                .doesNotExist();
    }

    @Test
    void historicalPendingEditRevalidatesAndApprovalPublishesOnlyRevisedAmount() throws Exception {
        mockHistoricalHouses();
        var record =
                historical
                        .importFile(project, historicalPart(historicalFile("100.00", true)))
                        .contextWrite(allowed())
                        .block(WAIT);
        var draft =
                importDrafts
                        .createQuery()
                        .where("importId", record.getId())
                        .and("lineNumber", 2)
                        .fetchOne()
                        .block(WAIT);
        var bill =
                com.alibaba.fastjson.JSON.parseObject(
                        draft.getPayloadJson(), ReceivableEntity.class);
        var invalid = historicalEdit(record, draft, bill, "90.00");
        StepVerifier.create(
                        importEdits
                                .edit(project, record.getId(), draft.getId(), invalid)
                                .contextWrite(allowed()))
                .expectErrorMatches(
                        e ->
                                e instanceof org.hswebframework.web.exception.BusinessException
                                        && ((org.hswebframework.web.exception.BusinessException) e)
                                                .getI18nCode()
                                                .contains("historical_row"))
                .verify(WAIT);
        assertEquals(
                draft.getVersion(), importDrafts.findById(draft.getId()).block(WAIT).getVersion());
        bill.setContractReceivableAmount(new BigDecimal("120.25"));
        var input = historicalEdit(record, draft, bill, "120.00");
        var changed =
                importEdits
                        .edit(project, record.getId(), draft.getId(), input)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(draft.getVersion() + 1, changed.line().version());
        assertEquals(record.getVersion() + 1, changed.record().getVersion());
        assertEquals("已校正", changed.line().receivable().getRemark());
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
        StepVerifier.create(
                        importEdits
                                .edit(project, record.getId(), draft.getId(), input)
                                .contextWrite(allowed()))
                .expectError(org.hswebframework.web.exception.BusinessException.class)
                .verify(WAIT);
        importAudit
                .decide(
                        project,
                        record.getId(),
                        new HistoricalImportAuditService.Decision(
                                changed.record().getVersion(), "核对", List.of()),
                        true)
                .contextWrite(allowed())
                .block(WAIT);
        assertEquals("已校正", bills.findById(draft.getId()).block(WAIT).getRemark());
        assertEquals(
                new BigDecimal("120.00"), bills.findById(draft.getId()).block(WAIT).getArrears());
        StepVerifier.create(
                        importEdits
                                .edit(
                                        project,
                                        record.getId(),
                                        draft.getId(),
                                        historicalEdit(
                                                changed.record(),
                                                importDrafts.findById(draft.getId()).block(WAIT),
                                                bill,
                                                "100.00"))
                                .contextWrite(allowed()))
                .expectError(org.hswebframework.web.exception.BusinessException.class)
                .verify(WAIT);
        assertEquals(
                1,
                receivableLogs
                        .createQuery()
                        .where("projectId", project)
                        .and("operation", "IMPORT_EDIT")
                        .count()
                        .block(WAIT));
    }

    @Test
    void historicalImportedAmountSurvivesAdjustmentAndDailyTasksWithoutRegeneration()
            throws Exception {
        mockHistoricalHouses();
        importAndApprove(historicalPart(historicalFile("100.00", false)))
                .contextWrite(allowed())
                .block(WAIT);
        var bill = bills.createQuery().where("projectId", project).fetchOne().block(WAIT);
        var input = new org.jetlinks.pro.property.finance.receivable.AdjustmentInput();
        input.setVersion(bill.getVersion());
        input.setAdjustmentMode("DIRECT");
        input.setDirectDirection("INCREASE");
        input.setDirectAmount("10.00");
        input.setReason("补收历史费用");
        var today = LocalDate.now();
        var preview =
                adjustments
                        .preview(project, bill.getId(), input, today)
                        .contextWrite(allowed())
                        .block(WAIT);
        adjustments
                .submit(
                        project,
                        bill.getId(),
                        new ReceivableAdjustmentService.SubmitInput(input, preview.token()),
                        today)
                .contextWrite(allowed())
                .block(WAIT);
        // 后续修改标准单价/滞纳金定义，均不能替换导入时冻结的快照。
        standards
                .createUpdate()
                .set("price", new BigDecimal("999.00"))
                .set("isDelay", 1)
                .set("rateAmount", new BigDecimal("50.00"))
                .where("id", standardId)
                .execute()
                .block(WAIT);
        tasks.scan(BillingTaskKind.GENERATE, today.plusDays(1)).block(WAIT);
        tasks.executeReceivable(bill, BillingTaskKind.ACCRUE, today.plusDays(1)).block(WAIT);
        tasks.executeReceivable(bill, BillingTaskKind.LATE_FEE, today.plusDays(1)).block(WAIT);
        var after = bills.findById(bill.getId()).block(WAIT);
        assertEquals(new BigDecimal("100.25"), after.getContractReceivableAmount());
        assertEquals(new BigDecimal("110.00"), after.getActualReceivableAmount());
        assertEquals(new BigDecimal("110.00"), after.getArrears());
        assertEquals(new BigDecimal("10.00"), after.getAdjustmentAmount());
        assertEquals(0, after.getAccruedLateFeeAmount().signum());
        assertEquals(1, bills.createQuery().where("projectId", project).count().block(WAIT));
        var version = after.getVersion();
        tasks.executeReceivable(bill, BillingTaskKind.LATE_FEE, today.plusDays(1)).block(WAIT);
        assertEquals(version, bills.findById(bill.getId()).block(WAIT).getVersion());
    }

    @Test
    void historicalPendingEditRejectsDuplicatesForeignScopeAndClosedOriginalPeriod()
            throws Exception {
        mockHistoricalHouses();
        var record =
                historical
                        .importFile(project, historicalPart(historicalFile("100.00", true)))
                        .contextWrite(allowed())
                        .block(WAIT);
        var first =
                importDrafts
                        .createQuery()
                        .where("importId", record.getId())
                        .and("lineNumber", 2)
                        .fetchOne()
                        .block(WAIT);
        var second =
                importDrafts
                        .createQuery()
                        .where("importId", record.getId())
                        .and("lineNumber", 3)
                        .fetchOne()
                        .block(WAIT);
        var original =
                com.alibaba.fastjson.JSON.parseObject(
                        first.getPayloadJson(), ReceivableEntity.class);
        var duplicate =
                com.alibaba.fastjson.JSON.parseObject(
                        second.getPayloadJson(), ReceivableEntity.class);
        StepVerifier.create(
                        importEdits
                                .edit(
                                        project,
                                        record.getId(),
                                        first.getId(),
                                        historicalEdit(record, first, duplicate, "100.00"))
                                .contextWrite(allowed()))
                .expectError(org.hswebframework.web.exception.BusinessException.class)
                .verify(WAIT);
        StepVerifier.create(
                        importEdits
                                .edit(
                                        "foreign-project",
                                        record.getId(),
                                        first.getId(),
                                        historicalEdit(record, first, original, "100.00"))
                                .contextWrite(allowed()))
                .expectError(org.hswebframework.web.exception.BusinessException.class)
                .verify(WAIT);
        var denied = mock(AssetsHolder.class);
        when(denied.assertPermission(anyString(), anyCollection(), any()))
                .thenReturn(Mono.error(new IllegalStateException("space denied")));
        StepVerifier.create(
                        importEdits
                                .edit(
                                        project,
                                        record.getId(),
                                        first.getId(),
                                        historicalEdit(record, first, original, "100.00"))
                                .contextWrite(allowed().put(AssetsHolder.class, denied)))
                .expectError()
                .verify(WAIT);
        financialPeriods
                .close(project, LocalDate.of(2023, 1, 31))
                .contextWrite(allowed())
                .block(WAIT);
        original.setCalcStartDate("2023-03-01");
        original.setCalcEndDate("2023-03-31");
        original.setAccountPeriod("2023-03");
        StepVerifier.create(
                        importEdits
                                .edit(
                                        project,
                                        record.getId(),
                                        first.getId(),
                                        historicalEdit(record, first, original, "100.00"))
                                .contextWrite(allowed()))
                .expectErrorMatches(
                        e ->
                                ((org.hswebframework.web.exception.BusinessException) e)
                                        .getI18nCode()
                                        .contains("period_closed"))
                .verify(WAIT);
        assertEquals(
                first.getVersion(), importDrafts.findById(first.getId()).block(WAIT).getVersion());
        assertEquals(
                first.getPayloadJson(),
                importDrafts.findById(first.getId()).block(WAIT).getPayloadJson());
        assertEquals(
                record.getVersion(),
                importRecords.findById(record.getId()).block(WAIT).getVersion());
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
    }

    private HistoricalImportEditService.Input historicalEdit(
            ReceivableImportEntity record,
            ReceivableImportLineEntity draft,
            ReceivableEntity bill,
            String actual) {
        return new HistoricalImportEditService.Input(
                record.getVersion(),
                draft.getVersion(),
                bill.getFullName(),
                bill.getCustomerName(),
                bill.getSubjectName(),
                bill.getStandardName(),
                bill.getContractCode(),
                bill.getCalcStartDate(),
                bill.getCalcEndDate(),
                bill.getAccountPeriod(),
                bill.getDueDate(),
                bill.getContractReceivableAmount(),
                bill.getFeeReductionAmount(),
                new BigDecimal(actual),
                "已校正");
    }

    private Mono<ReceivableImportEntity> importAndApprove(
            org.springframework.http.codec.multipart.FilePart file) {
        return historical
                .importFile(project, file)
                .flatMap(
                        record ->
                                "PENDING".equals(record.getStatus())
                                        ? importAudit.decide(
                                                project,
                                                record.getId(),
                                                new HistoricalImportAuditService.Decision(
                                                        record.getVersion(), "核对历史应收", List.of()),
                                                true)
                                        : Mono.just(record));
    }

    @Test
    void historicalReviewStagesThenApprovesSelectedRowsAndRetainsRejectedHistory()
            throws Exception {
        mockHistoricalHouses();
        var record =
                historical
                        .importFile(project, historicalPart(historicalFile("100.00", true)))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("PENDING", record.getStatus());
        assertEquals(0, record.getSuccessCount());
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
        var page =
                importAudit
                        .lines(
                                project,
                                record.getId(),
                                new org.hswebframework.web.api.crud.entity.QueryParamEntity())
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(2, page.getTotal());
        assertEquals(2, page.getData().get(0).lineNumber());
        assertEquals(
                "100.00",
                page.getData().get(0).receivable().getActualReceivableAmount().toPlainString());
        StepVerifier.create(
                        importAudit
                                .decide(
                                        "foreign-project",
                                        record.getId(),
                                        new HistoricalImportAuditService.Decision(
                                                record.getVersion(), "跨项目", List.of()),
                                        true)
                                .contextWrite(allowed()))
                .expectError(org.hswebframework.web.exception.BusinessException.class)
                .verify(WAIT);
        var row = page.getData().get(0);
        var input =
                new HistoricalImportAuditService.Decision(
                        record.getVersion(),
                        "核对第2行",
                        List.of(new HistoricalImportAuditService.Item(row.id(), row.version())));
        var denied = mock(AssetsHolder.class);
        when(denied.assertPermission(anyString(), anyCollection(), any()))
                .thenReturn(Mono.error(new IllegalStateException("space denied")));
        StepVerifier.create(
                        importAudit
                                .decide(project, record.getId(), input, true)
                                .contextWrite(allowed().put(AssetsHolder.class, denied)))
                .expectError()
                .verify(WAIT);
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
        var partial =
                importAudit
                        .decide(project, record.getId(), input, true)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("PENDING", partial.getStatus());
        assertEquals(1, partial.getSuccessCount());
        assertEquals(1, bills.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals("APPROVED", bills.findById(row.id()).block(WAIT).getReviewState());
        StepVerifier.create(
                        importAudit
                                .decide(project, record.getId(), input, true)
                                .contextWrite(allowed()))
                .expectError(org.hswebframework.web.exception.BusinessException.class)
                .verify(WAIT);
        var finalRecord =
                importAudit
                        .decide(
                                project,
                                record.getId(),
                                new HistoricalImportAuditService.Decision(
                                        partial.getVersion(), "剩余行资料不符", List.of()),
                                false)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("APPROVED", finalRecord.getStatus());
        assertEquals(1, finalRecord.getSuccessCount());
        assertEquals(1, finalRecord.getRejectedCount());
        var rejected =
                importDrafts
                        .createQuery()
                        .where("importId", record.getId())
                        .and("status", "REJECTED")
                        .fetchOne()
                        .block(WAIT);
        assertEquals("剩余行资料不符", rejected.getReviewReason());
        assertFalse(rejected.getDeleted());
        assertNull(bills.findById(rejected.getId()).block(WAIT));
    }

    @Test
    void historicalBatchReviewRollsBackEveryBatchOnDuplicateAndPeriodIsRechecked()
            throws Exception {
        mockHistoricalHouses();
        var first =
                historical
                        .importFile(project, historicalPart(historicalFile("100.00", false)))
                        .contextWrite(allowed())
                        .block(WAIT);
        var second =
                historical
                        .importFile(project, historicalPart(historicalFile("100.00", false)))
                        .contextWrite(allowed())
                        .block(WAIT);
        var input =
                new HistoricalImportAuditService.BatchDecision(
                        List.of(
                                new HistoricalImportAuditService.Item(
                                        first.getId(), first.getVersion()),
                                new HistoricalImportAuditService.Item(
                                        second.getId(), second.getVersion())),
                        "核对批次");
        StepVerifier.create(importAudit.decideBatches(project, input, true).contextWrite(allowed()))
                .expectErrorMatches(
                        e ->
                                e instanceof org.hswebframework.web.exception.BusinessException
                                        && ((org.hswebframework.web.exception.BusinessException) e)
                                                .getI18nCode()
                                                .contains("historical_row"))
                .verify(WAIT);
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                2,
                importDrafts
                        .createQuery()
                        .where("projectId", project)
                        .and("status", "PENDING")
                        .count()
                        .block(WAIT));
        assertEquals(
                first.getVersion(), importRecords.findById(first.getId()).block(WAIT).getVersion());
        assertEquals("PENDING", importRecords.findById(second.getId()).block(WAIT).getStatus());
        financialPeriods
                .close(project, LocalDate.now().minusDays(1))
                .contextWrite(allowed())
                .block(WAIT);
        FinancialPeriodTestSupport.rejectsClosed(
                importAudit
                        .decide(
                                project,
                                first.getId(),
                                new HistoricalImportAuditService.Decision(
                                        first.getVersion(), "关账后审核", List.of()),
                                true)
                        .contextWrite(allowed()));
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
        // 驳回只结束待审，不入账，已经关闭的期间也可保留驳回历史。
        var rejected =
                importAudit
                        .decideBatches(project, input, false)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertTrue(rejected.stream().allMatch(row -> "REJECTED".equals(row.getStatus())));
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void historicalReviewUpgradeSqlIsRepeatableAndPreservesExistingRows() throws Exception {
        var sql =
                java.nio.file.Files.readString(
                        java.nio.file.Path.of("../sql/migrate-historical-import-review.sql"));
        DB.copyFileToContainer(
                org.testcontainers.images.builder.Transferable.of(
                        sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "/tmp/import-review.sql");
        for (int run = 0; run < 2; run++) {
            var result =
                    DB.execInContainer(
                            "psql",
                            "-U",
                            "postgres",
                            "-d",
                            "setting_test",
                            "-v",
                            "ON_ERROR_STOP=1",
                            "-f",
                            "/tmp/import-review.sql");
            assertEquals(0, result.getExitCode(), result.getStderr());
        }
        mockHistoricalHouses();
        var pending =
                historical
                        .importFile(project, historicalPart(historicalFile("100.00", false)))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(
                1,
                importDrafts.createQuery().where("importId", pending.getId()).count().block(WAIT));
    }

    @Test
    void receivableTaxProjectionUsesInvoiceFactsAndNetReversalsOnly() throws Exception {
        mockHistoricalHouses();
        importAndApprove(historicalPart(historicalFile("100.00", false)))
                .contextWrite(allowed())
                .block(WAIT);
        var row = bills.createQuery().where("projectId", project).fetchOne().block(WAIT);
        assertNull(
                presentation
                        .views(List.of(row), "测试项目", false)
                        .block(WAIT)
                        .get(0)
                        .getOutputTaxAmount());
        taxDocument(row, project, "RECEIPT", "100.00", "40.00");
        taxDocument(row, "foreign-project", "INVOICE", "100.00", "50.00");
        assertNull(
                presentation
                        .views(List.of(row), "测试项目", false)
                        .block(WAIT)
                        .get(0)
                        .getOutputTaxAmount());
        taxDocument(row, project, "INVOICE", "100.00", "6.00");
        var blue = presentation.views(List.of(row), "测试项目", false).block(WAIT).get(0);
        assertEquals("6.00", blue.getOutputTaxAmount().toPlainString());
        assertEquals("94.00", blue.getIncomeExcludingTax().toPlainString());
        assertEquals("测试项目", blue.getProjectName());
        taxDocument(row, project, "INVOICE", "-100.00", "-6.00");
        assertNull(
                presentation
                        .views(List.of(row), "测试项目", false)
                        .block(WAIT)
                        .get(0)
                        .getOutputTaxAmount());
        taxDocument(row, project, "INVOICE", "100.00", "3.00");
        var reissued = presentation.views(List.of(row), "测试项目", false).block(WAIT).get(0);
        assertEquals("3.00", reissued.getOutputTaxAmount().toPlainString());
        assertEquals("97.00", reissued.getIncomeExcludingTax().toPlainString());
    }

    private void taxDocument(
            ReceivableEntity bill, String projectId, String type, String amount, String tax) {
        var doc = new DocumentEntity();
        doc.setId(UUID.randomUUID().toString());
        StandardPersistenceTest.initialize(doc);
        doc.setTenantId("tenant");
        doc.setProjectId(projectId);
        doc.setCreatorId("user");
        doc.setDocumentType(type);
        doc.setChannel("SIMULATED");
        doc.setStatus("ISSUED");
        doc.setDocumentNumber(doc.getId());
        doc.setRootDocumentId(doc.getId());
        doc.setReopenCount(0);
        doc.setCompanyId("company");
        doc.setCompanyName("开票公司");
        doc.setSellerTaxNum("tax");
        doc.setCustomerId("customer");
        doc.setCustomerName("龚超");
        doc.setBuyerName("龚超");
        doc.setIssueScene("RECEIVABLE");
        doc.setIssuedAt(LocalDate.now().toString());
        doc.setRequestFingerprint(doc.getId());
        doc.setBuyerJson("{}");
        doc.setRuleJson("{}");
        doc.setAmount(new BigDecimal(amount));
        doc.setTaxAmount(new BigDecimal(tax));
        doc.setNetAmount(doc.getAmount().subtract(doc.getTaxAmount()));
        documents.insert(doc).block(WAIT);
        var line = new DocumentLineEntity();
        line.setId(UUID.randomUUID().toString());
        StandardPersistenceTest.initialize(line);
        line.setProjectId(projectId);
        line.setDocumentId(doc.getId());
        line.setLineIndex(1);
        line.setSpaceId(bill.getSpaceId());
        line.setSourceType("RECEIVABLE");
        line.setSourceId(bill.getId());
        line.setOriginalSourceType("RECEIVABLE");
        line.setOriginalSourceId(bill.getId());
        line.setSourceSnapshotJson("{}");
        line.setLineSnapshotJson("{}");
        line.setAmount(doc.getAmount());
        line.setTaxAmount(doc.getTaxAmount());
        line.setNetAmount(doc.getNetAmount());
        documentLines.insert(line).block(WAIT);
    }

    private byte[] historicalFile(String lastActual, boolean twoRows) throws Exception {
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                var out = new java.io.ByteArrayOutputStream()) {
            var sheet = book.createSheet("历史应收");
            var header = sheet.createRow(0);
            var headers = org.jetlinks.pro.property.finance.receivable.HistoricalWorkbook.HEADERS;
            for (int c = 0; c < headers.size(); c++)
                header.createCell(c).setCellValue(headers.get(c));
            for (int r = 1; r <= (twoRows ? 2 : 1); r++) {
                var row = sheet.createRow(r);
                var month = r == 1 ? "01" : "02";
                var values =
                        List.of(
                                "HT-1",
                                context("room").house().fullName(),
                                "龚超",
                                "物业费",
                                "面积月费",
                                "2023-" + month + "-01",
                                "2023-" + month + "-28",
                                "2023-" + month,
                                "2023-03-05",
                                "100.25",
                                "0.25",
                                r == (twoRows ? 2 : 1) ? lastActual : "100.00",
                                "迁入历史");
                for (int c = 0; c < values.size(); c++)
                    row.createCell(c).setCellValue(values.get(c));
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    private org.springframework.http.codec.multipart.FilePart historicalPart(byte[] bytes) {
        var part = mock(org.springframework.http.codec.multipart.FilePart.class);
        when(part.filename()).thenReturn("历史应收.xlsx");
        when(part.content())
                .thenAnswer(
                        call ->
                                Flux.just(
                                        new org.springframework.core.io.buffer
                                                        .DefaultDataBufferFactory()
                                                .wrap(bytes)));
        return part;
    }

    private void mockHistoricalHouses() {
        when(archive.houses(eq(project), isNull(), isNull(), anyInt(), eq(200)))
                .thenReturn(
                        Mono.just(
                                new PropertyHousePage(
                                        1, 0, 200, List.of(context("room").house()))));
    }

    @Test
    void historicalImportHasNoSettingAndWholeFileValidationPrecedesAllWrites() throws Exception {
        mockHistoricalHouses();
        var invalid =
                historical
                        .importFile(project, historicalPart(historicalFile("99.99", true)))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("FAILED", invalid.getStatus());
        assertEquals(0, invalid.getSuccessCount());
        assertTrue(invalid.getErrorMessage().contains("3"), invalid.getErrorMessage());
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
        var success =
                importAndApprove(historicalPart(historicalFile("100.00", true)))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("APPROVED", success.getStatus());
        assertEquals(2, success.getSuccessCount());
        var imported =
                bills.createQuery().where("projectId", project).fetch().collectList().block(WAIT);
        assertEquals(2, imported.size());
        assertTrue(imported.stream().allMatch(r -> r.getSettingId() == null));
        assertEquals(
                new BigDecimal("200.00"),
                imported.stream()
                        .map(ReceivableEntity::getActualReceivableAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        var row = imported.get(0);
        assertEquals("CLOSED", row.getPeriodStatus());
        assertEquals("HISTORICAL_IMPORT", row.getSourceType());
        assertEquals("HT-1", row.getContractCode());
        var standard = SettingRules.standard(row.getSnapshotJson());
        new ReceivableCalculationService(
                        new ReceivableAmountCalculator(
                                new ChargeFormulaParser(),
                                new ChargeFormulaEvaluator(),
                                new BillingProrationService()),
                        new ReceivableLateFeeCalculator())
                .calculate(row, standard, DATE, BillingTaskKind.ALL);
        assertEquals(new BigDecimal("100.00"), row.getActualReceivableAmount());
        var duplicate =
                importAndApprove(historicalPart(historicalFile("100.00", true)))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("FAILED", duplicate.getStatus());
        assertEquals(2, bills.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                4, receivableLogs.createQuery().where("projectId", project).count().block(WAIT));
        var history =
                historical
                        .records(
                                project,
                                new org.hswebframework.web.api.crud.entity.QueryParamEntity())
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(3, history.getTotal());
    }

    @Test
    void historicalAuditFailureRollsBackAllBillsAndPersistsFailedBatch() throws Exception {
        mockHistoricalHouses();
        @SuppressWarnings("unchecked")
        ReactiveRepository<ReceivableLogEntity, String> failedLogs = mock(ReactiveRepository.class);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        when(failedLogs.insert(any(ReceivableLogEntity.class)))
                .thenAnswer(
                        call ->
                                count.incrementAndGet() == 1
                                        ? receivableLogs.insert(
                                                call.getArgument(0, ReceivableLogEntity.class))
                                        : Mono.error(
                                                new IllegalStateException("audit unavailable")));
        var service =
                new HistoricalReceivableService(
                        financeScope,
                        periodGate,
                        archive,
                        standards,
                        subjectRows,
                        subjects,
                        bills,
                        failedLogs,
                        importRecords,
                        importDrafts,
                        transactionManager);
        var record =
                service.importFile(project, historicalPart(historicalFile("100.00", true)))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("FAILED", record.getStatus());
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                1, importRecords.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(2, count.get());
        assertEquals(
                0, receivableLogs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void historicalUpgradeCanRepeatAndConcurrentImportsCreateOnlyOneBill() throws Exception {
        var old =
                DB.execInContainer(
                        "psql",
                        "-U",
                        "postgres",
                        "-d",
                        "setting_test",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-c",
                        "CREATE SCHEMA historical_upgrade; CREATE TABLE"
                            + " historical_upgrade.pm_fin_receivable (LIKE public.pm_fin_receivable"
                            + " INCLUDING DEFAULTS); ALTER TABLE"
                            + " historical_upgrade.pm_fin_receivable ALTER COLUMN setting_id SET"
                            + " NOT NULL");
        assertEquals(0, old.getExitCode(), old.getStderr());
        var sql =
                java.nio.file.Files.readString(
                                java.nio.file.Path.of("../sql/migrate-historical-receivable.sql"))
                        .replace("public.", "historical_upgrade.");
        for (int i = 0; i < 2; i++) {
            var result =
                    DB.execInContainer(
                            "psql",
                            "-U",
                            "postgres",
                            "-d",
                            "setting_test",
                            "-v",
                            "ON_ERROR_STOP=1",
                            "-c",
                            sql);
            assertEquals(0, result.getExitCode(), result.getStderr());
        }
        // 其他项目同名科目超过游标批次，导入仍只锁本项目已下发科目，不在读游标中嵌套查询。
        Flux.range(0, 80)
                .concatMap(
                        index -> {
                            var other = new SubjectEntity();
                            other.setId(UUID.randomUUID().toString());
                            StandardPersistenceTest.initialize(other);
                            other.setCategoryId(other.getId());
                            other.setCode("K" + other.getId().replace("-", "").substring(0, 16));
                            other.setName("物业费");
                            other.setNameKey(other.getId());
                            other.setFeeType("1");
                            other.setEnabled(true);
                            other.setAutoInvoice(false);
                            other.setArrearsConfigId("arrears");
                            return subjectRows.insert(other);
                        })
                .then()
                .block(WAIT);
        mockHistoricalHouses();
        var bytes = historicalFile("100.00", false);
        var staged =
                Flux.merge(
                                historical
                                        .importFile(project, historicalPart(bytes))
                                        .contextWrite(allowed()),
                                historical
                                        .importFile(project, historicalPart(bytes))
                                        .contextWrite(allowed()))
                        .collectList()
                        .block(WAIT);
        assertTrue(staged.stream().allMatch(row -> "PENDING".equals(row.getStatus())));
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
        var result =
                Flux.fromIterable(staged)
                        .flatMap(
                                record ->
                                        importAudit
                                                .decide(
                                                        project,
                                                        record.getId(),
                                                        new HistoricalImportAuditService.Decision(
                                                                record.getVersion(),
                                                                "核对导入",
                                                                List.of()),
                                                        true)
                                                .map(ReceivableImportEntity::getStatus)
                                                .onErrorReturn("CONFLICT")
                                                .contextWrite(allowed()))
                        .collectList()
                        .block(WAIT);
        assertEquals(1, result.stream().filter("APPROVED"::equals).count());
        assertEquals(1, result.stream().filter("CONFLICT"::equals).count());
        assertEquals(1, bills.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                3, receivableLogs.createQuery().where("projectId", project).count().block(WAIT));
        var copied =
                DB.execInContainer(
                        "psql",
                        "-U",
                        "postgres",
                        "-d",
                        "setting_test",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-c",
                        "INSERT INTO historical_upgrade.pm_fin_receivable SELECT * FROM"
                                + " public.pm_fin_receivable WHERE project_id='"
                                + project
                                + "'");
        assertEquals(0, copied.getExitCode(), copied.getStderr());
    }

    @Test
    void historicalLargeStagesOverFiveHundredWithoutPublishingAndResumesCheckpoint()
            throws Exception {
        mockHistoricalHouses();
        when(archive.houses(eq(project), isNull(), anyString(), eq(0), eq(200)))
                .thenReturn(
                        Mono.just(
                                new PropertyHousePage(
                                        1, 0, 200, List.of(context("room").house()))));
        var batch = new ReceivableImportEntity();
        StandardPersistenceTest.initialize(batch);
        batch.setId(UUID.randomUUID().toString());
        batch.setProjectId(project);
        batch.setFileName("历史应收.xlsx");
        batch.setStatus("PROCESSING");
        batch.setTotalCount(0);
        batch.setSuccessCount(0);
        batch.setErrorCount(0);
        importRecords.insert(batch).block(WAIT);
        var directory = java.nio.file.Files.createTempDirectory("historical-large-");
        try {
            var source = directory.resolve("large.xlsx");
            var raw = directory.resolve("raw.jsonl");
            var prepared = directory.resolve("prepared.jsonl");
            try (var book = new org.apache.poi.xssf.streaming.SXSSFWorkbook(100);
                    var output = java.nio.file.Files.newOutputStream(source)) {
                var sheet = book.createSheet("历史应收");
                var headers =
                        org.jetlinks.pro.property.finance.receivable.HistoricalWorkbook.HEADERS;
                var head = sheet.createRow(0);
                for (int c = 0; c < headers.size(); c++)
                    head.createCell(c).setCellValue(headers.get(c));
                for (int n = 0; n < 601; n++) {
                    var day = java.time.LocalDate.of(2020, 1, 1).plusDays(n);
                    var row = sheet.createRow(n + 1);
                    var values =
                            java.util.List.of(
                                    "",
                                    context("room").house().fullName(),
                                    context("room").customers().get(0).name(),
                                    "物业费",
                                    "面积月费",
                                    day.toString(),
                                    day.toString(),
                                    java.time.YearMonth.from(day).toString(),
                                    day.toString(),
                                    "100.00",
                                    "0.00",
                                    "100.00",
                                    "");
                    for (int c = 0; c < values.size(); c++)
                        row.createCell(c).setCellValue(values.get(c));
                }
                book.write(output);
                book.dispose();
            }
            org.jetlinks.pro.property.finance.payment.FinanceExcelRows.spool(source, raw);
            historical
                    .processLarge(
                            project, batch.getId(), raw, prepared, (phase, count) -> Mono.empty())
                    .contextWrite(allowed())
                    .block(java.time.Duration.ofMinutes(3));
            assertEquals(
                    601,
                    importDrafts
                            .createQuery()
                            .where("importId", batch.getId())
                            .count()
                            .block(WAIT));
            assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
            historical
                    .processLarge(
                            project, batch.getId(), raw, prepared, (phase, count) -> Mono.empty())
                    .contextWrite(allowed())
                    .block(java.time.Duration.ofMinutes(3));
            assertEquals(
                    601,
                    importDrafts
                            .createQuery()
                            .where("importId", batch.getId())
                            .count()
                            .block(WAIT));
        } finally {
            try (var files = java.nio.file.Files.list(directory)) {
                for (var file : files.toList()) java.nio.file.Files.delete(file);
            }
            java.nio.file.Files.delete(directory);
        }
    }

    @Test
    void historicalHttpTemplateUploadAndHistoryUseProjectScope() throws Exception {
        mockHistoricalHouses();
        var queue = mock(FinanceImportQueue.class);
        when(queue.reviewHistorical(anyString(), any(), anyBoolean()))
                .thenAnswer(
                        call ->
                                importAudit.decideBatches(
                                        call.getArgument(0),
                                        call.getArgument(1),
                                        call.getArgument(2)));
        when(queue.uploadHistorical(anyString(), any()))
                .thenAnswer(
                        call -> historical.importFile(call.getArgument(0), call.getArgument(1)));
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web
                                        .HistoricalReceivableController(historical, queue),
                                new org.jetlinks.pro.property.finance.web
                                        .HistoricalImportAuditController(
                                        importAudit, queue, importEdits))
                        .webFilter(
                                (exchange, chain) -> chain.filter(exchange).contextWrite(allowed()))
                        .build();
        var path = "/property/finance/projects/" + project + "/receivables/imports";
        var template =
                client.get()
                        .uri(path + "/template.xlsx")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectHeader()
                        .contentType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        .expectBody()
                        .returnResult()
                        .getResponseBody();
        try (var book =
                org.apache.poi.ss.usermodel.WorkbookFactory.create(
                        new java.io.ByteArrayInputStream(template))) {
            assertEquals("房屋完整路径", book.getSheetAt(0).getRow(0).getCell(1).getStringCellValue());
            assertEquals(1, book.getSheetAt(0).getLastRowNum());
            assertEquals("张某", book.getSheetAt(0).getRow(1).getCell(2).getStringCellValue());
            assertNull(book.getSheet("填写示例"));
            assertEquals(
                    context("room").house().fullName(),
                    book.getSheet("项目房屋与客户").getRow(1).getCell(0).getStringCellValue());
        }
        var body = new org.springframework.http.client.MultipartBodyBuilder();
        body.part(
                        "file",
                        new org.springframework.core.io.ByteArrayResource(
                                historicalFile("100.00", false)))
                .filename("历史应收.xlsx");
        client.post()
                .uri(path)
                .body(
                        org.springframework.web.reactive.function.BodyInserters.fromMultipartData(
                                body.build()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("PENDING")
                .jsonPath("$.successCount")
                .isEqualTo(0);
        var query = new org.hswebframework.web.api.crud.entity.QueryParamEntity();
        query.toQuery().or("projectId", "other-project");
        client.post()
                .uri(path + "/_query")
                .bodyValue(query)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(0);
        client.post()
                .uri(path + "/_query")
                .bodyValue(new org.hswebframework.web.api.crud.entity.QueryParamEntity())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1)
                .jsonPath("$.data[0].projectId")
                .isEqualTo(project);
        var pending =
                importRecords.createQuery().where("projectId", project).fetchOne().block(WAIT);
        assertEquals(
                0,
                receivables
                        .query(
                                project,
                                new org.hswebframework.web.api.crud.entity.QueryParamEntity())
                        .contextWrite(allowed())
                        .block(WAIT)
                        .getTotal());
        client.post()
                .uri(path + "/" + pending.getId() + "/lines/_query")
                .bodyValue(new org.hswebframework.web.api.crud.entity.QueryParamEntity())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data[0].lineNumber")
                .isEqualTo(2)
                .jsonPath("$.data[0].receivable.actualReceivableAmount")
                .isEqualTo("100.00");
        client.post()
                .uri(path + "/" + pending.getId() + "/_approve")
                .bodyValue(
                        new HistoricalImportAuditService.Decision(
                                pending.getVersion(), "核对通过", List.of()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("APPROVED")
                .jsonPath("$.successCount")
                .isEqualTo(1);
        assertEquals(
                1,
                receivables
                        .query(
                                project,
                                new org.hswebframework.web.api.crud.entity.QueryParamEntity())
                        .contextWrite(allowed())
                        .block(WAIT)
                        .getTotal());
        StepVerifier.create(importAndApprove(historicalPart(historicalFile("100.00", false))))
                .expectError(
                        org.hswebframework.web.authorization.exception.UnAuthorizedException.class)
                .verify(WAIT);
        assertEquals(
                1, importRecords.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void batchHttpUsesServerMoneyAndRejectsForeignProjectAndAnonymousRequests() {
        var saved =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var row =
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        var controller =
                new org.jetlinks.pro.property.finance.web.ReceivableBatchController(batches);
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                controller)
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) -> chain.filter(exchange).contextWrite(allowed()))
                        .build();
        var path = "/property/finance/projects/" + project + "/receivables";
        var input =
                new ReceivableBatchService.Input(
                        List.of(new ReceivableBatchService.Item(row.getId(), row.getVersion())),
                        "误出账");
        client.post()
                .uri(path + "/notice/_preview")
                .bodyValue(input)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(row.getArrears().toPlainString())
                .jsonPath("$.rows[0].amount")
                .isEqualTo(row.getArrears().toPlainString());
        client.post()
                .uri(path.replace(project, "foreign-project") + "/_batch-delete")
                .bodyValue(input)
                .exchange()
                .expectStatus()
                .isNotFound();
        assertFalse(bills.findById(row.getId()).block(WAIT).getDeleted());
        StepVerifier.create(batches.delete(project, input))
                .expectError(
                        org.hswebframework.web.authorization.exception.UnAuthorizedException.class)
                .verify(WAIT);
        client.post()
                .uri(path + "/_batch-delete")
                .bodyValue(input)
                .exchange()
                .expectStatus()
                .isOk();
        assertTrue(bills.findById(row.getId()).block(WAIT).getDeleted());
    }

    @Test
    void historicalNoticeDoesNotInventCurrentPriceOrQuantity() throws Exception {
        mockHistoricalHouses();
        importAndApprove(historicalPart(historicalFile("100.00", false)))
                .contextWrite(allowed())
                .block(WAIT);
        var row = bills.createQuery().where("projectId", project).fetchOne().block(WAIT);
        var notice =
                batches.notice(
                                project,
                                new ReceivableBatchService.Input(
                                        List.of(
                                                new ReceivableBatchService.Item(
                                                        row.getId(), row.getVersion())),
                                        null))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("100.00", notice.total());
        assertEquals("—", notice.rows().get(0).price());
        assertEquals("—", notice.rows().get(0).quantity());
    }

    @Test
    void batchDeleteValidatesEveryVersionAndPolicyBeforeAnyWriteAndAudit() {
        var first =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var second =
                createApproved(project, input("job", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var a =
                bills.createQuery()
                        .where("settingId", first.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        var b =
                bills.createQuery()
                        .where("settingId", second.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        var items =
                List.of(
                        new ReceivableBatchService.Item(a.getId(), a.getVersion()),
                        new ReceivableBatchService.Item(b.getId(), b.getVersion()));
        bills.createUpdate().set("frozen", true).where("id", b.getId()).execute().block(WAIT);
        conflict(batches.delete(project, new ReceivableBatchService.Input(items, "误出账")), 409);
        assertFalse(bills.findById(a.getId()).block(WAIT).getDeleted());
        assertFalse(bills.findById(b.getId()).block(WAIT).getDeleted());
        assertEquals(
                0,
                receivableLogs
                        .createQuery()
                        .where("projectId", project)
                        .and("operation", "DELETE")
                        .count()
                        .block(WAIT));
        bills.createUpdate().set("frozen", false).where("id", b.getId()).execute().block(WAIT);
        conflict(
                batches.delete(
                        project,
                        new ReceivableBatchService.Input(
                                List.of(
                                        items.get(0),
                                        new ReceivableBatchService.Item(b.getId(), 99L)),
                                "误出账")),
                409);
        conflict(
                batches.delete(
                        project,
                        new ReceivableBatchService.Input(
                                List.of(items.get(0), items.get(0)), "误出账")),
                400);
        batches.delete(project, new ReceivableBatchService.Input(items, "误出账"))
                .contextWrite(allowed())
                .block(WAIT);
        assertTrue(bills.findById(a.getId()).block(WAIT).getDeleted());
        assertTrue(bills.findById(b.getId()).block(WAIT).getDeleted());
        assertEquals(
                2,
                receivableLogs
                        .createQuery()
                        .where("projectId", project)
                        .and("operation", "DELETE")
                        .count()
                        .block(WAIT));
        generation
                .generate(project, first.setting().getId(), DATE)
                .contextWrite(allowed())
                .block(WAIT);
        assertEquals(2, bills.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void noticeUsesExactCurrentDebtAndVersionAndRejectsMixedSpaces() {
        var saved =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var row =
                bills.createQuery()
                        .where("settingId", saved.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        bills.createUpdate()
                .set("arrears", new BigDecimal("900719925474099.91"))
                .where("id", row.getId())
                .execute()
                .block(WAIT);
        var input =
                new ReceivableBatchService.Input(
                        List.of(new ReceivableBatchService.Item(row.getId(), row.getVersion())),
                        null);
        var notice = batches.notice(project, input).contextWrite(allowed()).block(WAIT);
        assertEquals("900719925474099.91", notice.total());
        assertTrue(notice.totalUppercase().endsWith("玖角壹分"));
        assertEquals("100.25", notice.rows().get(0).quantity());
        assertEquals("1.23", notice.rows().get(0).price());
        bills.createUpdate()
                .set("version", row.getVersion() + 1)
                .where("id", row.getId())
                .execute()
                .block(WAIT);
        conflict(batches.notice(project, input), 409);
        var other =
                createApproved(project, input("job", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var b =
                bills.createQuery()
                        .where("settingId", other.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        conflict(
                batches.notice(
                        project,
                        new ReceivableBatchService.Input(
                                List.of(
                                        new ReceivableBatchService.Item(
                                                row.getId(), row.getVersion() + 1),
                                        new ReceivableBatchService.Item(b.getId(), b.getVersion())),
                                null)),
                400);
    }

    @Test
    void batchDeleteAuditFailureRollsBackEarlierDeletedBill() throws Exception {
        var first =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var second =
                createApproved(project, input("job", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var sort = new org.hswebframework.web.api.crud.entity.QueryParamEntity();
        sort.toQuery().orderByAsc("id");
        var rows =
                bills.createQuery()
                        .setParam(sort)
                        .where("projectId", project)
                        .fetch()
                        .collectList()
                        .block(WAIT);
        var guarded = rows.get(1).getId();
        var constraint = "batch_audit_" + UUID.randomUUID().toString().replace("-", "");
        var setup =
                DB.execInContainer(
                        "psql",
                        "-U",
                        "postgres",
                        "-d",
                        "setting_test",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-c",
                        "ALTER TABLE pm_fin_receivable_log ADD CONSTRAINT "
                                + constraint
                                + " CHECK (operation <> 'DELETE' OR receivable_id <> '"
                                + guarded
                                + "') NOT VALID");
        assertEquals(0, setup.getExitCode(), setup.getStderr());
        try {
            var input =
                    new ReceivableBatchService.Input(
                            rows.stream()
                                    .map(
                                            row ->
                                                    new ReceivableBatchService.Item(
                                                            row.getId(), row.getVersion()))
                                    .toList(),
                            "误出账");
            StepVerifier.create(batches.delete(project, input).contextWrite(allowed()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(
                    0,
                    bills.createQuery()
                            .where("projectId", project)
                            .and("deleted", true)
                            .count()
                            .block(WAIT));
            assertEquals(
                    0,
                    receivableLogs
                            .createQuery()
                            .where("projectId", project)
                            .and("operation", "DELETE")
                            .count()
                            .block(WAIT));
        } finally {
            DB.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "setting_test",
                    "-c",
                    "ALTER TABLE pm_fin_receivable_log DROP CONSTRAINT " + constraint);
        }
    }

    @Test
    void closedPeriodRejectsAdjustmentWithoutChangingAmountsOrAudit() {
        var bill = periodBill("room");
        var input = periodAdjustment(bill);
        var preview =
                adjustments
                        .preview(project, bill.getId(), input, DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        financialPeriods.close(project, DATE).contextWrite(allowed()).block(WAIT);
        FinancialPeriodTestSupport.rejectsClosed(
                adjustments
                        .submit(
                                project,
                                bill.getId(),
                                new ReceivableAdjustmentService.SubmitInput(input, preview.token()),
                                DATE)
                        .contextWrite(allowed()));
        var retained = bills.findById(bill.getId()).block(WAIT);
        assertEquals(bill.getVersion(), retained.getVersion());
        assertEquals(bill.getActualReceivableAmount(), retained.getActualReceivableAmount());
        assertEquals(bill.getSnapshotJson(), retained.getSnapshotJson());
        assertEquals(
                0,
                receivableLogs
                        .createQuery()
                        .where("receivableId", bill.getId())
                        .count()
                        .block(WAIT));
    }

    @Test
    void closedPeriodRejectsSplitWithoutArchivingOrCreatingChildren() {
        var bill = periodBill("room");
        var input = periodSplit(bill);
        var preview =
                splits.preview(project, bill.getId(), input, DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        financialPeriods.close(project, DATE).contextWrite(allowed()).block(WAIT);
        FinancialPeriodTestSupport.rejectsClosed(
                splits.submit(
                                project,
                                bill.getId(),
                                new ReceivableSplitService.Submit(input, preview.token()),
                                DATE)
                        .contextWrite(allowed()));
        assertFalse(bills.findById(bill.getId()).block(WAIT).getSplit());
        assertEquals(bill.getVersion(), bills.findById(bill.getId()).block(WAIT).getVersion());
        assertEquals(
                1, bills.createQuery().where("settingId", bill.getSettingId()).count().block(WAIT));
        assertEquals(
                0,
                receivableLogs
                        .createQuery()
                        .where("receivableId", bill.getId())
                        .count()
                        .block(WAIT));
    }

    @Test
    void closedPeriodRejectsFreezeUnfreezeAndDeleteWithoutChangingVersionOrAudit() {
        var operations = List.of("FREEZE", "UNFREEZE", "DELETE");
        var sources =
                operations.stream().map(operation -> periodBill("room-" + operation)).toList();
        bills.createUpdate()
                .set("frozen", true)
                .where("id", sources.get(1).getId())
                .execute()
                .block(WAIT);
        financialPeriods.close(project, LocalDate.now()).contextWrite(allowed()).block(WAIT);
        for (int index = 0; index < operations.size(); index++) {
            var operation = operations.get(index);
            var bill = sources.get(index);
            FinancialPeriodTestSupport.rejectsClosed(
                    receivables
                            .change(
                                    project,
                                    bill.getId(),
                                    new ReceivableService.ChangeInput(bill.getVersion(), "期间保护"),
                                    operation)
                            .contextWrite(allowed()));
            var retained = bills.findById(bill.getId()).block(WAIT);
            assertEquals(bill.getVersion(), retained.getVersion());
            assertFalse(retained.getDeleted());
            assertEquals("UNFREEZE".equals(operation), retained.getFrozen());
            assertEquals(
                    0,
                    receivableLogs
                            .createQuery()
                            .where("receivableId", bill.getId())
                            .count()
                            .block(WAIT));
        }
    }

    @Test
    void closedPeriodRejectsWholeBatchBeforeAnyDeleteOrAudit() {
        var first = periodBill("room");
        var second = periodBill("job");
        var input =
                new ReceivableBatchService.Input(
                        List.of(
                                new ReceivableBatchService.Item(first.getId(), first.getVersion()),
                                new ReceivableBatchService.Item(
                                        second.getId(), second.getVersion())),
                        "误出账");
        financialPeriods.close(project, LocalDate.now()).contextWrite(allowed()).block(WAIT);
        FinancialPeriodTestSupport.rejectsClosed(
                batches.delete(project, input).contextWrite(allowed()));
        for (var bill : List.of(first, second)) {
            var retained = bills.findById(bill.getId()).block(WAIT);
            assertFalse(retained.getDeleted());
            assertEquals(bill.getVersion(), retained.getVersion());
            assertEquals(
                    0,
                    receivableLogs
                            .createQuery()
                            .where("receivableId", bill.getId())
                            .count()
                            .block(WAIT));
        }
    }

    @Test
    void nextOpenDayAllowsUnclosedBillAdjustmentAndSplitButProtectsFinancialClosedSource() {
        var bill = periodBill("room");
        var other = periodBill("job");
        financialPeriods.close(project, DATE).contextWrite(allowed()).block(WAIT);
        var next = DATE.plusDays(1);
        var input = periodAdjustment(bill);
        var preview =
                adjustments
                        .preview(project, bill.getId(), input, next)
                        .contextWrite(allowed())
                        .block(WAIT);
        adjustments
                .submit(
                        project,
                        bill.getId(),
                        new ReceivableAdjustmentService.SubmitInput(input, preview.token()),
                        next)
                .contextWrite(allowed())
                .block(WAIT);
        assertEquals(
                new BigDecimal("10.00"),
                bills.findById(bill.getId()).block(WAIT).getAdjustmentAmount());
        var split = periodSplit(other);
        var splitPreview =
                splits.preview(project, other.getId(), split, next)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(
                2,
                splits.submit(
                                project,
                                other.getId(),
                                new ReceivableSplitService.Submit(split, splitPreview.token()),
                                next)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .size());

        var closed = bills.findById(bill.getId()).block(WAIT);
        var closedInput = periodAdjustment(closed);
        var closedPreview =
                adjustments
                        .preview(project, closed.getId(), closedInput, next)
                        .contextWrite(allowed())
                        .block(WAIT);
        var closedSplit = periodSplit(closed);
        var closedSplitPreview =
                splits.preview(project, closed.getId(), closedSplit, next)
                        .contextWrite(allowed())
                        .block(WAIT);
        bills.createUpdate()
                .set("financialClosed", true)
                .where("id", closed.getId())
                .execute()
                .block(WAIT);
        conflict(
                adjustments.submit(
                        project,
                        closed.getId(),
                        new ReceivableAdjustmentService.SubmitInput(
                                closedInput, closedPreview.token()),
                        next),
                409);
        conflict(
                splits.submit(
                        project,
                        closed.getId(),
                        new ReceivableSplitService.Submit(closedSplit, closedSplitPreview.token()),
                        next),
                409);
        conflict(
                receivables.change(
                        project,
                        closed.getId(),
                        new ReceivableService.ChangeInput(closed.getVersion(), "已结原账"),
                        "FREEZE"),
                409);
        assertEquals(closed.getVersion(), bills.findById(closed.getId()).block(WAIT).getVersion());
    }

    private ReceivableEntity periodBill(String space) {
        var saved =
                createApproved(project, input(space, "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        return bills.createQuery()
                .where("settingId", saved.setting().getId())
                .fetchOne()
                .block(WAIT);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"CREATE", "UPDATE", "NEXT", "BATCH", "STOP", "DELETE"})
    void closedPeriodRejectsEverySettingWriteBeforeConfigurationOrBillChanges(String operation) {
        var saved =
                createApproved(project, input("existing", "2024-03-01", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        financialPeriods
                .close(project, "DELETE".equals(operation) ? LocalDate.now() : DATE)
                .contextWrite(allowed())
                .block(WAIT);
        var input = input("existing", "2024-03-01", null);
        if ("NEXT".equals(operation)) input.setEffectiveStartDate("2024-04-01");
        input.setVersion(saved.getVersion());
        Mono<?> action =
                switch (operation) {
                    case "CREATE" ->
                            createApproved(project, input("new", "2024-03-01", null), DATE);
                    case "UPDATE" -> updateApproved(project, saved.getId(), input, DATE);
                    case "NEXT" -> nextVersionApproved(project, saved.getId(), input, DATE);
                    case "BATCH" ->
                            createBatchApproved(
                                    project, List.of(input("new", "2024-03-01", null)), DATE);
                    case "STOP" ->
                            settings.stop(
                                    project,
                                    saved.getId(),
                                    new HouseSettingService.StopInput(
                                            saved.getVersion(), "2024-03-31"),
                                    DATE);
                    case "DELETE" -> settings.delete(project, saved.getId(), saved.getVersion());
                    default -> throw new AssertionError(operation);
                };
        FinancialPeriodTestSupport.rejectsClosed(action.contextWrite(allowed()));
        var retained = rows.findById(saved.getId()).block(WAIT);
        assertEquals(saved.getVersion(), retained.getVersion());
        assertFalse(retained.getDeleted());
        assertEquals("ACTIVE", retained.getStatus());
        assertEquals(1, rows.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void closedPeriodRejectsManualAndAnonymousScheduledGeneration() {
        var bill = periodBill("room");
        financialPeriods.close(project, DATE).contextWrite(allowed()).block(WAIT);
        FinancialPeriodTestSupport.rejectsClosed(
                generation.generate(project, bill.getSettingId(), DATE).contextWrite(allowed()));
        // 无登录的后台路径仍返回期间关闭，不能借接入门闩而变成要求操作员登录。
        FinancialPeriodTestSupport.rejectsClosed(
                generation.scheduled(project, bill.getSettingId(), DATE, BillingTaskKind.ALL));
        assertEquals(bill.getVersion(), bills.findById(bill.getId()).block(WAIT).getVersion());
        assertEquals(
                1, bills.createQuery().where("settingId", bill.getSettingId()).count().block(WAIT));
    }

    @Test
    void nextOpenDayCannotBackfillMissingClosedBillsOrBackdateNewSetting() {
        var saved =
                createApproved(
                                project,
                                input("future-job", "2024-01-01", null),
                                LocalDate.of(2023, 12, 31))
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        financialPeriods
                .close(project, LocalDate.of(2024, 2, 29))
                .contextWrite(allowed())
                .block(WAIT);
        var next = LocalDate.of(2024, 3, 1);
        FinancialPeriodTestSupport.rejectsClosed(
                generation.generate(project, saved.getId(), next).contextWrite(allowed()));
        FinancialPeriodTestSupport.rejectsClosed(
                generation.scheduled(project, saved.getId(), next, BillingTaskKind.GENERATE));
        FinancialPeriodTestSupport.rejectsClosed(
                createApproved(project, input("new-room", "2024-02-10", null), next)
                        .contextWrite(allowed()));
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(1, rows.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void scheduledGenerationKeepsClosedBillAndCreatesNextOpenPeriodWithoutLogin() {
        var saved =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        var bill = bills.createQuery().where("settingId", saved.getId()).fetchOne().block(WAIT);
        bills.createUpdate()
                .set("financialClosed", true)
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        financialPeriods
                .close(project, LocalDate.of(2024, 2, 29))
                .contextWrite(allowed())
                .block(WAIT);
        var result =
                generation
                        .scheduled(
                                project,
                                saved.getId(),
                                LocalDate.of(2024, 3, 1),
                                BillingTaskKind.ALL)
                        .block(WAIT);
        assertEquals(1, result.generated());
        assertEquals(1, result.retained());
        var retained = bills.findById(bill.getId()).block(WAIT);
        assertEquals(bill.getVersion(), retained.getVersion());
        assertEquals(bill.getActualReceivableAmount(), retained.getActualReceivableAmount());
        assertEquals(bill.getCalculatedAsOfDate(), retained.getCalculatedAsOfDate());
        assertEquals(2, bills.createQuery().where("settingId", saved.getId()).count().block(WAIT));
    }

    @Test
    void historicalImportRejectsClosedRowWithLineNumberAndRollsBackEarlierOpenRow()
            throws Exception {
        mockHistoricalHouses();
        byte[] bytes;
        try (var book =
                        new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                                new java.io.ByteArrayInputStream(historicalFile("100.00", true)));
                var out = new java.io.ByteArrayOutputStream()) {
            var first = book.getSheetAt(0).getRow(1);
            first.getCell(5).setCellValue("2023-03-01");
            first.getCell(6).setCellValue("2023-03-28");
            first.getCell(7).setCellValue("2023-03");
            book.write(out);
            bytes = out.toByteArray();
        }
        financialPeriods
                .close(project, LocalDate.of(2023, 2, 28))
                .contextWrite(allowed())
                .block(WAIT);
        var record =
                historical
                        .importFile(project, historicalPart(bytes))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("FAILED", record.getStatus());
        assertEquals(0, record.getSuccessCount());
        assertTrue(record.getErrorMessage().contains("3"), record.getErrorMessage());
        assertTrue(
                record.getErrorMessage().contains("period_closed")
                        || record.getErrorMessage().contains("已关闭")
                        || record.getErrorMessage().contains("已月结"),
                record.getErrorMessage());
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                0, receivableLogs.createQuery().where("projectId", project).count().block(WAIT));
    }

    private org.jetlinks.pro.property.finance.receivable.AdjustmentInput periodAdjustment(
            ReceivableEntity bill) {
        var input = new org.jetlinks.pro.property.finance.receivable.AdjustmentInput();
        input.setVersion(bill.getVersion());
        input.setAdjustmentMode("DIRECT");
        input.setDirectDirection("INCREASE");
        input.setDirectAmount("10.00");
        input.setReason("补充费用");
        return input;
    }

    private org.jetlinks.pro.property.finance.receivable.SplitInput periodSplit(
            ReceivableEntity bill) {
        return new org.jetlinks.pro.property.finance.receivable.SplitInput(
                bill.getVersion(), LocalDate.of(2024, 2, 12), 0, "link", "link", "按搬迁日期拆分");
    }

    @Test
    void closingFirstSerializesAnonymousGenerationBeforeAnyBillLockOrWrite() {
        var saved =
                createApproved(project, input("job", "2024-02-10", null), LocalDate.of(2024, 2, 9))
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        var held = Sinks.<Void>one();
        var started = Sinks.<Void>one();
        var closing =
                org.springframework.transaction.reactive.TransactionalOperator.create(
                                transactionManager)
                        .transactional(
                                periodGate
                                        .lock(project)
                                        .then(Mono.fromRunnable(held::tryEmitEmpty))
                                        .then(started.asMono())
                                        .then(financialPeriods.close(project, DATE)))
                        .thenReturn("CLOSED")
                        .contextWrite(allowed());
        var job =
                held.asMono()
                        .then(
                                generation
                                        .scheduled(
                                                project, saved.getId(), DATE, BillingTaskKind.ALL)
                                        .doOnSubscribe(ignored -> started.tryEmitEmpty()))
                        .materialize();
        var results = Mono.zip(closing, job).block(WAIT);
        assertEquals("CLOSED", results.getT1());
        assertTrue(results.getT2().isOnError());
        var failure = results.getT2().getThrowable();
        assertTrue(TaxItemPersistenceTest.status(failure, 409));
        assertTrue(
                ((org.hswebframework.web.exception.BusinessException) failure)
                        .getI18nCode()
                        .contains("period_closed"));
        assertEquals(0, bills.createQuery().where("settingId", saved.getId()).count().block(WAIT));
    }

    @Test
    void wrongTenantCannotUseAnonymousTaskToInitializeAnotherProjectsPeriod() {
        var saved =
                createApproved(project, input("job", "2024-02-10", null), LocalDate.of(2024, 2, 9))
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        var before =
                financialControls.createQuery().where("projectId", project).count().block(WAIT);
        StepVerifier.create(
                        generation
                                .scheduled(project, saved.getId(), DATE, BillingTaskKind.ALL)
                                .contextWrite(
                                        org.jetlinks.pro.tenant.holder.TenantHolder.makeCurrent(
                                                "other")))
                .expectErrorMatches(
                        error ->
                                TaxItemPersistenceTest.status(error, 409)
                                        && error.getMessage().contains("task_tenant_invalid"))
                .verify(WAIT);
        assertEquals(
                before,
                financialControls.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, bills.createQuery().where("settingId", saved.getId()).count().block(WAIT));
    }

    private void conflict(Mono<?> action, int status) {
        StepVerifier.create(action.contextWrite(allowed()))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, status))
                .verify(WAIT);
    }

    // 既有算费回归显式经过设置审批；新增审批测试直接调用保存接口验证待审批行为。
    private Mono<HouseSettingService.SaveResult> approveSetting(
            String project, HouseSettingService.SaveResult saved, LocalDate date) {
        return settingReview
                .decide(
                        project,
                        new HouseSettingReviewService.Input(
                                List.of(
                                        new HouseSettingReviewService.Item(
                                                saved.setting().getId(),
                                                saved.setting().getVersion())),
                                "核对收费规则"),
                        true,
                        date)
                .map(results -> results.get(0));
    }

    Mono<HouseSettingService.SaveResult> createApproved(
            String project, SettingInput input, LocalDate date) {
        return settings.create(project, input, date)
                .flatMap(saved -> approveSetting(project, saved, date));
    }

    private Mono<HouseSettingService.SaveResult> updateApproved(
            String project, String id, SettingInput input, LocalDate date) {
        return settings.update(project, id, input, date)
                .flatMap(saved -> approveSetting(project, saved, date));
    }

    private Mono<HouseSettingService.SaveResult> nextVersionApproved(
            String project, String id, SettingInput input, LocalDate date) {
        return settings.nextVersion(project, id, input, date)
                .flatMap(saved -> approveSetting(project, saved, date));
    }

    private Mono<List<HouseSettingService.SaveResult>> createBatchApproved(
            String project, List<SettingInput> inputs, LocalDate date) {
        return settings.createBatch(project, inputs, date)
                .flatMapMany(Flux::fromIterable)
                .concatMap(saved -> approveSetting(project, saved, date))
                .collectList();
    }

    @Test
    void settingApprovalIsIndependentAndCannotBeBypassedByGeneration() {
        var pending =
                settings.create(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("PENDING", pending.setting().getReviewState());
        assertEquals(0, pending.generated());
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
        conflict(generation.generate(project, pending.setting().getId(), DATE), 409);
        StepVerifier.create(
                        generation.scheduled(
                                project, pending.setting().getId(), DATE, BillingTaskKind.ALL))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 409))
                .verify(WAIT);
        var approved = approveSetting(project, pending, DATE).contextWrite(allowed()).block(WAIT);
        assertEquals("APPROVED", approved.setting().getReviewState());
        assertNotNull(approved.setting().getReviewedBy());
        assertEquals("核对收费规则", approved.setting().getReviewReason());
        assertEquals(1, approved.generated());
        var bill =
                bills.createQuery()
                        .where("settingId", approved.setting().getId())
                        .fetchOne()
                        .block(WAIT);
        assertEquals("APPROVED", bill.getReviewState(), "账单继承设置审批结果，无需二次审核");
        assertEquals(approved.setting().getReviewedBy(), bill.getReviewedBy());
        conflict(approveSetting(project, pending, DATE), 409);
        assertEquals(1, bills.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void rejectedSettingCanBeEditedAndSubmittedAgainWithoutChangingBills() {
        var pending =
                settings.create(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var rejected =
                settingReview
                        .decide(
                                project,
                                new HouseSettingReviewService.Input(
                                        List.of(
                                                new HouseSettingReviewService.Item(
                                                        pending.setting().getId(),
                                                        pending.setting().getVersion())),
                                        "请核对面积"),
                                false,
                                DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .get(0)
                        .setting();
        assertEquals("REJECTED", rejected.getReviewState());
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
        var request = input("room", "2024-02-10", null);
        request.setVersion(rejected.getVersion());
        var revised =
                settings.update(project, rejected.getId(), request, DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("PENDING", revised.setting().getReviewState());
        assertNull(revised.setting().getReviewedAt());
        assertNull(revised.setting().getReviewReason());
        assertEquals(
                1,
                approveSetting(project, revised, DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .generated());
    }

    @Test
    void settingApprovalBatchStaleItemAndWrongProjectHaveNoPartialWrites() {
        var pending =
                settings.createBatch(
                                project,
                                List.of(
                                        input("room", "2024-02-10", null),
                                        input("job", "2024-02-10", null)),
                                DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var one = pending.get(0).setting();
        var two = pending.get(1).setting();
        conflict(
                settingReview.decide(
                        project,
                        new HouseSettingReviewService.Input(
                                List.of(
                                        new HouseSettingReviewService.Item(
                                                one.getId(), one.getVersion()),
                                        new HouseSettingReviewService.Item(
                                                two.getId(), two.getVersion() + 1)),
                                "确认"),
                        true,
                        DATE),
                409);
        assertEquals("PENDING", rows.findById(one.getId()).block(WAIT).getReviewState());
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
        conflict(approveSetting("wrong-project", pending.get(0), DATE), 404);
        conflict(
                settingReview.decide(
                        project,
                        new HouseSettingReviewService.Input(
                                List.of(
                                        new HouseSettingReviewService.Item(
                                                one.getId(), one.getVersion())),
                                " "),
                        true,
                        DATE),
                400);
        StepVerifier.create(approveSetting(project, pending.get(0), DATE))
                .expectError()
                .verify(WAIT);
    }

    @Test
    void approvalCalculationFailureOnLastSettingRollsBackFirstApprovalAndBill() {
        standards
                .createUpdate()
                .set("formulaExpression", "1/(${BILLING_AREA}-1)")
                .where("id", standardId)
                .execute()
                .block(WAIT);
        var original = context("job");
        var house = original.house();
        when(archive.context(project, "job", true))
                .thenReturn(
                        Mono.just(
                                new PropertyHouseContext(
                                        new PropertyHouseInfo(
                                                house.spaceId(),
                                                house.projectId(),
                                                house.name(),
                                                house.fullName(),
                                                house.spaceTypeId(),
                                                house.spaceTypeName(),
                                                house.spaceLocation(),
                                                BigDecimal.ONE,
                                                null,
                                                null,
                                                null,
                                                house.version()),
                                        original.customers())));
        var pending =
                settings.createBatch(
                                project,
                                List.of(
                                        input("room", "2024-02-10", null),
                                        input("job", "2024-02-10", null)),
                                DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var request =
                new HouseSettingReviewService.Input(
                        pending.stream()
                                .map(
                                        saved ->
                                                new HouseSettingReviewService.Item(
                                                        saved.setting().getId(),
                                                        saved.setting().getVersion()))
                                .toList(),
                        "整批核对");
        StepVerifier.create(
                        settingReview.decide(project, request, true, DATE).contextWrite(allowed()))
                .expectError()
                .verify(WAIT);
        for (var saved : pending) {
            var retained = rows.findById(saved.setting().getId()).block(WAIT);
            assertEquals("PENDING", retained.getReviewState());
            assertEquals(saved.setting().getVersion(), retained.getVersion());
            assertNull(retained.getReviewedAt());
        }
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void pendingReplacementNeverStopsOriginalAndRejectLeavesOriginalUntouched() {
        var saved =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var nextInput = input("room", "2024-03-01", null);
        nextInput.setVersion(saved.setting().getVersion());
        var next =
                settings.nextVersion(project, saved.setting().getId(), nextInput, DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("PENDING", next.setting().getReviewState());
        assertEquals("ACTIVE", rows.findById(saved.setting().getId()).block(WAIT).getStatus());
        assertNull(rows.findById(saved.setting().getId()).block(WAIT).getEffectiveEndDate());
        settingReview
                .decide(
                        project,
                        new HouseSettingReviewService.Input(
                                List.of(
                                        new HouseSettingReviewService.Item(
                                                next.setting().getId(),
                                                next.setting().getVersion())),
                                "规则尚未确定"),
                        false,
                        DATE)
                .contextWrite(allowed())
                .block(WAIT);
        assertEquals("ACTIVE", rows.findById(saved.setting().getId()).block(WAIT).getStatus());
        assertEquals(
                0,
                bills.createQuery().where("settingId", next.setting().getId()).count().block(WAIT));
    }

    @Test
    void approvedReplacementSwitchesOriginalAndKeepsBillApproved() {
        var old =
                createApproved(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        var input = input("room", "2024-03-01", null);
        input.setVersion(old.getVersion());
        var next =
                settings.nextVersion(project, old.getId(), input, DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var result =
                approveSetting(project, next, LocalDate.of(2024, 3, 5))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("APPROVED", result.setting().getReviewState());
        assertEquals(1, result.generated());
        var previous = rows.findById(old.getId()).block(WAIT);
        assertEquals("SUPERSEDED", previous.getStatus());
        assertEquals("2024-02-29", previous.getEffectiveEndDate());
        assertEquals(
                "APPROVED",
                bills.createQuery()
                        .where("settingId", result.setting().getId())
                        .fetchOne()
                        .block(WAIT)
                        .getReviewState());
    }

    @Test
    void settingMigrationIsIdempotentAndOnlyPublishesLegacySettingBills() throws Exception {
        var pending =
                settings.create(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        var existing =
                createApproved(project, input("job", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        var bill = bills.createQuery().where("settingId", existing.getId()).fetchOne().block(WAIT);
        bills.createUpdate()
                .set("reviewState", "PENDING")
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        var script =
                java.nio.file.Files.readString(
                        java.nio.file.Path.of("../sql/migrate-setting-review.sql"));
        for (int run = 0; run < 2; run++) {
            var result =
                    DB.execInContainer(
                            "psql",
                            "-X",
                            "-U",
                            "postgres",
                            "-d",
                            "setting_test",
                            "-v",
                            "ON_ERROR_STOP=1",
                            "-c",
                            script);
            assertEquals(0, result.getExitCode(), result.getStderr());
        }
        assertEquals(
                "PENDING",
                rows.findById(pending.getId()).block(WAIT).getReviewState(),
                "新提交待审设置不能被重复升级发布");
        var migrated = bills.findById(bill.getId()).block(WAIT);
        assertEquals("APPROVED", migrated.getReviewState());
        assertEquals(bill.getActualReceivableAmount(), migrated.getActualReceivableAmount());
        assertEquals(bill.getVersion() + 1, migrated.getVersion(), "重复脚本不重复更新账单版本");
    }

    @Configuration
    @Import({
        StandardPersistenceTest.Config.class,
        HouseSettingService.class,
        HouseSettingReviewService.class,
        ReceivableGenerationService.class,
        ReceivableCalculationService.class,
        ReceivableAmountCalculator.class,
        ChargeFormulaEvaluator.class,
        BillingProrationService.class,
        NaturalBillingPeriodService.class,
        ReceivableLateFeeCalculator.class,
        BillingTaskService.class,
        ReceivableService.class,
        ReceivablePresentationService.class,
        ReceivableAdjustmentService.class,
        ReceivableSplitService.class,
        ReceivableChangeAuditService.class,
        HistoricalReceivableService.class,
        HistoricalImportAuditService.class,
        HistoricalImportEditService.class,
        ReceivableBatchService.class,
        org.jetlinks.pro.property.finance.receivable.SplitRules.class,
        org.jetlinks.pro.property.finance.receivable.AdjustmentRules.class
    })
    static class Config {
        @Bean
        HouseArchiveSource archive() {
            return mock(HouseArchiveSource.class);
        }
    }
}
