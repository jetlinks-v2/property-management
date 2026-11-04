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
    @Autowired ReceivableGenerationService generation;
    @Autowired HouseArchiveSource archive;
    @Autowired ReactiveRepository<StandardEntity, String> standards;
    @Autowired ReactiveRepository<ProjectSubjectEntity, String> subjects;
    @Autowired ReactiveRepository<SubjectEntity, String> subjectRows;
    @Autowired ReactiveRepository<HouseSettingEntity, String> rows;
    @Autowired ReactiveRepository<ReceivableEntity, String> bills;
    @Autowired BillingTaskService tasks;
    @Autowired ReceivableService receivables;
    @Autowired ReceivableBatchService batches;
    @Autowired ReceivableAdjustmentService adjustments;
    @Autowired ReactiveRepository<ReceivableImportEntity, String> importRecords;
    @Autowired HistoricalReceivableService historical;
    @Autowired ReceivableSplitService splits;
    @Autowired org.jetlinks.pro.property.finance.receivable.SplitRules splitRules;
    @Autowired org.jetlinks.pro.property.finance.receivable.AdjustmentRules adjustmentRules;
    @Autowired ReactiveRepository<ReceivableLogEntity, String> receivableLogs;
    @Autowired FinanceScope financeScope;
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
        var result = settings.create(project, input, DATE).contextWrite(allowed()).block(WAIT);
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
        StepVerifier.create(settings.create(project, other, DATE).contextWrite(allowed()))
                .expectError()
                .verify(WAIT);
        assertEquals(1, bills.createQuery().where("projectId", project).count().block(WAIT));
    }

    Context allowed() {
        var holder = mock(AssetsHolder.class);
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
        return auth().put(AssetsHolder.class, holder);
    }

    @Test
    void saveImmediatelyGeneratesProratedLeapMonthAndFreezesTrustedSources() {
        var saved =
                settings.create(project, input("room", "2024-02-10", "2024-02-29"), DATE)
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
                settings.create(project, input("future", "2024-03-01", null), DATE)
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
                settings.create(project, input("past", "2024-01-01", "2024-01-31"), DATE)
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
        conflict(settings.create(project, request, DATE), 400);
        request.setVariableValues(Map.of());
        request.setCustomerRelationshipIds(List.of("other-house-link"));
        conflict(settings.create(project, request, DATE), 409);
        request.setCustomerRelationshipIds(List.of("link"));
        standards
                .createUpdate()
                .set("cancelDate", "2024-02-29")
                .where("id", standardId)
                .execute()
                .block(WAIT);
        conflict(settings.create(project, request, DATE), 409);
        assertEquals(0, rows.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void sameHouseSubjectOverlapIsSerializedAndRepeatedGenerationIsIdempotent() {
        var actions =
                Flux.merge(
                                settings.create(project, input("room", "2024-01-01", null), DATE)
                                        .contextWrite(allowed())
                                        .materialize(),
                                settings.create(project, input("room", "2024-02-01", null), DATE)
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
    void calculationFailureRollsBackConfigurationAndEveryGeneratedBill() {
        standards
                .createUpdate()
                .set("formulaExpression", "${STANDARD_PRICE}/0")
                .where("id", standardId)
                .execute()
                .block(WAIT);
        StepVerifier.create(
                        settings.create(project, input("room", "2024-01-01", null), DATE)
                                .contextWrite(allowed()))
                .expectError()
                .verify(WAIT);
        assertEquals(0, rows.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void issuedSettingsAreImmutableWhileFutureUnbilledCanBeEditedWithVersion() {
        var active =
                settings.create(project, input("room", "2024-01-01", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        var change = input("room", "2024-01-01", null);
        change.setVersion(active.getVersion());
        conflict(settings.update(project, active.getId(), change, DATE), 409);
        conflict(settings.delete(project, active.getId(), active.getVersion()), 409);
        var future =
                settings.create(project, input("future", "2024-04-01", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        var replacement = input("future", "2024-05-01", null);
        replacement.setVersion(future.getVersion());
        var edited =
                settings.update(project, future.getId(), replacement, DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        assertEquals(1L, edited.getVersion());
        conflict(settings.update(project, future.getId(), replacement, DATE), 409);
        settings.delete(project, future.getId(), edited.getVersion())
                .contextWrite(allowed())
                .block(WAIT);
        assertTrue(rows.findById(future.getId()).block(WAIT).getDeleted());
    }

    @Test
    void missingSourceAnonymousAndForbiddenProjectNeverCreateSettings() {
        when(archive.context(eq(project), anyString(), anyBoolean()))
                .thenReturn(Mono.error(FinanceScope.error("house_source_unavailable", 503)));
        conflict(settings.create(project, input("room", "2024-01-01", null), DATE), 503);
        StepVerifier.create(settings.create(project, input("room", "2024-01-01", null), DATE))
                .expectError(
                        org.hswebframework.web.authorization.exception.UnAuthorizedException.class)
                .verify(WAIT);
        conflict(settings.create("forbidden", input("room", "2024-01-01", null), DATE), 403);
        assertEquals(0, rows.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void batchFailureLeavesNoConfigurationOrReceivableAndSuccessCreatesEveryHouse() {
        var bad = input("bad", "2024-02-01", null);
        bad.setCustomerRelationshipIds(List.of("missing"));
        conflict(
                settings.createBatch(
                        project, List.of(input("good", "2024-02-01", null), bad), DATE),
                409);
        assertEquals(0, rows.createQuery().where("projectId", project).count().block(WAIT));
        var saved =
                settings.createBatch(
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
        conflict(settings.createBatch(project, List.of(overlapA, overlapB), DATE), 409);
        assertEquals(2, rows.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void nextVersionPreservesOldBillsAndRejectsDateThatWouldRewriteAnExistingBill() {
        var saved =
                settings.create(project, input("room", "2024-01-01", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        var next = input("room", "2024-03-01", null);
        next.setVersion(saved.getVersion());
        var successor =
                settings.nextVersion(project, saved.getId(), next, DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        assertEquals(saved.getId(), successor.getPreviousVersionId());
        assertEquals(2, successor.getConfigVersion());
        assertEquals("2024-02-29", rows.findById(saved.getId()).block(WAIT).getEffectiveEndDate());
        assertEquals("SUPERSEDED", rows.findById(saved.getId()).block(WAIT).getStatus());
        assertEquals(2, bills.createQuery().where("settingId", saved.getId()).count().block(WAIT));
        var another =
                settings.create(project, input("other", "2024-01-01", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        var collision = input("other", "2024-02-15", null);
        collision.setVersion(another.getVersion());
        conflict(settings.nextVersion(project, another.getId(), collision, DATE), 409);
        assertNull(rows.findById(another.getId()).block(WAIT).getEffectiveEndDate());
    }

    @Test
    void stopDoesNotTouchExistingBillsAndPreventsLaterGeneration() {
        var setting =
                settings.create(project, input("room", "2024-01-01", null), DATE)
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
                settings.create(
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
                settings.create(
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
                settings.create(
                                project,
                                input("job", "2024-01-01", null),
                                LocalDate.of(2023, 12, 31))
                        .contextWrite(allowed())
                        .block(WAIT)
                        .setting();
        var hidden =
                settings.create(
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
                settings.create(project, input("room", "2024-02-10", null), DATE)
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
                settings.create(project, input("room", "2024-02-10", null), DATE)
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
                settings.create(project, input("room", "2024-02-10", null), DATE)
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
                settings.create(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        settings.create(project, input("hidden", "2024-02-10", null), DATE)
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
            assertEquals("实际应收", sheet.getRow(0).getCell(21).getStringCellValue());
            assertEquals("999999999999999999.99", sheet.getRow(1).getCell(21).getStringCellValue());
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
                settings.create(project, input("room", "2024-02-10", null), DATE)
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
        var failedService = new ReceivableService(financeScope, bills, failedLogs);
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
                settings.create(project, input("room", "2024-02-10", null), DATE)
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
                settings.create(project, input("room", "2024-02-10", null), DATE)
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
                settings.create(project, input("room", "2024-02-10", null), DATE)
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
                new ReceivableAdjustmentService(financeScope, adjustmentRules, bills, failedLogs);
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
                                        .ReceivableAdjustmentController(adjustments))
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
                settings.create(project, input("room", "2024-02-10", null), DATE)
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
                settings.create(project, input("room", "2024-02-10", null), DATE)
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
                new ReceivableSplitService(financeScope, archive, splitRules, bills, failedLogs);
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
                settings.create(project, input("room", "2024-02-10", null), DATE)
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
                settings.create(project, input("room", "2024-02-10", null), DATE)
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
                                        splits))
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
                .jsonPath("$[0].actualReceivableAmount")
                .isEqualTo("12.76")
                .jsonPath("$[0].sourceReceivableId")
                .isEqualTo(source.getId())
                .jsonPath("$[0].snapshotJson")
                .doesNotExist();
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
                historical
                        .importFile(project, historicalPart(historicalFile("100.00", true)))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("SUCCESS", success.getStatus());
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
                historical
                        .importFile(project, historicalPart(historicalFile("100.00", true)))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals("FAILED", duplicate.getStatus());
        assertEquals(2, bills.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                2, receivableLogs.createQuery().where("projectId", project).count().block(WAIT));
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
                        archive,
                        standards,
                        subjectRows,
                        subjects,
                        bills,
                        failedLogs,
                        importRecords,
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
        mockHistoricalHouses();
        var bytes = historicalFile("100.00", false);
        var result =
                Flux.merge(
                                historical
                                        .importFile(project, historicalPart(bytes))
                                        .contextWrite(allowed()),
                                historical
                                        .importFile(project, historicalPart(bytes))
                                        .contextWrite(allowed()))
                        .collectList()
                        .block(WAIT);
        assertEquals(1, result.stream().filter(r -> "SUCCESS".equals(r.getStatus())).count());
        assertEquals(1, result.stream().filter(r -> "FAILED".equals(r.getStatus())).count());
        assertEquals(1, bills.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                1, receivableLogs.createQuery().where("projectId", project).count().block(WAIT));
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
    void historicalHttpTemplateUploadAndHistoryUseProjectScope() throws Exception {
        mockHistoricalHouses();
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web
                                        .HistoricalReceivableController(historical))
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
                .isEqualTo("SUCCESS")
                .jsonPath("$.successCount")
                .isEqualTo(1);
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
        StepVerifier.create(
                        historical.importFile(
                                project, historicalPart(historicalFile("100.00", false))))
                .expectError(
                        org.hswebframework.web.authorization.exception.UnAuthorizedException.class)
                .verify(WAIT);
        assertEquals(
                1, importRecords.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void batchHttpUsesServerMoneyAndRejectsForeignProjectAndAnonymousRequests() {
        var saved =
                settings.create(project, input("room", "2024-02-10", null), DATE)
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
        historical
                .importFile(project, historicalPart(historicalFile("100.00", false)))
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
                settings.create(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var second =
                settings.create(project, input("job", "2024-02-10", null), DATE)
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
                settings.create(project, input("room", "2024-02-10", null), DATE)
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
                settings.create(project, input("job", "2024-02-10", null), DATE)
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
                settings.create(project, input("room", "2024-02-10", null), DATE)
                        .contextWrite(allowed())
                        .block(WAIT);
        var second =
                settings.create(project, input("job", "2024-02-10", null), DATE)
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

    private void conflict(Mono<?> action, int status) {
        StepVerifier.create(action.contextWrite(allowed()))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, status))
                .verify(WAIT);
    }

    @Configuration
    @Import({
        StandardPersistenceTest.Config.class,
        HouseSettingService.class,
        ReceivableGenerationService.class,
        ReceivableCalculationService.class,
        ReceivableAmountCalculator.class,
        ChargeFormulaEvaluator.class,
        BillingProrationService.class,
        NaturalBillingPeriodService.class,
        ReceivableLateFeeCalculator.class,
        BillingTaskService.class,
        ReceivableService.class,
        ReceivableAdjustmentService.class,
        ReceivableSplitService.class,
        HistoricalReceivableService.class,
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
