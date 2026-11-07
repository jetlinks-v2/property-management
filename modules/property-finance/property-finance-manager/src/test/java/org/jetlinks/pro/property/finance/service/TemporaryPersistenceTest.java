package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
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

/** 临时收款没有应收单；真实PG验证统一实收和退款不会凭空生成或改写应收。 */
@SpringBootTest(
        classes = TemporaryPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TemporaryPersistenceTest {
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        HouseSettingPersistenceTest.database(registry);
    }

    @Autowired TemporaryService service;
    @Autowired CashierConfiguration configuration;
    @Autowired HouseArchiveSource archive;
    @Autowired RefundService refunds;
    @Autowired CashierService cashier;
    @Autowired StandardService standardService;
    @Autowired ReactiveRepository<StandardEntity, String> standards;
    @Autowired ReactiveRepository<ProjectSubjectEntity, String> issued;
    @Autowired ReactiveRepository<SubjectEntity, String> subjects;
    @Autowired ReactiveRepository<PaymentEntity, String> payments;
    @Autowired ReactiveRepository<PaymentAllocationEntity, String> lines;
    @Autowired ReactiveRepository<ReceivableEntity, String> bills;
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
    }

    TemporaryInput input() {
        var input = new TemporaryInput();
        input.setSpaceId("room");
        input.setRelationshipId("link");
        input.setStandardId(setting.standardId);
        input.setCompanyId("company");
        input.setStartDate("2024-02-01");
        input.setEndDate("2024-02-29");
        input.setAccountPeriod("2024-02");
        input.setPaidAt("2024-02-15T12:00:00");
        input.setPayerName("柜台代缴人");
        input.setAmount(new BigDecimal("100.01"));
        input.setRemark("临时保洁收费");
        input.setTenders(
                List.of(
                        new CashierInput.Tender("1", null, new BigDecimal("60.00")),
                        new CashierInput.Tender("3", "bank", new BigDecimal("40.01"))));
        return input;
    }

    TemporaryService.Submission request(TemporaryInput input) {
        var preview =
                service.preview(project, input, DATE).contextWrite(fixture.allowed()).block(WAIT);
        return new TemporaryService.Submission(
                UUID.randomUUID().toString(), preview.token(), input);
    }

    TemporaryService.Detail collect(TemporaryInput input) {
        return service.collect(project, request(input), DATE)
                .contextWrite(fixture.allowed())
                .block(WAIT);
    }

    @Autowired FinancialPeriodTestSupport periods;

    @Test
    void selectedSubjectMustOwnTheStandardAndCompanyCannotBeForged() {
        var input = input();
        input.setSubjectId("another-subject");
        fixture.rejects(service.preview(project, input, DATE), 409);
        input.setSubjectId(setting.subjectId);
        input.setCompanyId("another-company");
        fixture.rejects(service.preview(project, input, DATE), 409);
        assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void closedPeriodBlocksNewTemporaryCollectionButRecoversOriginalReceipt() {
        var original = request(input());
        var result =
                service.collect(project, original, DATE)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        var next = request(input());
        periods.close(project, DATE).contextWrite(fixture.allowed()).block(WAIT);
        FinancialPeriodTestSupport.rejectsClosed(
                service.collect(project, next, DATE).contextWrite(fixture.allowed()));
        assertEquals(
                result.payment().getId(),
                service.collect(project, original, DATE)
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .payment()
                        .getId());
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(2, lines.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void mixedReceiptHasNoBillAndKeepsHistoricalSnapshots() {
        var result = collect(input());
        assertEquals("TEMPORARY", result.payment().getSourceType());
        assertEquals(new BigDecimal("100.01"), result.payment().getAmount());
        assertEquals(2, result.lines().size());
        assertTrue(result.lines().stream().allMatch(line -> line.getReceivableId() == null));
        assertTrue(
                result.lines().stream()
                        .allMatch(line -> setting.standardId.equals(line.getStandardId())));
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
        standards
                .createUpdate()
                .set("standardName", "已修改标准")
                .where("id", setting.standardId)
                .execute()
                .block(WAIT);
        var detail =
                service.detail(project, result.payment().getId())
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals("面积月费", ((Map<?, ?>) detail.snapshot().get("standard")).get("standardName"));
        assertEquals("2024-02", detail.snapshot().get("accountPeriod"));
        assertEquals(
                detail.snapshot(),
                cashier.detail(project, result.payment().getId())
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .snapshot());
        fixture.rejects(
                standardService.delete(
                        project,
                        setting.standardId,
                        standards.findById(setting.standardId).block(WAIT).getVersion()),
                409);
        assertNotNull(detail.snapshot().get("tax"));
    }

    @Test
    void partialRefundReturnsSourceBalanceWithoutTouchingOtherBills() {
        var unrelated = fixture.bill("unrelated");
        var result = collect(input());
        var input = new RefundInput();
        input.setCompanyId("company");
        input.setMethod("1");
        input.setAmount(new BigDecimal("25.01"));
        input.setRefundedAt("2024-02-15T13:00:00");
        input.setReceiverName("退款收款人");
        input.setReason("部分退临时款");
        input.setItems(
                result.lines().stream()
                        .map(line -> new RefundInput.Item(line.getId(), line.getVersion()))
                        .toList());
        var preview =
                refunds.preview(project, input, DATE).contextWrite(fixture.allowed()).block(WAIT);
        var refund =
                refunds.confirm(
                                project,
                                new RefundService.Submission(
                                        UUID.randomUUID().toString(), preview.token(), input),
                                DATE)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        assertEquals(new BigDecimal("25.01"), refund.refund().getAmount());
        assertTrue(refund.lines().stream().allMatch(line -> line.getReceivableId() == null));
        assertEquals(
                new BigDecimal("25.01"),
                lines.createQuery()
                        .where("paymentId", result.payment().getId())
                        .fetch()
                        .map(PaymentAllocationEntity::getRefundedAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .block(WAIT));
        var bill = bills.findById(unrelated.getId()).block(WAIT);
        assertEquals(unrelated.getVersion(), bill.getVersion());
        assertEquals(new BigDecimal("100.00"), bill.getArrears());
    }

    @Test
    void sameRequestReusesPaymentButStaleOrChangedRequestsCannotCollect() {
        var input = input();
        var request = request(input);
        var first =
                service.collect(project, request, DATE).contextWrite(fixture.allowed()).block(WAIT);
        var retry =
                service.collect(project, request, DATE).contextWrite(fixture.allowed()).block(WAIT);
        assertEquals(first.payment().getId(), retry.payment().getId());
        input.setRemark("不同内容");
        fixture.rejects(service.collect(project, request, DATE), 409);
        var stale = request(input());
        standards
                .createUpdate()
                .set("version", 2L)
                .where("id", setting.standardId)
                .execute()
                .block(WAIT);
        fixture.rejects(service.collect(project, stale, DATE), 409);
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void validatesPaymentsDatesCurrentCustomerAndProjectScope() {
        var invalid = input();
        invalid.setAmount(new BigDecimal("100.02"));
        fixture.rejects(service.preview(project, invalid, DATE), 400);
        invalid = input();
        invalid.setEndDate("2024-01-01");
        fixture.rejects(service.preview(project, invalid, DATE), 400);
        invalid = input();
        invalid.setRelationshipId("unknown");
        fixture.rejects(service.preview(project, invalid, DATE), 409);
        var result = collect(input());
        fixture.rejects(service.detail("other", result.payment().getId()), 404);
        var query = new QueryParamEntity();
        query.toQuery().or("projectId", "other");
        assertEquals(
                0,
                service.query(project, query)
                        .contextWrite(fixture.allowed())
                        .block(WAIT)
                        .getTotal());
    }

    @Test
    void secondTenderFailureRollsBackMainAndFirstTender() throws Exception {
        var constraint = "temporary_" + UUID.randomUUID().toString().replace("-", "");
        var sql =
                "ALTER TABLE pm_fin_payment_line ADD CONSTRAINT "
                        + constraint
                        + " CHECK (project_id <> '"
                        + project
                        + "' OR sort_index <> 1)";
        assertEquals(
                0,
                HouseSettingPersistenceTest.DB
                        .execInContainer("psql", "-U", "postgres", "-d", "setting_test", "-c", sql)
                        .getExitCode());
        try {
            var request = request(input());
            reactor.test.StepVerifier.create(
                            service.collect(project, request, DATE).contextWrite(fixture.allowed()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(0, lines.createQuery().where("projectId", project).count().block(WAIT));
        } finally {
            HouseSettingPersistenceTest.DB.execInContainer(
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "setting_test",
                    "-c",
                    "ALTER TABLE pm_fin_payment_line DROP CONSTRAINT " + constraint);
        }
    }

    @Test
    void concurrentSameRequestKeepsOneReceiptAndSourceInvoiceBlocksRefund() {
        var request = request(input());
        var attempts =
                Flux.merge(
                                service.collect(project, request, DATE)
                                        .contextWrite(fixture.allowed())
                                        .map(v -> true)
                                        .onErrorReturn(false),
                                service.collect(project, request, DATE)
                                        .contextWrite(fixture.allowed())
                                        .map(v -> true)
                                        .onErrorReturn(false))
                        .collectList()
                        .block(WAIT);
        assertTrue(attempts.contains(true));
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(2, lines.createQuery().where("projectId", project).count().block(WAIT));
        var original =
                service.collect(project, request, DATE).contextWrite(fixture.allowed()).block(WAIT);
        var source = original.lines().get(0);
        lines.createUpdate()
                .set("invoiceOccupiedAmount", new BigDecimal("1.00"))
                .where("id", source.getId())
                .execute()
                .block(WAIT);
        var refund = new RefundInput();
        refund.setCompanyId("company");
        refund.setMethod("1");
        refund.setAmount(new BigDecimal("1.00"));
        refund.setRefundedAt("2024-02-15T13:00:00");
        refund.setReceiverName("客户");
        refund.setReason("退临时款");
        refund.setItems(List.of(new RefundInput.Item(source.getId(), source.getVersion())));
        fixture.rejects(refunds.preview(project, refund, DATE), 409);
    }

    @Test
    void deniedSpaceCannotPreviewOrWriteAndCannotReadHistoricalDetails() {
        var input = input();
        var request = request(input);
        var denied = fixture.allowed();
        var holder =
                denied.<org.jetlinks.pro.assets.AssetsHolder>get(
                        org.jetlinks.pro.assets.AssetsHolder.class);
        when(holder.assertPermission(anyString(), anyCollection(), any()))
                .thenAnswer(
                        call ->
                                call.<Collection<String>>getArgument(1).contains("room")
                                        ? Mono.error(FinanceScope.error("permission_denied", 403))
                                        : Mono.empty());
        reactor.test.StepVerifier.create(service.preview(project, input, DATE).contextWrite(denied))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 403))
                .verify(WAIT);
        reactor.test.StepVerifier.create(
                        service.collect(project, request, DATE).contextWrite(denied))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 403))
                .verify(WAIT);
        assertEquals(0, payments.createQuery().where("projectId", project).count().block(WAIT));
        var result = collect(input);
        reactor.test.StepVerifier.create(
                        service.detail(project, result.payment().getId()).contextWrite(denied))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 403))
                .verify(WAIT);
    }

    @Test
    void receiptAndLineSourceTypesMustAgreeBeforeRefund() {
        var result = collect(input());
        payments.createUpdate()
                .set("sourceType", "RECEIVABLE")
                .where("id", result.payment().getId())
                .execute()
                .block(WAIT);
        var refund = new RefundInput();
        refund.setCompanyId("company");
        refund.setMethod("1");
        refund.setAmount(new BigDecimal("1.00"));
        refund.setRefundedAt("2024-02-15T13:00:00");
        refund.setReceiverName("客户");
        refund.setReason("不能退损坏来源");
        refund.setItems(
                List.of(
                        new RefundInput.Item(
                                result.lines().get(0).getId(),
                                result.lines().get(0).getVersion())));
        fixture.rejects(refunds.preview(project, refund, DATE), 409);
    }

    @Test
    void httpUsesExactAmountsReadOnlySnapshotAndCannotBypassTypeFilter() {
        var controller =
                new org.jetlinks.pro.property.finance.web.TemporaryController(
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
        var input = input();
        input.setAmount(new BigDecimal("9007199254740993.01"));
        input.setTenders(List.of(new CashierInput.Tender("1", null, input.getAmount())));
        var path = "/property/finance/projects/" + project + "/temporary";
        var preview =
                client.post()
                        .uri(path + "/_preview")
                        .bodyValue(input)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(TemporaryService.Preview.class)
                        .returnResult()
                        .getResponseBody();
        var result =
                client.post()
                        .uri(path + "/_collect")
                        .bodyValue(
                                new TemporaryService.Submission(
                                        UUID.randomUUID().toString(), preview.token(), input))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("$.payment.amount")
                        .isEqualTo("9007199254740993.01")
                        .jsonPath("$.payment.requestFingerprint")
                        .doesNotExist()
                        .jsonPath("$.payment.sourceSnapshotJson")
                        .doesNotExist()
                        .jsonPath("$.snapshot.standard.standardName")
                        .isEqualTo("面积月费")
                        .returnResult();
        assertNotNull(result.getResponseBody());
        client.post()
                .uri(path + "/_query")
                .bodyValue(
                        Map.of(
                                "terms",
                                List.of(
                                        Map.of(
                                                "column",
                                                "sourceType",
                                                "value",
                                                "RECEIVABLE",
                                                "type",
                                                "or"))))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(0);
        client.delete().uri(path + "/fake").exchange().expectStatus().is4xxClientError();
    }

    @Configuration
    @Import({
        RefundPersistenceTest.Config.class,
        TemporaryService.class,
        TemporarySourceService.class,
        TemporaryLedger.class,
        CollectionStandardSource.class
    })
    static class Config {}
}
