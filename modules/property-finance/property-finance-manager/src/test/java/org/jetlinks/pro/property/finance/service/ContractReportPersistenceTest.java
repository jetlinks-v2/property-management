package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.simple.SimplePermission;
import org.jetlinks.pro.assets.impl.entity.DimensionAssetBindingEntity;
import org.jetlinks.pro.property.finance.calculation.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.report.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/** 真实PG按业务日期还原合同余额，验证全部历史房屋权限及只读账务。 */
@SpringBootTest(
        classes = ContractReportPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ContractReportPersistenceTest {
    static final Duration WAIT = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        InvoiceCompanyPersistenceTest.database(r);
    }

    @Autowired ContractReportService reports;
    @Autowired ReactiveRepository<ContractMirrorEntity, String> contracts;
    @Autowired ReactiveRepository<ReceivableEntity, String> bills;
    @Autowired ReactiveRepository<PaymentEntity, String> payments;
    @Autowired ReactiveRepository<PaymentAllocationEntity, String> lines;
    @Autowired ReactiveRepository<RefundEntity, String> refunds;
    @Autowired ReactiveRepository<RefundAllocationEntity, String> refundLines;
    @Autowired ReactiveRepository<DimensionAssetBindingEntity, String> bindings;
    @Autowired DatabaseOperator database;
    String project, reader;
    Context allowed;
    CarryForwardAuthorityTest assets;

    @BeforeEach
    void setup() {
        project = UUID.randomUUID().toString();
        reader = UUID.randomUUID().toString();
        assets = new CarryForwardAuthorityTest();
        assets.holders();
        assets.rights.put(reader, Set.of("room", "room2"));
        database.getMetadata()
                .addFeature(new org.jetlinks.pro.assets.impl.terms.DimensionAssetsTermBuilder());
        for (String room : List.of("room", "room2")) {
            var b = new DimensionAssetBindingEntity();
            b.setAssetId(room);
            b.setAssetType("space");
            b.setTargetId(reader);
            b.setTargetType("test");
            b.setPermission(org.jetlinks.pro.assets.crud.CrudAssetPermission.all_mask);
            b.setUpdateTime(System.currentTimeMillis());
            b.generateTargetKey();
            b.setId(b.generateId());
            bindings.insert(b).block(WAIT);
        }
        var auth = assets.auth(reader);
        auth.setPermissions(
                List.of(
                        SimplePermission.builder()
                                .id("property-finance-report-contract")
                                .actions(Set.of("query", "export"))
                                .build()));
        allowed = Context.of(Authentication.class, auth);
    }

    @AfterEach
    void cleanup() {
        assets.close();
    }

    ContractReportInput input(String month) {
        var i = new ContractReportInput();
        i.setProjectId(project);
        i.setMonth(month);
        return i;
    }

    ContractMirrorEntity contract(String code) {
        var c = new ContractMirrorEntity();
        c.setId(UUID.randomUUID().toString());
        c.setTenantId("");
        c.setProjectId(project);
        c.setSourceId(c.getId());
        c.setCode(code);
        c.setCodeKey(c.getId());
        c.setName("合同" + code);
        c.setSpaceId("room");
        c.setFullName("园区/1栋/101");
        c.setCustomerId("customer");
        c.setCustomerName("客户甲");
        c.setStartDate("2024-01-01");
        c.setEndDate("2024-12-31");
        c.setLeaseArea("100.00");
        c.setFingerprint("fixture");
        StandardPersistenceTest.initialize(c);
        contracts.insert(c).block(WAIT);
        return c;
    }

    ReceivableEntity bill(String code, String amount, String start, String end) {
        var fixture = new ReceivableReportPersistenceTest();
        fixture.project = project;
        fixture.bills = bills;
        var b = fixture.bill("1", "0");
        bills.createUpdate()
                .set("contractCode", code)
                .set("contractReceivableAmount", new BigDecimal(amount))
                .set("calcStartDate", start)
                .set("calcEndDate", end)
                .where("id", b.getId())
                .execute()
                .block(WAIT);
        return bills.findById(b.getId()).block(WAIT);
    }

    PaymentAllocationEntity pay(ReceivableEntity b, String amount, String method, String date) {
        var fixture = funds();
        var l = fixture.payment(amount, method, b.getSpaceId(), date);
        lines.createUpdate()
                .set("receivableId", b.getId())
                .where("id", l.getId())
                .execute()
                .block(WAIT);
        l.setReceivableId(b.getId());
        return l;
    }

    void refund(PaymentAllocationEntity l, String amount, String target, String date) {
        var r = funds().refund(l, amount, target, date);
        refundLines
                .createUpdate()
                .set("receivableId", l.getReceivableId())
                .where("refundId", r.getId())
                .execute()
                .block(WAIT);
    }

    ReceivedReportPersistenceTest funds() {
        var f = new ReceivedReportPersistenceTest();
        f.project = project;
        f.payments = payments;
        f.lines = lines;
        f.refunds = refunds;
        f.refundLines = refundLines;
        return f;
    }

    ContractReportResult query(String month) {
        return reports.query(input(month)).contextWrite(allowed).block(WAIT);
    }

    @Test
    void frozenWholePeriodAmountIsAllocatedAndTailIsNotLost() {
        var c = contract("C01");
        var b = bill("C01", "1000", "2024-01-01", "2024-03-31");
        assertEquals("333.33", query("2024-01").summary().currentReceivable());
        assertEquals("333.33", query("2024-02").summary().currentReceivable());
        var march = query("2024-03");
        assertEquals("333.34", march.summary().currentReceivable());
        assertEquals("1000.00", march.summary().closingReceivable());
        var details =
                reports.details(input("2024-03"), c.getId(), "currentReceivable")
                        .contextWrite(allowed)
                        .block(WAIT);
        assertEquals(1, details.getTotal());
        assertEquals(b.getId(), details.getData().get(0).receivableId());
        assertEquals("333.34", details.getData().get(0).amount());
        assertEquals(b.getVersion(), bills.findById(b.getId()).block(WAIT).getVersion());
        assertEquals(
                "1.00",
                bills.findById(b.getId()).block(WAIT).getActualReceivableAmount().toPlainString());
    }

    @Test
    void refundRestoresLaterArrearsWithoutRewritingEarlierMonthAndNegativeDrilldownIsVisible() {
        var c = contract("C01");
        var b = bill("C01", "100", "2024-02-01", "2024-02-29");
        var p = pay(b, "100", "1", "2024-02-29T23:59:59");
        refund(p, "30", "CASH", "2024-03-01T00:00");
        assertEquals("100.00", query("2024-02").summary().currentNetReceived());
        assertEquals("0.00", query("2024-02").summary().closingReceivable());
        assertEquals("-30.00", query("2024-03").summary().currentNetReceived());
        assertEquals("30.00", query("2024-03").summary().closingReceivable());
        var d =
                reports.details(input("2024-03"), c.getId(), "currentNetReceived")
                        .contextWrite(allowed)
                        .block(WAIT);
        assertEquals("-30.00", d.getData().get(0).amount());
    }

    @Test
    void advanceOffsetsAndBalanceReturnsCountExactlyOnce() {
        contract("C01");
        var b = bill("C01", "100", "2024-04-01", "2024-04-30");
        var p = pay(b, "100", "ADVANCE", "2024-02-01T00:00");
        refund(p, "20", "BALANCE", "2024-03-01T00:00");
        assertEquals("100.00", query("2024-02").summary().closingAdvance());
        var march = query("2024-03").summary();
        assertEquals("100.00", march.openingAdvance());
        assertEquals("80.00", march.closingAdvance());
        assertEquals("-20.00", march.currentNetReceived());
    }

    @Test
    void billsDoNotOffsetEachOthersPrepaymentsWithinOneContract() {
        contract("C01");
        bill("C01", "100", "2024-02-01", "2024-02-29");
        var future = bill("C01", "100", "2024-04-01", "2024-04-30");
        pay(future, "100", "DEPOSIT", "2024-02-01T00:00");
        var s = query("2024-02").summary();
        assertEquals("100.00", s.closingReceivable());
        assertEquals("100.00", s.closingAdvance());
    }

    @Test
    void emptyRealContractsRemainAndSubjectScopeUsesTheSameBillSet() {
        contract("C01");
        contract("C02");
        bill("C01", "100", "2024-02-01", "2024-02-29");
        assertEquals(2, query("2024-02").page().getTotal());
        var i = input("2024-02");
        i.setSubjectIds(List.of("missing"));
        assertEquals(0, reports.query(i).contextWrite(allowed).block(WAIT).page().getTotal());
        i.setSubjectIds(List.of("subject"));
        assertEquals(1, reports.query(i).contextWrite(allowed).block(WAIT).page().getTotal());
    }

    @Test
    void missingButtonForeignProjectOrHistoricalHouseDeniesEntireReport() {
        contract("C01");
        var b = bill("C01", "100", "2024-02-01", "2024-02-29");
        bills.createUpdate().set("spaceId", "secret").where("id", b.getId()).execute().block(WAIT);
        StepVerifier.create(reports.query(input("2024-02")).contextWrite(allowed))
                .expectErrorMatches(
                        e ->
                                TaxItemPersistenceTest.status(e, 403)
                                        || e
                                                instanceof
                                                org.hswebframework.web.authorization.exception
                                                        .AccessDenyException)
                .verify(WAIT);
        var noButton = Context.of(Authentication.class, assets.auth(reader));
        StepVerifier.create(reports.query(input("2024-02")).contextWrite(noButton))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        var i = input("2024-02");
        i.setProjectId("forbidden");
        rejects(reports.query(i), 403);
    }

    @Test
    void corruptPaymentLineageAndOrphanedParentsRefusePlausibleTotals() {
        contract("C01");
        var b = bill("C01", "100", "2024-02-01", "2024-02-29");
        var p = pay(b, "50", "1", "2024-02-10T10:00");
        lines.createUpdate().set("projectId", "other").where("id", p.getId()).execute().block(WAIT);
        rejects(reports.query(input("2024-02")), 409);
        lines.createUpdate()
                .set("projectId", project)
                .set("paymentId", "missing")
                .where("id", p.getId())
                .execute()
                .block(WAIT);
        rejects(reports.query(input("2024-02")), 409);
    }

    @Test
    void sixDrilldownsSumToTheirContractSummaryAndZeroBillsStayOutOfDetails() {
        var c = contract("C01");
        var a = bill("C01", "1000", "2024-01-01", "2024-03-31");
        var p = pay(a, "500", "1", "2024-01-01T12:00");
        refund(p, "100", "CASH", "2024-02-15T12:00");
        var future = bill("C01", "300", "2024-04-01", "2024-06-30");
        pay(future, "200", "ADVANCE", "2024-01-05T12:00");
        bill("C01", "0", "2024-01-01", "2024-03-31");
        var s = query("2024-02").page().getData().get(0).amounts();
        for (String metric : ContractReportResult.Amounts.METRICS) {
            var details =
                    reports.details(input("2024-02"), c.getId(), metric)
                            .contextWrite(allowed)
                            .block(WAIT);
            var total =
                    details.getData().stream()
                            .map(d -> new BigDecimal(d.amount()))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, total.compareTo(new BigDecimal(s.value(metric))), metric);
            assertTrue(
                    details.getData().stream()
                            .allMatch(d -> new BigDecimal(d.amount()).signum() != 0));
        }
        var left =
                new BigDecimal(s.openingReceivable())
                        .subtract(new BigDecimal(s.openingAdvance()))
                        .add(new BigDecimal(s.currentReceivable()))
                        .subtract(new BigDecimal(s.currentNetReceived()));
        assertEquals(
                0,
                left.compareTo(
                        new BigDecimal(s.closingReceivable())
                                .subtract(new BigDecimal(s.closingAdvance()))));
    }

    @Test
    void projectTenantDeletionAndSplitScopesApplyBeforeEveryTotal() {
        contract("C01");
        bill("C01", "10", "2024-02-01", "2024-02-29");
        var falseSplit = bill("C01", "20", "2024-02-01", "2024-02-29");
        bills.createUpdate()
                .set("split", false)
                .where("id", falseSplit.getId())
                .execute()
                .block(WAIT);
        for (String field : List.of("deleted", "split")) {
            var b = bill("C01", "9000", "2024-02-01", "2024-02-29");
            bills.createUpdate().set(field, true).where("id", b.getId()).execute().block(WAIT);
        }
        var foreign = bill("C01", "8000", "2024-02-01", "2024-02-29");
        bills.createUpdate()
                .set("projectId", "different-project")
                .where("id", foreign.getId())
                .execute()
                .block(WAIT);
        var removed = contract("REMOVED");
        contracts
                .createUpdate()
                .set("deleted", true)
                .where("id", removed.getId())
                .execute()
                .block(WAIT);
        bill("REMOVED", "7000", "2024-02-01", "2024-02-29");
        var tenant = contract("FOREIGN-TENANT");
        contracts
                .createUpdate()
                .set("tenantId", "different-tenant")
                .where("id", tenant.getId())
                .execute()
                .block(WAIT);
        bill("FOREIGN-TENANT", "6000", "2024-02-01", "2024-02-29");
        var s = query("2024-02");
        assertEquals(1, s.page().getTotal());
        assertEquals("30.00", s.summary().currentReceivable());
        rejects(reports.details(input("2024-02"), removed.getId(), "currentReceivable"), 404);
    }

    @Test
    void paginationAndKeywordDatesDoNotChangeAllRowsSummaryOrMutateSources() {
        contract("A01");
        contract("B02");
        bill("A01", "10.01", "2024-02-01", "2024-02-29");
        bill("B02", "20.02", "2024-02-01", "2024-02-29");
        var i = input("2024-02");
        i.setPageSize(1);
        var first = reports.query(i).contextWrite(allowed).block(WAIT);
        i.setPageIndex(1);
        var second = reports.query(i).contextWrite(allowed).block(WAIT);
        assertEquals(2, first.page().getTotal());
        assertEquals("30.03", first.summary().currentReceivable());
        assertEquals(first.summary(), second.summary());
        assertNotEquals(first.page().getData().get(0).id(), second.page().getData().get(0).id());
        i.setPageIndex(20);
        assertTrue(reports.query(i).contextWrite(allowed).block(WAIT).page().getData().isEmpty());
        i.setPageIndex(0);
        i.setKeyword("A0");
        assertEquals(
                "10.01",
                reports.query(i).contextWrite(allowed).block(WAIT).summary().currentReceivable());
        i.setKeyword("客户甲");
        assertEquals(2, reports.query(i).contextWrite(allowed).block(WAIT).page().getTotal());
        i.setKeyword("' OR 1=1 --");
        assertEquals(0, reports.query(i).contextWrite(allowed).block(WAIT).page().getTotal());
        i.setKeyword(null);
        i.setStartDate("2024-02-01");
        assertEquals(0, reports.query(i).contextWrite(allowed).block(WAIT).page().getTotal());
    }

    @Test
    void malformedDatesFailedParentsAndOverRefundCannotTurnIntoZeroOrNegativeNetPaid() {
        contract("C01");
        var b = bill("C01", "100", "2024-02-01", "2024-02-29");
        var p = pay(b, "100", "1", "2024-02-01T00:00");
        payments.createUpdate()
                .set("paidAt", "2024-02-30T12:00")
                .where("id", p.getPaymentId())
                .execute()
                .block(WAIT);
        rejects(reports.query(input("2024-02")), 409);
        payments.createUpdate()
                .set("paidAt", "2024-02-01T00:00")
                .set("status", "FAILED")
                .where("id", p.getPaymentId())
                .execute()
                .block(WAIT);
        rejects(reports.query(input("2024-02")), 409);
        payments.createUpdate()
                .set("status", "SUCCEEDED")
                .where("id", p.getPaymentId())
                .execute()
                .block(WAIT);
        refund(p, "110", "CASH", "2024-03-01T00:00");
        rejects(reports.query(input("2024-02")), 409);
    }

    @Test
    void invalidQueryOrMetricIsExplicitAndExportRequiresItsOwnAction() {
        var i = input("2024-13");
        rejects(reports.query(i), 400);
        i.setMonth("2024-02");
        i.setPageSize(201);
        rejects(reports.query(i), 400);
        i.setPageSize(12);
        i.setStartDate("2024-02-30");
        rejects(reports.query(i), 400);
        rejects(reports.details(input("2024-02"), "unknown", "sql"), 400);
        rejects(reports.details(input("2024-02"), "unknown", "currentReceivable"), 404);
        var auth = assets.auth(reader);
        auth.setPermissions(
                List.of(
                        SimplePermission.builder()
                                .id("property-finance-report-contract")
                                .actions(Set.of("query"))
                                .build()));
        StepVerifier.create(
                        reports.exportData(input("2024-02"))
                                .contextWrite(Context.of(Authentication.class, auth)))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
    }

    @Test
    void tooManyBillsRefuseAllViewsInsteadOfReturningTruncatedFinancialTotals() {
        var c = contract("C01");
        var template = bill("C01", "1", "2024-02-01", "2024-02-29");
        var bulk = new ArrayList<ReceivableEntity>();
        for (int index = 0; index < 10000; index++) {
            var b = new ReceivableEntity();
            org.springframework.beans.BeanUtils.copyProperties(template, b);
            b.setId(UUID.randomUUID().toString());
            b.setBillKey(b.getId());
            bulk.add(b);
        }
        reactor.core.publisher.Flux.fromIterable(bulk)
                .buffer(250)
                .concatMap(bills::insertBatch)
                .then()
                .block(WAIT);
        rejects(reports.query(input("2024-02")), 400);
        rejects(reports.exportData(input("2024-02")), 400);
        rejects(reports.details(input("2024-02"), c.getId(), "currentReceivable"), 400);
        var i = input("2024-02");
        i.setSubjectIds(List.of("missing"));
        assertEquals(0, reports.query(i).contextWrite(allowed).block(WAIT).page().getTotal());
        bills.createUpdate()
                .set("deleted", true)
                .where("id", template.getId())
                .execute()
                .block(WAIT);
        var started = System.nanoTime();
        var boundary = reports.query(input("2024-02")).contextWrite(allowed).block(WAIT);
        assertEquals("10000.00", boundary.summary().currentReceivable());
        assertEquals(1, boundary.page().getTotal());
        System.out.println(
                "Contract report 10000-bill query elapsed ms="
                        + (System.nanoTime() - started) / 1000000);
    }

    void rejects(Mono<?> action, int code) {
        StepVerifier.create(action.contextWrite(allowed))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, code))
                .verify(WAIT);
    }

    @Test
    void damagedFrozenBillDatesAndAmountsHaveExplicitBusinessResponses() {
        contract("C01");
        var b = bill("C01", "100", "2024-02-01", "2024-02-29");
        bills.createUpdate()
                .set("calcEndDate", "2024-02-30")
                .where("id", b.getId())
                .execute()
                .block(WAIT);
        rejects(reports.query(input("2024-02")), 409);
        bills.createUpdate()
                .set("calcEndDate", "2024-02-29")
                .set("contractReceivableAmount", new BigDecimal("-1"))
                .where("id", b.getId())
                .execute()
                .block(WAIT);
        rejects(reports.query(input("2024-02")), 409);
    }

    @Test
    void refundCannotClaimOneBillWhileItsPaymentBelongsToAnother() {
        contract("C01");
        var a = bill("C01", "100", "2024-02-01", "2024-02-29");
        var other = bill("OTHER-CONTRACT", "100", "2024-02-01", "2024-02-29");
        var p = pay(other, "100", "1", "2024-02-01T12:00");
        var r = funds().refund(p, "20", "CASH", "2024-02-15T12:00");
        refundLines
                .createUpdate()
                .set("receivableId", a.getId())
                .where("refundId", r.getId())
                .execute()
                .block(WAIT);
        rejects(reports.query(input("2024-02")), 409);
    }

    @Configuration
    @org.hswebframework.web.crud.annotation.EnableEasyormRepository(
            "org.jetlinks.pro.assets.impl.entity")
    @Import({
        InvoiceCompanyPersistenceTest.Config.class,
        DocumentQueryScope.class,
        ContractReportService.class,
        ContractReportSource.class,
        ContractMonthAllocation.class,
        BillingProrationService.class
    })
    static class Config {}
}
