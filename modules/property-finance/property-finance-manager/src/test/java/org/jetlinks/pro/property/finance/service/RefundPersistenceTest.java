package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

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
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.*;

/** 真实PostgreSQL验证资金闭环、来源余额、幂等竞争和事务回滚，不替换账本为mock。 */
@SpringBootTest(
        classes = RefundPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RefundPersistenceTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        HouseSettingPersistenceTest.database(registry);
    }

    @Autowired CashierService cashier;
    @Autowired CashierConfiguration configuration;
    @Autowired HouseArchiveSource archive;
    @Autowired RefundService refunds;
    @Autowired RefundRecordService records;
    @Autowired ReactiveRepository<ReceivableEntity, String> bills;
    @Autowired ReactiveRepository<PaymentAllocationEntity, String> sources;
    @Autowired ReactiveRepository<RefundEntity, String> refundRows;
    @Autowired ReactiveRepository<RefundAllocationEntity, String> lines;
    @Autowired ReactiveRepository<ReceivableLogEntity, String> logs;
    CashierPersistenceTest fixture;
    String project;

    @BeforeEach
    void setup() {
        fixture = new CashierPersistenceTest();
        fixture.cashier = cashier;
        fixture.configuration = configuration;
        fixture.archive = archive;
        fixture.bills = bills;
        fixture.setup();
        project = fixture.project;
    }

    CashierService.Detail collect(ReceivableEntity bill, String amount) {
        return cashier.collect(project, fixture.submission(fixture.input(bill, amount)), DATE)
                .contextWrite(fixture.allowed())
                .block(WAIT);
    }

    RefundInput input(List<PaymentAllocationEntity> lines, String amount) {
        var input = new RefundInput();
        input.setItems(
                lines.stream()
                        .map(line -> new RefundInput.Item(line.getId(), line.getVersion()))
                        .toList());
        input.setAmount(new BigDecimal(amount));
        input.setCompanyId("company");
        input.setMethod("1");
        input.setRefundedAt("2024-02-15T12:00:00");
        input.setReceiverName("退款收款人");
        input.setReason("多收退还");
        return input;
    }

    RefundService.Submission submission(RefundInput input) {
        var preview =
                refunds.preview(project, input, DATE).contextWrite(fixture.allowed()).block(WAIT);
        return new RefundService.Submission(UUID.randomUUID().toString(), preview.token(), input);
    }

    RefundService.Detail confirm(RefundService.Submission request) {
        return refunds.confirm(project, request, DATE).contextWrite(fixture.allowed()).block(WAIT);
    }

    void rejects(Mono<?> action, int status) {
        fixture.rejects(action, status);
    }

    long count() {
        return refundRows.createQuery().where("projectId", project).count().block(WAIT);
    }

    void money(String expected, BigDecimal actual) {
        assertEquals(new BigDecimal(expected), actual);
    }

    @Autowired FinancialPeriodTestSupport periods;

    @Test
    void closedPeriodBlocksNewRefundButPreservesSuccessfulOriginalRequest() {
        var bill = fixture.bill("period-refund");
        var paid = collect(bill, "40.00");
        var original = submission(input(paid.lines(), "10.00"));
        var result = confirm(original);
        var current = sources.findById(paid.lines().get(0).getId()).block(WAIT);
        var next = submission(input(List.of(current), "10.00"));
        periods.close(project, DATE).contextWrite(fixture.allowed()).block(WAIT);
        FinancialPeriodTestSupport.rejectsClosed(
                refunds.confirm(project, next, DATE).contextWrite(fixture.allowed()));
        assertEquals(result.refund().getId(), confirm(original).refund().getId());
        assertEquals(1, count());
        money("30.00", bills.findById(bill.getId()).block(WAIT).getPaidAmount());
    }

    @Test
    void partialRefundRestoresDebtPreservesOriginalReceiptAndCanRefundAgainAfterRecollection() {
        var bill = fixture.bill("one");
        var receipt = collect(bill, "60.00");
        var input = input(receipt.lines(), "10.01");
        var request = submission(input);
        assertEquals(0, count());
        money("40.00", bills.findById(bill.getId()).block(WAIT).getArrears());
        var result = confirm(request);
        money("10.01", result.refund().getAmount());
        money("49.99", bills.findById(bill.getId()).block(WAIT).getPaidAmount());
        money("50.01", bills.findById(bill.getId()).block(WAIT).getArrears());
        var source = sources.findById(receipt.lines().get(0).getId()).block(WAIT);
        money("60.00", source.getAmount());
        money("10.01", source.getRefundedAmount());
        assertEquals(result.refund().getId(), confirm(request).refund().getId());
        assertEquals(1, count());
        input.setReason("同号修改原因");
        rejects(refunds.confirm(project, request, DATE), 409);
        var second = confirm(submission(input(List.of(source), "49.99")));
        money("49.99", second.refund().getAmount());
        money("100.00", bills.findById(bill.getId()).block(WAIT).getArrears());
        var recollected = collect(bills.findById(bill.getId()).block(WAIT), "100.00");
        money("100.00", recollected.payment().getAmount());
        money("0.00", bills.findById(bill.getId()).block(WAIT).getArrears());
        assertEquals(
                2,
                logs.createQuery()
                        .where("projectId", project)
                        .and("operation", "REFUND")
                        .count()
                        .block(WAIT));
    }

    @Test
    void differentClientsRaceCannotBothRefundAndSameRequestRetriesReturnSameResult() {
        var receipt = collect(fixture.bill("race"), "60.00");
        var first = submission(input(receipt.lines(), "40.00"));
        var second =
                new RefundService.Submission(
                        UUID.randomUUID().toString(), first.token(), first.input());
        var results =
                Flux.merge(
                                refunds.confirm(project, first, DATE)
                                        .contextWrite(fixture.allowed())
                                        .materialize(),
                                refunds.confirm(project, second, DATE)
                                        .contextWrite(fixture.allowed())
                                        .materialize())
                        .collectList()
                        .block(WAIT);
        assertEquals(1, results.stream().filter(Signal::isOnNext).count());
        assertEquals(1, results.stream().filter(Signal::isOnError).count());
        assertEquals(1, count());
        money(
                "40.00",
                sources.findById(receipt.lines().get(0).getId()).block(WAIT).getRefundedAmount());

        var other = collect(fixture.bill("same"), "60.00");
        var same = submission(input(other.lines(), "60.00"));
        var twins =
                Flux.merge(
                                refunds.confirm(project, same, DATE)
                                        .contextWrite(fixture.allowed()),
                                refunds.confirm(project, same, DATE)
                                        .contextWrite(fixture.allowed()))
                        .collectList()
                        .block(WAIT);
        assertEquals(2, twins.size());
        assertEquals(twins.get(0).refund().getId(), twins.get(1).refund().getId());
        assertEquals(2, count());
    }

    @Test
    void occupiedOrStaleSourcesCrossProjectAndChangedBusinessDayAreRejectedWithoutWrites() {
        var bill = fixture.bill("blocked");
        var receipt = collect(bill, "60.00");
        var request = submission(input(receipt.lines(), "1.00"));
        bills.createUpdate()
                .set("invoiceOccupiedAmount", new BigDecimal("1.00"))
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        rejects(refunds.confirm(project, request, DATE), 409);
        bills.createUpdate()
                .set("invoiceOccupiedAmount", BigDecimal.ZERO)
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        rejects(refunds.confirm("other-project", request, DATE), 404);
        rejects(refunds.confirm(project, request, DATE.plusDays(1)), 409);
        var source = receipt.lines().get(0);
        sources.createUpdate().set("version", 1L).where("id", source.getId()).execute().block(WAIT);
        rejects(refunds.confirm(project, request, DATE), 409);
        assertEquals(0, count());
        money("60.00", bills.findById(bill.getId()).block(WAIT).getPaidAmount());
        StepVerifier.create(refunds.confirm(project, request, DATE))
                .expectError(
                        org.hswebframework.web.authorization.exception.UnAuthorizedException.class)
                .verify(WAIT);
    }

    @Test
    void cannotRefundBeforeOriginalPaymentOrWithAnotherCompanyAndOverRefundIsRejected() {
        var receipt = collect(fixture.bill("dates"), "10.00");
        var input = input(receipt.lines(), "1.00");
        input.setRefundedAt("2024-02-15T09:59:59");
        rejects(refunds.preview(project, input, DATE), 400);
        input.setRefundedAt("2024-02-15T12:00:00");
        input.setCompanyId("another-company");
        rejects(refunds.preview(project, input, DATE), 409);
        input.setCompanyId("company");
        input.setAmount(new BigDecimal("10.01"));
        rejects(refunds.preview(project, input, DATE), 409);
        assertEquals(0, count());
    }

    @Test
    void monthlyClosedOriginalAmountStaysFixedButRefundRestoresDebtAsNewTransaction() {
        var bill = fixture.bill("closed");
        var receipt = collect(bill, "60.00");
        bills.createUpdate()
                .set("financialClosed", true)
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        confirm(submission(input(receipt.lines(), "10.00")));
        var after = bills.findById(bill.getId()).block(WAIT);
        assertTrue(after.getFinancialClosed());
        money("100.00", after.getActualReceivableAmount());
        money("50.00", after.getPaidAmount());
        money("50.00", after.getArrears());
    }

    @Test
    void secondAuditFailureRollsBackRefundSourceBalancesBillsAndAudit() throws Exception {
        var a = fixture.bill("a");
        var b = fixture.bill("b");
        var receiptA = collect(a, "20.00");
        var receiptB = collect(b, "20.00");
        var sourceLines = new ArrayList<>(receiptA.lines());
        sourceLines.addAll(receiptB.lines());
        var request = submission(input(sourceLines, "20.00"));
        var constraint = "refund_audit_" + UUID.randomUUID().toString().replace("-", "");
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
                                + " CHECK (operation <> 'REFUND' OR receivable_id <> '"
                                + b.getId()
                                + "') NOT VALID");
        assertEquals(0, result.getExitCode(), result.getStderr());
        try {
            StepVerifier.create(
                            refunds.confirm(project, request, DATE).contextWrite(fixture.allowed()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(0, count());
            assertEquals(0, lines.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(
                    0,
                    logs.createQuery()
                            .where("projectId", project)
                            .and("operation", "REFUND")
                            .count()
                            .block(WAIT));
            for (var source : sourceLines)
                money("0.00", sources.findById(source.getId()).block(WAIT).getRefundedAmount());
            money("20.00", bills.findById(a.getId()).block(WAIT).getPaidAmount());
            money("80.00", bills.findById(b.getId()).block(WAIT).getArrears());
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
    void queryAlwaysScopesProjectAndSerializesMoneyAsStrings() throws Exception {
        var receipt = collect(fixture.bill("query"), "20.00");
        var result = confirm(submission(input(receipt.lines(), "10.00")));
        var query = new QueryParamEntity();
        query.toQuery().where("projectId", "other-project").or("projectId", project);
        var page = records.query(project, query).contextWrite(fixture.allowed()).block(WAIT);
        assertEquals(1, page.getTotal());
        assertEquals(result.refund().getId(), page.getData().get(0).line().getRefundId());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(result));
        assertTrue(json.path("refund").path("amount").isTextual());
        assertEquals("10.00", json.path("lines").get(0).path("amount").asText());
        assertFalse(json.path("refund").has("requestFingerprint"));
        rejects(refunds.detail("other-project", result.refund().getId()), 404);
    }

    @Configuration
    @Import({
        CashierPersistenceTest.Config.class,
        RefundService.class,
        RefundSourceService.class,
        RefundLedger.class,
        RefundRecordService.class
    })
    static class Config {}

    @Test
    void deniedSpaceCannotRefundAndListsNeverLeakOtherRooms() {
        var a = fixture.bill("visible");
        var b = fixture.bill("secret");
        bills.createUpdate()
                .set("spaceId", "secret-space")
                .where("id", b.getId())
                .execute()
                .block(WAIT);
        var sourceLines = new ArrayList<>(collect(a, "20.00").lines());
        sourceLines.addAll(collect(bills.findById(b.getId()).block(WAIT), "20.00").lines());
        var request = submission(input(sourceLines, "20.00"));
        var restricted = fixture.allowed();
        var holder =
                restricted.<org.jetlinks.pro.assets.AssetsHolder>get(
                        org.jetlinks.pro.assets.AssetsHolder.class);
        org.mockito.Mockito.when(
                        holder.assertPermission(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyCollection(),
                                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        call ->
                                call.<Collection<String>>getArgument(1).contains("secret-space")
                                        ? Mono.error(FinanceScope.error("permission_denied", 403))
                                        : Mono.empty());
        StepVerifier.create(refunds.confirm(project, request, DATE).contextWrite(restricted))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 403))
                .verify(WAIT);
        assertEquals(0, count());
        var result = confirm(request);
        var page =
                records.query(project, new QueryParamEntity()).contextWrite(restricted).block(WAIT);
        assertEquals(1, page.getTotal());
        money("10.00", page.getData().get(0).line().getAmount());
        StepVerifier.create(
                        refunds.detail(project, result.refund().getId()).contextWrite(restricted))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 403))
                .verify(WAIT);
    }

    @Test
    void httpPreviewConfirmQueryAndReadonlyDetailUseRealDatabase() {
        var receipt = collect(fixture.bill("http"), "20.00");
        var input = input(receipt.lines(), "10.01");
        input.setRefundedAt(java.time.LocalDate.now() + "T12:00:00");
        var controller =
                new org.jetlinks.pro.property.finance.web.RefundController(
                        refunds, records, new BillingJobProperties());
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                controller)
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange).contextWrite(fixture.allowed()))
                        .build();
        var path = "/property/finance/projects/" + project + "/refunds";
        var preview =
                client.post()
                        .uri(path + "/_preview")
                        .bodyValue(input)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(RefundService.Preview.class)
                        .returnResult()
                        .getResponseBody();
        assertEquals("10.01", preview.total());
        var request =
                new RefundService.Submission(UUID.randomUUID().toString(), preview.token(), input);
        var result =
                client.post()
                        .uri(path + "/_confirm")
                        .bodyValue(request)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(RefundService.Detail.class)
                        .returnResult()
                        .getResponseBody();
        client.get()
                .uri(path + "/" + result.refund().getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.refund.amount")
                .isEqualTo("10.01")
                .jsonPath("$.refund.requestFingerprint")
                .doesNotExist();
        client.post()
                .uri(path + "/_query")
                .bodyValue(Map.of("pageIndex", 0, "pageSize", 1))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1);
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
                                                "other-project",
                                                "type",
                                                "or"))))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(0);
        client.delete()
                .uri(path + "/" + result.refund().getId())
                .exchange()
                .expectStatus()
                .is4xxClientError();
        StepVerifier.create(refunds.detail(project, result.refund().getId()))
                .expectError(
                        org.hswebframework.web.authorization.exception.UnAuthorizedException.class)
                .verify(WAIT);
    }
}
