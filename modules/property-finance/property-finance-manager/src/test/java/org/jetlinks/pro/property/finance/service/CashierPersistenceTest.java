package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.core.publisher.*;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 金额及事务在真实PostgreSQL验证；仅隔离项目/配置/基础档案命令边界。 */
@SpringBootTest(
        classes = CashierPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CashierPersistenceTest {
    static final LocalDate DATE = LocalDate.of(2024, 2, 15);
    static final Duration WAIT = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        HouseSettingPersistenceTest.database(registry);
    }

    @Autowired CashierService cashier;
    @Autowired ReceivableService receivables;
    @Autowired CashierConfiguration configuration;
    @Autowired HouseArchiveSource archive;
    @Autowired ReactiveRepository<ReceivableEntity, String> bills;
    @Autowired ReactiveRepository<PaymentEntity, String> payments;
    @Autowired ReactiveRepository<PaymentAllocationEntity, String> lines;
    @Autowired ReactiveRepository<ReceivableLogEntity, String> logs;
    @Autowired PaymentRecordService records;
    @Autowired ReactiveRepository<FinanceProjectCompanyEntity, String> companyRelations;
    @Autowired ReactiveRepository<RecordedInfoEntity, String> recordedRows;
    @Autowired ReactiveRepository<FinanceBankEntity, String> bankRows;
    String project;

    @BeforeEach
    void setup() {
        project = UUID.randomUUID().toString();
        reset(configuration, archive);
        var source = new HouseSettingPersistenceTest();
        source.project = project;
        when(archive.context(anyString(), anyString(), anyBoolean()))
                .thenAnswer(call -> Mono.just(source.context(call.getArgument(1))));
        var company = new FinanceCompanyEntity();
        company.setId("company");
        company.setCompanyName("测试物业公司");
        company.setVersion(1L);
        when(configuration.resolve(anyString(), any(), anyBoolean()))
                .thenAnswer(
                        call -> {
                            var input = call.getArgument(1, CashierInput.class);
                            return Mono.just(
                                    new CashierConfiguration.Resolved(
                                            company,
                                            input.getTenders().stream()
                                                    .map(
                                                            tender ->
                                                                    new CashierConfiguration
                                                                            .Funding(
                                                                            tender.method(),
                                                                            "现金",
                                                                            tender.recordedId(),
                                                                            null,
                                                                            null,
                                                                            null,
                                                                            1L))
                                                    .toList()));
                        });
        when(configuration.resolveCollection(anyString(), any(), anyBoolean()))
                .thenAnswer(call -> configuration.resolve(
                        call.getArgument(0), call.getArgument(1), call.getArgument(2)));
    }

    ReceivableEntity bill(String suffix) {
        var row = new ReceivableEntity();
        var wrapper = new BeanWrapperImpl(row);
        for (var field : wrapper.getPropertyDescriptors())
            if (field.getPropertyType() == BigDecimal.class)
                wrapper.setPropertyValue(field.getName(), BigDecimal.ZERO.setScale(2));
        row.setId(UUID.randomUUID().toString());
        row.setBillKey(row.getId());
        StandardPersistenceTest.initialize(row);
        row.setProjectId(project);
        row.setSpaceId("room");
        row.setFullName("园区/1栋/101");
        row.setSubjectId("subject");
        row.setSubjectName("物业费");
        row.setStandardId("standard");
        row.setStandardName("历史物业费");
        row.setCustomerId("customer");
        row.setCustomerRelationshipId("link");
        row.setCustomerName("龚超");
        row.setSourceType("HISTORICAL_IMPORT");
        row.setConfigVersion(1);
        row.setPeriodStart("2024-02-01");
        row.setPeriodEnd("2024-02-29");
        row.setCalcStartDate("2024-02-01");
        row.setCalcEndDate("2024-02-29");
        row.setDueDate("2024-02-29");
        row.setAccruedAsOfDate(DATE.toString());
        row.setCalculatedAsOfDate(DATE.toString());
        row.setPeriodStatus("CLOSED");
        row.setMoneyScale(2);
        row.setReviewState("APPROVED");
        row.setFrozen(false);
        row.setPaymentLocked(false);
        row.setBankCollectionLocked(false);
        row.setLateFeeLocked(false);
        row.setFinancialClosed(false);
        row.setContractReceivableAmount(new BigDecimal("100.00"));
        row.setAccruedReceivableAmount(new BigDecimal("100.00"));
        row.setActualReceivableAmount(new BigDecimal("100.00"));
        row.setArrears(new BigDecimal("100.00"));
        row.setSnapshotJson(
                "{\"standard\":{\"formulaExpression\":\"100\",\"chargeRoundType\":2,\"mantissa\":0,\"isDelay\":0,\"variablesSnapshotJson\":\"[]\"},\"values\":{},\"customer\":{\"customerId\":\"customer\"}}");
        bills.insert(row).block(WAIT);
        return row;
    }

    CashierInput input(ReceivableEntity row, String amount) {
        var input = new CashierInput();
        input.setCompanyId("company");
        input.setPayerName("龚超");
        input.setPayerRelationshipId("link");
        input.setPaidAt("2024-02-15T10:00:00");
        input.setRemark("柜台收款");
        input.setItems(
                List.of(
                        new CashierInput.Item(
                                row.getId(), row.getVersion(), new BigDecimal(amount))));
        input.setTenders(List.of(new CashierInput.Tender("1", null, new BigDecimal(amount))));
        return input;
    }

    reactor.util.context.Context allowed() {
        return new HouseSettingPersistenceTest().allowed();
    }

    CashierService.Preview preview(CashierInput input) {
        return cashier.preview(project, input, DATE).contextWrite(allowed()).block(WAIT);
    }

    CashierService.Submission submission(CashierInput input) {
        return new CashierService.Submission(
                UUID.randomUUID().toString(), preview(input).token(), input);
    }

    void rejects(Mono<?> action, int status) {
        StepVerifier.create(action.contextWrite(allowed()))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, status))
                .verify(WAIT);
    }

    @Test
    void collectionAndReceivableLogPersistNameInsteadOfAccount() {
        var row = bill("named-operator");
        var input = input(row, "100.00");
        var context = allowed().put(
                org.hswebframework.web.authorization.Authentication.class,
                FinanceOperatorNameTest.actor("龚超"));
        var preview = cashier.preview(project, input, DATE).contextWrite(context).block(WAIT);
        var request = new CashierService.Submission(UUID.randomUUID().toString(), preview.token(), input);
        var detail = cashier.collect(project, request, DATE).contextWrite(context).block(WAIT);
        var saved = payments.findById(detail.payment().getId()).block(WAIT);
        assertEquals("龚超", saved.getModifierName());
        assertEquals("operator-id", saved.getModifierId());
        var history = logs.createQuery().where("receivableId", row.getId())
                .and("operation", "COLLECT").fetch().collectList().block(WAIT);
        assertEquals(1, history.size());
        assertEquals("龚超", history.get(0).getOperatorName());
        assertEquals("operator-id", history.get(0).getOperatorId());
    }

    @Test
    void previewDoesNotWriteAndFullCollectionIsAtomicAndRetryIsIdempotent() {
        var row = bill("one");
        var input = input(row, "100.00");
        var request = submission(input);
        assertEquals(
                "0.00", bills.findById(row.getId()).block(WAIT).getPaidAmount().toPlainString());
        assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
        var detail = cashier.collect(project, request, DATE).contextWrite(allowed()).block(WAIT);
        assertEquals("100.00", detail.payment().getAmount().toPlainString());
        assertEquals("龚超", detail.payment().getPayerName());
        assertEquals("龚超", detail.lines().get(0).getCustomerName());
        assertEquals("0.00", bills.findById(row.getId()).block(WAIT).getArrears().toPlainString());
        assertEquals(
                detail.payment().getId(),
                cashier.collect(project, request, DATE)
                        .contextWrite(allowed())
                        .block(WAIT)
                        .payment()
                        .getId());
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                1,
                logs.createQuery()
                        .where("projectId", project)
                        .and("operation", "COLLECT")
                        .count()
                        .block(WAIT));
        input.setRemark("同请求号更改输入");
        rejects(cashier.collect(project, request, DATE), 409);
        StepVerifier.create(cashier.collect(project, request, DATE))
                .expectError(
                        org.hswebframework.web.authorization.exception.UnAuthorizedException.class)
                .verify(WAIT);
    }

    @Test
    void directRequestsCannotPartiallyCollectOrCombinePaymentMethods() {
        var row = bill("invalid-full-collection");
        var partial = input(row, "40.00");
        rejects(cashier.preview(project, partial, DATE), 409);
        rejects(cashier.collect(project, new CashierService.Submission(
                UUID.randomUUID().toString(), "0".repeat(64), partial), DATE), 409);
        var mixed = input(row, "100.00");
        mixed.setTenders(List.of(
                new CashierInput.Tender("1", null, new BigDecimal("40.00")),
                new CashierInput.Tender("3", "bank-record", new BigDecimal("60.00"))));
        rejects(cashier.preview(project, mixed, DATE), 400);
        rejects(cashier.collect(project, new CashierService.Submission(
                UUID.randomUUID().toString(), "0".repeat(64), mixed), DATE), 400);
        assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, lines.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, logs.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(new BigDecimal("100.00"), bills.findById(row.getId()).block(WAIT).getArrears());
    }

    @Test
    void stalePreviewBlockedLastBillAndCrossProjectCannotLeavePartialPayments() {
        var a = bill("a");
        var b = bill("b");
        var input = input(a, "100.00");
        input.setItems(
                List.of(
                        new CashierInput.Item(a.getId(), 0L, new BigDecimal("100.00")),
                        new CashierInput.Item(b.getId(), 0L, new BigDecimal("100.00"))));
        input.setTenders(List.of(new CashierInput.Tender("1", null, new BigDecimal("200.00"))));
        var request = submission(input);
        bills.createUpdate().set("frozen", true).where("id", b.getId()).execute().block(WAIT);
        rejects(cashier.collect(project, request, DATE), 409);
        assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals("0.00", bills.findById(a.getId()).block(WAIT).getPaidAmount().toPlainString());
        bills.createUpdate().set("frozen", false).where("id", b.getId()).execute().block(WAIT);
        rejects(cashier.collect(project, request, DATE.plusDays(1)), 409);
        rejects(cashier.collect("foreign-project", request, DATE), 404);
        assertEquals(0, lines.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void concurrentSameRequestCollectsOnlyOnceAndDifferentRequestsCannotOvercollect() {
        var row = bill("one");
        var request = submission(input(row, "100.00"));
        var results =
                Flux.merge(
                                cashier.collect(project, request, DATE).contextWrite(allowed()),
                                cashier.collect(project, request, DATE).contextWrite(allowed()))
                        .collectList()
                        .block(WAIT);
        assertEquals(2, results.size());
        assertEquals(results.get(0).payment().getId(), results.get(1).payment().getId());
        assertEquals("0.00", bills.findById(row.getId()).block(WAIT).getArrears().toPlainString());
        var different =
                new CashierService.Submission(
                        UUID.randomUUID().toString(), request.token(), request.input());
        rejects(cashier.collect(project, different, DATE), 409);
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void secondAuditFailureRollsBackPaymentAllocationsAndEveryBill() throws Exception {
        var a = bill("a");
        var b = bill("b");
        var input = input(a, "100.00");
        input.setItems(
                List.of(
                        new CashierInput.Item(a.getId(), 0L, new BigDecimal("100.00")),
                        new CashierInput.Item(b.getId(), 0L, new BigDecimal("100.00"))));
        input.setTenders(List.of(new CashierInput.Tender("1", null, new BigDecimal("200.00"))));
        var request = submission(input);
        var constraint = "payment_audit_" + UUID.randomUUID().toString().replace("-", "");
        var result =
                HouseSettingPersistenceTest.DB.execInContainer(
                        "psql",
                        "-U",
                        "postgres",
                        "-d",
                        "setting_test",
                        "-c",
                        "ALTER TABLE pm_fin_receivable_log ADD CONSTRAINT "
                                + constraint
                                + " CHECK (operation <> 'COLLECT' OR receivable_id <> '"
                                + b.getId()
                                + "') NOT VALID");
        assertEquals(0, result.getExitCode(), result.getStderr());
        try {
            StepVerifier.create(cashier.collect(project, request, DATE).contextWrite(allowed()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(0, lines.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(
                    0,
                    logs.createQuery()
                            .where("projectId", project)
                            .and("operation", "COLLECT")
                            .count()
                            .block(WAIT));
            assertEquals(
                    "0.00", bills.findById(a.getId()).block(WAIT).getPaidAmount().toPlainString());
            assertEquals(
                    "0.00", bills.findById(b.getId()).block(WAIT).getPaidAmount().toPlainString());
        } finally {
            HouseSettingPersistenceTest.DB.execInContainer(
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
    void selectedPayerUsesCurrentRelationshipAndCannotChooseUnselectedCustomer() {
        var row = bill("family");
        var fixture = new HouseSettingPersistenceTest();
        fixture.project = project;
        var base = fixture.context("room");
        row.setSnapshotJson(
                row.getSnapshotJson()
                        .replace(
                                "\"customerId\":\"customer\"",
                                "\"customerId\":\"family-customer\""));
        bills.createUpdate()
                .set("snapshotJson", row.getSnapshotJson())
                .where("id", row.getId())
                .execute()
                .block(WAIT);
        var member =
                new org.jetlinks.pro.property.base.archive.PropertyHouseCustomer(
                        "family-link",
                        "family-customer",
                        "family-user",
                        "person",
                        "龚超家人",
                        "13800000000",
                        "family",
                        0L,
                        1L,
                        1L);
        var outsider =
                new org.jetlinks.pro.property.base.archive.PropertyHouseCustomer(
                        "outsider",
                        "other",
                        "other-user",
                        "person",
                        "其他客户",
                        "13800000001",
                        "tenant",
                        0L,
                        1L,
                        1L);
        when(archive.context(eq(project), eq("room"), anyBoolean()))
                .thenReturn(
                        Mono.just(
                                new org.jetlinks.pro.property.base.archive.PropertyHouseContext(
                                        base.house(), List.of(member, outsider))));
        var options =
                cashier.payerOptions(project, List.of(row.getId()))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(
                List.of("family-link"),
                options.stream().map(CashierService.PayerOption::relationshipId).toList());
        var input = input(row, "100.00");
        input.setPayerRelationshipId("outsider");
        rejects(cashier.preview(project, input, DATE), 409);
        input.setPayerRelationshipId("family-link");
        var request = submission(input);
        when(archive.context(eq(project), eq("room"), eq(true)))
                .thenReturn(
                        Mono.just(
                                new org.jetlinks.pro.property.base.archive.PropertyHouseContext(
                                        base.house(), List.of())));
        rejects(cashier.collect(project, request, DATE), 409);
        assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
        when(archive.context(eq(project), eq("room"), eq(true)))
                .thenReturn(
                        Mono.just(
                                new org.jetlinks.pro.property.base.archive.PropertyHouseContext(
                                        base.house(), List.of(member))));
        var result = cashier.collect(project, request, DATE).contextWrite(allowed()).block(WAIT);
        assertEquals("family-user", result.payment().getPayerUserId());
        assertEquals("龚超家人", result.payment().getPayerName());
        assertEquals("龚超", result.lines().get(0).getCustomerName());
    }

    @Test
    void actualConfigurationRequiresProjectCompanyEnabledMethodPcRecordAndCurrentBank() {
        var company = new FinanceCompanyEntity();
        company.setId("company");
        company.setCompanyName("真实配置公司");
        company.setVersion(1L);
        var companySource = mock(InvoiceCompanyService.class);
        when(companySource.company(eq("company"), anyBoolean())).thenReturn(Mono.just(company));
        var dict = mock(RecordedDictionarySource.class);
        when(dict.options("pattern_payment")).thenReturn(Mono.just(Map.of("1", "现金", "3", "转账")));
        when(dict.options("source_charge")).thenReturn(Mono.just(Map.of("1", "PC")));
        var actual =
                new CashierConfiguration(
                        companySource, dict, companyRelations, recordedRows, bankRows);
        var input = input(bill("config"), "10.00");
        rejects(actual.resolve(project, input, false), 409);
        var relation = new FinanceProjectCompanyEntity();
        relation.setId(UUID.randomUUID().toString());
        relation.setProjectId(project);
        relation.setCompanyId("company");
        StandardPersistenceTest.initialize(relation);
        companyRelations.insert(relation).block(WAIT);
        assertEquals(
                "现金", actual.resolve(project, input, false).block(WAIT).funding().get(0).text());
        var bank = new FinanceBankEntity();
        bank.setId(UUID.randomUUID().toString());
        bank.setCompanyId("company");
        bank.setBankName("招商银行");
        bank.setBankAccount("62220000");
        bank.setDefaultBank(true);
        StandardPersistenceTest.initialize(bank);
        bankRows.insert(bank).block(WAIT);
        var record = new RecordedInfoEntity();
        record.setId(UUID.randomUUID().toString());
        record.setProjectId(project);
        record.setCompanyId("company");
        record.setBankId(bank.getId());
        record.setSourceCharge("1");
        record.setPatternPayment("3");
        StandardPersistenceTest.initialize(record);
        recordedRows.insert(record).block(WAIT);
        input.setTenders(
                List.of(new CashierInput.Tender("3", record.getId(), new BigDecimal("10.00"))));
        assertEquals(
                "62220000",
                actual.resolve(project, input, false).block(WAIT).funding().get(0).bankAccount());
        bankRows.createUpdate()
                .set("deleted", true)
                .where("id", bank.getId())
                .execute()
                .block(WAIT);
        rejects(actual.resolve(project, input, false), 409);
        bankRows.createUpdate()
                .set("deleted", false)
                .where("id", bank.getId())
                .execute()
                .block(WAIT);
        recordedRows
                .createUpdate()
                .set("projectId", "foreign")
                .where("id", record.getId())
                .execute()
                .block(WAIT);
        rejects(actual.resolve(project, input, false), 409);
        when(dict.options("pattern_payment")).thenReturn(Mono.just(Map.of("1", "现金")));
        rejects(actual.resolve(project, input, false), 409);
    }

    @Test
    void httpPaymentFlowAndRecordOrFiltersCannotExpandProject() {
        var row = bill("http");
        var input = input(row, "100.00");
        input.setPaidAt(LocalDate.now() + "T10:00:00");
        var controller =
                new org.jetlinks.pro.property.finance.web.CashierController(
                        cashier, records, receivables, new BillingJobProperties());
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                controller)
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) -> chain.filter(exchange).contextWrite(allowed()))
                        .build();
        var path = "/property/finance/projects/" + project + "/cashier";
        var preview =
                client.post()
                        .uri(path + "/_preview")
                        .bodyValue(input)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(CashierService.Preview.class)
                        .returnResult()
                        .getResponseBody();
        var request =
                new CashierService.Submission(UUID.randomUUID().toString(), preview.token(), input);
        var detail =
                client.post()
                        .uri(path + "/_collect")
                        .bodyValue(request)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(CashierService.Detail.class)
                        .returnResult()
                        .getResponseBody();
        assertEquals("100.00", detail.payment().getAmount().toPlainString());
        client.get()
                .uri(path + "/payments/" + detail.payment().getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.payment.requestFingerprint")
                .doesNotExist();
        client.post()
                .uri(path + "/payments/_query")
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
        client.post()
                .uri(path + "/payments/_query")
                .bodyValue(Map.of("pageIndex", 0, "pageSize", 1))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1)
                .jsonPath("$.data[0].line.amount")
                .isEqualTo("100.00");
        StepVerifier.create(
                        records.query(
                                project,
                                new org.hswebframework.web.api.crud.entity.QueryParamEntity()))
                .expectError(
                        org.hswebframework.web.authorization.exception.UnAuthorizedException.class)
                .verify(WAIT);
    }

    @Test
    void lastSpaceDeniedLeavesNoPaymentAndListingNeverLeaksOtherSpacesInMixedReceipt() {
        var a = bill("visible");
        var b = bill("secret");
        bills.createUpdate()
                .set("spaceId", "secret-space")
                .where("id", b.getId())
                .execute()
                .block(WAIT);
        var input = input(a, "200.00");
        input.setItems(
                List.of(
                        new CashierInput.Item(a.getId(), 0L, new BigDecimal("100.00")),
                        new CashierInput.Item(b.getId(), 0L, new BigDecimal("100.00"))));
        var request = submission(input);
        var restricted = allowed();
        var holder =
                restricted.<org.jetlinks.pro.assets.AssetsHolder>get(
                        org.jetlinks.pro.assets.AssetsHolder.class);
        when(holder.assertPermission(anyString(), anyCollection(), any()))
                .thenAnswer(
                        call ->
                                call.<Collection<String>>getArgument(1).contains("secret-space")
                                        ? Mono.error(FinanceScope.error("permission_denied", 403))
                                        : Mono.empty());
        StepVerifier.create(cashier.collect(project, request, DATE).contextWrite(restricted))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 403))
                .verify(WAIT);
        assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals("0.00", bills.findById(a.getId()).block(WAIT).getPaidAmount().toPlainString());
        var receipt = cashier.collect(project, request, DATE).contextWrite(allowed()).block(WAIT);
        var visible =
                records.query(
                                project,
                                new org.hswebframework.web.api.crud.entity.QueryParamEntity())
                        .contextWrite(restricted)
                        .block(WAIT);
        assertEquals(1, visible.getTotal());
        assertEquals("100.00", visible.getData().get(0).line().getAmount().toPlainString());
        StepVerifier.create(
                        cashier.detail(project, receipt.payment().getId()).contextWrite(restricted))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 403))
                .verify(WAIT);
    }

    @Test
    void concurrentDifferentClientsCannotBothCollectTheSameBalance() {
        var row = bill("race");
        var input = input(row, "100.00");
        var a = submission(input);
        var b = new CashierService.Submission(UUID.randomUUID().toString(), a.token(), a.input());
        var outcomes =
                Flux.merge(
                                cashier.collect(project, a, DATE)
                                        .contextWrite(allowed())
                                        .materialize(),
                                cashier.collect(project, b, DATE)
                                        .contextWrite(allowed())
                                        .materialize())
                        .collectList()
                        .block(WAIT);
        assertEquals(1, outcomes.stream().filter(reactor.core.publisher.Signal::isOnNext).count());
        assertEquals(1, outcomes.stream().filter(reactor.core.publisher.Signal::isOnError).count());
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals("0.00", bills.findById(row.getId()).block(WAIT).getArrears().toPlainString());
    }

    @Test
    void eitherSelectedCustomerCanPayInFullAndUnselectedRoomCustomerCannot() {
        var row = bill("shared");
        var parsed = com.alibaba.fastjson.JSON.parseObject(row.getSnapshotJson());
        parsed.put(
                "customers",
                List.of(
                        Map.of("customerId", "customer", "relationshipId", "link", "name", "龚超"),
                        Map.of(
                                "customerId",
                                "second",
                                "relationshipId",
                                "second-link",
                                "name",
                                "张女士")));
        bills.createUpdate()
                .set("snapshotJson", parsed.toJSONString())
                .where("id", row.getId())
                .execute()
                .block(WAIT);
        var fixture = new HouseSettingPersistenceTest();
        fixture.project = project;
        var base = fixture.context("room");
        var second =
                new org.jetlinks.pro.property.base.archive.PropertyHouseCustomer(
                        "second-link",
                        "second",
                        "second-user",
                        "person",
                        "张女士",
                        "123",
                        "tenant",
                        1L,
                        1L,
                        1L);
        var unselected =
                new org.jetlinks.pro.property.base.archive.PropertyHouseCustomer(
                        "third-link",
                        "third",
                        "third-user",
                        "person",
                        "未选客户",
                        "123",
                        "owner",
                        1L,
                        1L,
                        1L);
        when(archive.context(eq(project), eq("room"), anyBoolean()))
                .thenReturn(
                        Mono.just(
                                new org.jetlinks.pro.property.base.archive.PropertyHouseContext(
                                        base.house(),
                                        List.of(base.customers().get(0), second, unselected))));
        var candidates =
                cashier.payerOptions(project, List.of(row.getId()))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(
                List.of("link", "second-link"),
                candidates.stream().map(CashierService.PayerOption::relationshipId).toList());
        var a = input(row, "100.00");
        a.setPayerRelationshipId("third-link");
        rejects(cashier.preview(project, a, DATE), 409);
        a.setPayerRelationshipId("link");
        cashier.collect(project, submission(a), DATE).contextWrite(allowed()).block(WAIT);
        var another = bill("shared-second");
        bills.createUpdate().set("snapshotJson", parsed.toJSONString())
                .where("id", another.getId()).execute().block(WAIT);
        var b = input(another, "100.00");
        b.setPayerRelationshipId("second-link");
        var payment =
                cashier.collect(project, submission(b), DATE).contextWrite(allowed()).block(WAIT);
        assertEquals("张女士", payment.payment().getPayerName());
        assertEquals("second-user", payment.payment().getPayerUserId());
        assertEquals(2, bills.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                "100.00", bills.findById(row.getId()).block(WAIT).getPaidAmount().toPlainString());
        assertEquals("0.00", bills.findById(row.getId()).block(WAIT).getArrears().toPlainString());
    }

    @Test
    void reductionAndCollectionCommitTogetherAndRetryDoesNotReduceTwice() {
        var row = bill("reduction");
        var input = input(row, "90.00");
        input.setReduction(new CashierInput.Reduction("AMOUNT", new BigDecimal("10.00"), "当次优惠"));
        var request = submission(input);
        assertEquals(0, bills.findById(row.getId()).block(WAIT).getFeeReductionAmount().signum());
        var result = cashier.collect(project, request, DATE).contextWrite(allowed()).block(WAIT);
        var stored = bills.findById(row.getId()).block(WAIT);
        assertEquals(new BigDecimal("10.00"), stored.getFeeReductionAmount());
        assertEquals(new BigDecimal("90.00"), stored.getPaidAmount());
        assertEquals(0, stored.getArrears().signum());
        assertEquals("当次优惠", ((Map<?, ?>) result.snapshot().get("reduction")).get("reason"));
        assertEquals(result.payment().getId(), cashier.collect(project, request, DATE).contextWrite(allowed()).block(WAIT).payment().getId());
        assertEquals(1, logs.createQuery().where("receivableId", row.getId()).count().block(WAIT));
        var log = logs.createQuery().where("receivableId", row.getId()).fetchOne().block(WAIT);
        assertTrue(log.getDetailJson().contains("当次优惠"));
    }

    @Test
    void cashierQueryCannotUseOrToIncludeUnapprovedOrOtherProjectBills() {
        var approved = bill("approved");
        var pending = bill("pending");
        bills.createUpdate().set("reviewState", "PENDING").where("id", pending.getId()).execute().block(WAIT);
        var foreign = bill("foreign");
        bills.createUpdate().set("projectId", "other-project").where("id", foreign.getId()).execute().block(WAIT);
        var query = new org.hswebframework.web.api.crud.entity.QueryParamEntity();
        query.toQuery().is("id", approved.getId()).or().is("id", pending.getId()).or().is("id", foreign.getId());
        var controller = new org.jetlinks.pro.property.finance.web.CashierController(
                cashier, records, receivables, new BillingJobProperties());
        var result = controller.bills(project, query).contextWrite(allowed()).block(WAIT);
        assertEquals(List.of(approved.getId()), result.getData().stream().map(ReceivableEntity::getId).toList());
        rejects(cashier.preview(project, input(pending, "1.00"), DATE), 409);
    }

    @Configuration
    @Import({
        HouseSettingPersistenceTest.Config.class,
        CashierService.class,
        HistoricalReceiptFixture.class,
        PaymentLedger.class,
        PaymentRules.class,
        PaymentRecordService.class
    })
    static class Config {
        @Bean
        CashierConfiguration cashierConfiguration() {
            return mock(CashierConfiguration.class);
        }
    }
}
