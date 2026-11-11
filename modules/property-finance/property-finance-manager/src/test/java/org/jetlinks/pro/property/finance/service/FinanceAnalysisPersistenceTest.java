package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.report.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;

import reactor.util.context.Context;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@SpringBootTest(
        classes = FinanceAnalysisPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FinanceAnalysisPersistenceTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        InvoiceCompanyPersistenceTest.database(r);
    }

    @Autowired FinanceAnalysisService service;
    @Autowired ReactiveRepository<ReceivableEntity, String> bills;
    @Autowired ReactiveRepository<ReceivableLogEntity, String> logs;
    @Autowired ReactiveRepository<FundEntryEntity, String> entries;
    @Autowired ReactiveRepository<FundAccountEntity, String> accounts;
    String project;
    Context allowed;
    static final Duration WAIT = Duration.ofSeconds(30);

    @BeforeEach
    void init() {
        project = UUID.randomUUID().toString();
        allowed = new HouseSettingPersistenceTest().allowed();
    }

    AnalysisInput input() {
        var i = new AnalysisInput();
        i.setProjectIds(List.of(project));
        i.setStartDate("2024-02-01");
        i.setEndDate("2024-02-29");
        return i;
    }

    ReceivableEntity bill(String net, String paid) {
        var f = new ReceivableReportPersistenceTest();
        f.project = project;
        f.bills = bills;
        return f.bill(net, paid);
    }

    AnalysisResult query(String kind, AnalysisInput i) {
        return service.query(kind, i).contextWrite(allowed).block(WAIT);
    }

    @Test
    void currentArrearsBucketsConserveDebtAndDeduplicateSpaces() {
        var b = bill("100", "20");
        var c = bill("50", "0");
        var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        bills.createUpdate()
                .set("dueDate", today.minusDays(45).toString())
                .where("id", b.getId())
                .execute()
                .block(WAIT);
        bills.createUpdate()
                .set("dueDate", today.plusDays(1).toString())
                .where("id", c.getId())
                .execute()
                .block(WAIT);
        var i = input();
        i.setEndDate(today.toString());
        i.setGroupBy("AGE");
        var r = query("arrears", i);
        assertEquals("130.00", r.summary().get("debt"));
        assertEquals("80.00", r.summary().get("days90"));
        assertEquals("50.00", r.summary().get("notDue"));
        assertEquals(1L, ((Number) r.summary().get("spaceCount")).longValue());
        assertEquals(2, r.page().getTotal());
        i.setAgeBucket("DAYS_90");
        i.setGroupBy("DETAIL");
        assertEquals(1, query("arrears", i).page().getTotal());
    }

    @Test
    void missingHistoryIsAnExplicitFailure() {
        bill("100", "0");
        var ex = assertThrows(RuntimeException.class, () -> query("monthly", input()));
        assertTrue(TaxItemPersistenceTest.status(ex, 409));
    }

    void log(ReceivableEntity row, long version, String before, String after, String at) {
        var l = new ReceivableLogEntity();
        l.setId(UUID.randomUUID().toString());
        l.setProjectId(project);
        l.setSpaceId(row.getSpaceId());
        l.setReceivableId(row.getId());
        l.setOperation("ADJUST");
        l.setReason("测试");
        l.setOperatorId("tester");
        l.setOperatorName("财务");
        l.setBeforeVersion(version - 1);
        l.setAfterVersion(version);
        l.setBeforeJson(before);
        l.setAfterJson(after);
        l.setCreateTime(
                LocalDateTime.parse(at)
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .toInstant()
                        .toEpochMilli());
        logs.insert(l).block(WAIT);
    }

    @Test
    void historySnapshotsAreStableAfterLaterPaymentAndReconcileMonth() {
        var b = bill("100", "0");
        log(b, 0, "{}", ReceivableService.auditSnapshot(b), "2024-01-02T09:00:00");
        String before = ReceivableService.auditSnapshot(b);
        b.setPaidAmount(new BigDecimal("30"));
        b.setVersion(1L);
        log(b, 1, before, ReceivableService.auditSnapshot(b), "2024-02-03T09:00:00");
        before = ReceivableService.auditSnapshot(b);
        b.setPaidAmount(new BigDecimal("80"));
        b.setVersion(2L);
        log(b, 2, before, ReceivableService.auditSnapshot(b), "2024-03-03T09:00:00");
        bills.updateById(b.getId(), b).block(WAIT);
        var r = query("monthly", input());
        assertEquals("100.00", r.summary().get("opening"));
        assertEquals("0.00", r.summary().get("accrual"));
        assertEquals("30.00", r.summary().get("settled"));
        assertEquals("70.00", r.summary().get("debt"));
        var i = input();
        i.setGroupBy("DETAIL");
        assertEquals("70.00", query("arrears", i).summary().get("debt"));
    }

    @Test
    void activityAndChangesEmptySourcesAreRealZeroes() {
        assertEquals("0.00", query("activity", input()).summary().get("principal"));
        assertEquals(0, query("changes", input()).page().getTotal());
    }

    @Test
    void fundsPreserveOpeningTransfersAndClosingWithoutCashDuplication() {
        var a = new FundAccountEntity();
        a.setId(UUID.randomUUID().toString());
        a.setProjectId(project);
        a.setSpaceId("room");
        a.setType("ADVANCE");
        a.setCompanyId("company");
        a.setCompanyName("公司");
        a.setInvoiceOccupiedAmount(BigDecimal.ZERO);
        a.setFullName("测试空间");
        a.setCustomerName("客户");
        a.setCustomerId("customer");
        a.setRelationshipId("relation");
        a.setSubjectId("subject");
        a.setSubjectName("物业费");
        a.setStandardId("standard");
        a.setStandardName("标准");
        a.setBalance(new BigDecimal("90"));
        StandardPersistenceTest.initialize(a);
        accounts.insert(a).block(WAIT);
        String[] dates = {"2024-01-01T09:00:00", "2024-02-03T09:00:00", "2024-02-04T09:00:00"};
        String[] deltas = {"100", "-30", "20"};
        String[] actions = {"TOPUP", "OFFSET", "TRANSFER_IN"};
        BigDecimal balance = BigDecimal.ZERO;
        for (int n = 0; n < 3; n++) {
            var e = new FundEntryEntity();
            e.setId(UUID.randomUUID().toString());
            e.setBillNo("YC" + n);
            e.setProjectId(project);
            e.setSpaceId("room");
            e.setAccountId(a.getId());
            e.setTransactionId(UUID.randomUUID().toString());
            e.setAction(actions[n]);
            e.setCompanyId("company");
            e.setCompanyName("公司");
            e.setFullName("测试空间");
            e.setCustomerName("客户");
            e.setSubjectName("物业费");
            e.setStandardName("标准");
            e.setPayerName("客户");
            e.setMethod("1");
            e.setMethodText("现金");
            e.setInputSnapshotJson("{}");
            e.setOccurredAt(dates[n]);
            e.setReason("测试");
            e.setRequestFingerprint("test");
            e.setAmount(new BigDecimal(deltas[n]));
            e.setCashAmount(n == 0 ? e.getAmount() : BigDecimal.ZERO);
            e.setBeforeBalance(balance);
            balance = balance.add(e.getAmount());
            e.setAfterBalance(balance);
            StandardPersistenceTest.initialize(e);
            e.setCreateTime(1000L + n);
            entries.insert(e).block(WAIT);
        }
        var i = input();
        i.setGroupBy("TYPE");
        var r = query("funds", i);
        assertEquals("100.00", r.summary().get("opening"));
        assertEquals("90.00", r.summary().get("closing"));
        assertEquals("0.00", r.summary().get("income"));
        assertEquals("30.00", r.summary().get("offset"));
        assertEquals("20.00", r.summary().get("transferIn"));
        i.setGroupBy("DETAIL");
        assertEquals(2, query("funds", i).page().getTotal());
        a.setId(UUID.randomUUID().toString());
        accounts.insert(a).block(WAIT);
        assertTrue(
                TaxItemPersistenceTest.status(
                        assertThrows(RuntimeException.class, () -> query("funds", i)), 409));
    }

    @Autowired ReactiveRepository<PaymentEntity, String> payments;
    @Autowired ReactiveRepository<PaymentAllocationEntity, String> paymentLines;
    @Autowired ReactiveRepository<RefundEntity, String> refunds;
    @Autowired ReactiveRepository<RefundAllocationEntity, String> refundLines;
    @Autowired ReactiveRepository<PrepayQuoteEntity, String> quotes;
    @Autowired ReactiveRepository<PrepayConsumptionEntity, String> consumptions;

    ReceivedReportPersistenceTest cashFixture() {
        var f = new ReceivedReportPersistenceTest();
        f.project = project;
        f.payments = payments;
        f.lines = paymentLines;
        f.refunds = refunds;
        f.refundLines = refundLines;
        return f;
    }

    @Test
    void monthlySeparatesOriginalFeeCashAndBalanceOffsetsAndRestoringRefunds() {
        var b = bill("100", "0");
        log(b, 0, "{}", ReceivableService.auditSnapshot(b), "2024-01-02T09:00:00");
        String before = ReceivableService.auditSnapshot(b);
        b.setPaidAmount(new BigDecimal("35"));
        b.setVersion(1L);
        log(b, 1, before, ReceivableService.auditSnapshot(b), "2024-02-06T09:00:00");
        bills.updateById(b.getId(), b).block(WAIT);
        var f = cashFixture();
        var cash = f.payment("30", "1", "room", "2024-02-03T09:00:00");
        var offset = f.payment("10", "ADVANCE", "room", "2024-02-04T09:00:00");
        for (var line : List.of(cash, offset))
            paymentLines
                    .createUpdate()
                    .set("receivableId", b.getId())
                    .set("calcStartDate", "2023-12-01")
                    .set("calcEndDate", "2023-12-31")
                    .where("id", line.getId())
                    .execute()
                    .block(WAIT);
        f.refund(cash, "5", "CASH", "2024-02-05T09:00:00");
        var r = query("monthly", input());
        assertEquals("30.00", r.summary().get("cash"));
        assertEquals("30.00", r.summary().get("priorYears"));
        assertEquals("10.00", r.summary().get("offset"));
        assertEquals("5.00", r.summary().get("restored"));
        assertEquals("65.00", r.summary().get("debt"));
    }

    @Test
    void historicalCustomerUsesCutoffSnapshotAndNonfinancialVersionsDoNotInvalidateIt() {
        var b = bill("100", "0");
        b.setCustomerName("原业主");
        bills.updateById(b.getId(), b).block(WAIT);
        log(b, 0, "{}", ReceivableService.auditSnapshot(b), "2024-01-02T09:00:00");
        b.setCustomerName("新业主");
        b.setVersion(2L);
        bills.updateById(b.getId(), b).block(WAIT);
        var i = input();
        i.setGroupBy("DETAIL");
        assertEquals("原业主", query("monthly", i).page().getData().get(0).get("customerName"));
        b.setPaidAmount(BigDecimal.TEN);
        bills.updateById(b.getId(), b).block(WAIT);
        assertTrue(
                TaxItemPersistenceTest.status(
                        assertThrows(RuntimeException.class, () -> query("monthly", i)), 409));
    }

    @Test
    void confirmedActivityFactsExcludeDraftsAndFilterByStableActivityId() {
        var f = cashFixture();
        var line = f.payment("600", "1", "room", "2024-02-03T09:00:00");
        String snapshot =
                com.alibaba.fastjson.JSON.toJSONString(
                        Map.of(
                                "houseName",
                                "园区/101",
                                "payerName",
                                "客户甲",
                                "payerCustomerId",
                                "customer",
                                "settingJson",
                                "{\"subjectId\":\"subject\",\"subjectName\":\"物业费\"}",
                                "activityJson",
                                "{\"name\":\"充六赠一\"}",
                                "payAmount",
                                "600",
                                "originalAmount",
                                "700",
                                "periods",
                                List.of(
                                        Map.of(
                                                "start",
                                                "2024-02-01",
                                                "end",
                                                "2024-08-31",
                                                "paidAmount",
                                                "600",
                                                "discountAmount",
                                                "100"))));
        for (int n = 0; n < 2; n++) {
            var q = new PrepayQuoteEntity();
            q.setId(UUID.randomUUID().toString());
            q.setProjectId(project);
            q.setSpaceId("room");
            q.setSettingId("setting");
            q.setActivityId("act" + n);
            q.setRequestFingerprint("fp");
            q.setCreatorId("tester");
            q.setStatus(n == 0 ? "CONFIRMED" : "QUOTED");
            q.setExpiresAt(9999999999999L);
            q.setSnapshotJson(snapshot);
            StandardPersistenceTest.initialize(q);
            quotes.insert(q).block(WAIT);
            if (n == 0) {
                var c = new PrepayConsumptionEntity();
                c.setId(UUID.randomUUID().toString());
                c.setProjectId(project);
                c.setSpaceId("room");
                c.setQuoteId(q.getId());
                c.setActivityId("act0");
                c.setPaymentId(line.getPaymentId());
                c.setPeriodsJson("[]");
                StandardPersistenceTest.initialize(c);
                consumptions.insert(c).block(WAIT);
            }
        }
        var i = input();
        i.setGroupBy("ACTIVITY");
        var r = query("activity", i);
        assertEquals(1, r.page().getTotal());
        assertEquals("600.00", r.summary().get("principal"));
        assertEquals("100.00", r.summary().get("discount"));
        i.setGroupBy("PERIOD");
        var periods = query("activity", i);
        assertEquals("600.00", periods.summary().get("principal"));
        assertEquals("100.00", periods.summary().get("discount"));
        assertEquals("2024-02-01", periods.page().getData().get(0).get("calcStartDate"));
        i.setActivityId("act1");
        assertEquals(0, query("activity", i).page().getTotal());
    }

    @Test
    void approvedChangesKeepApplicantReviewerAndDoNotDoubleCountHistory() {
        var b = bill("100", "0");
        log(b, 0, "{}", ReceivableService.auditSnapshot(b), "2024-01-02T09:00:00");
        String before = ReceivableService.auditSnapshot(b);
        b.setActualReceivableAmount(new BigDecimal("120"));
        b.setVersion(1L);
        bills.updateById(b.getId(), b).block(WAIT);
        log(b, 1, before, ReceivableService.auditSnapshot(b), "2024-02-03T09:00:00");
        var approval = new ReceivableLogEntity();
        approval.setId(UUID.randomUUID().toString());
        approval.setProjectId(project);
        approval.setSpaceId(b.getSpaceId());
        approval.setReceivableId(b.getId());
        approval.setOperation("CHANGE_APPROVE");
        approval.setReason("批准");
        approval.setOperatorId("reviewer");
        approval.setOperatorName("复核人");
        approval.setBeforeVersion(0L);
        approval.setAfterVersion(1L);
        approval.setBeforeJson(before);
        approval.setAfterJson(ReceivableService.auditSnapshot(b));
        approval.setCreateTime(
                LocalDateTime.parse("2024-02-03T09:00:01")
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .toInstant()
                        .toEpochMilli());
        logs.insert(approval).block(WAIT);
        var c = new ReceivableChangeEntity();
        c.setId(UUID.randomUUID().toString());
        c.setProjectId(project);
        c.setSpaceId(b.getSpaceId());
        c.setReceivableId(b.getId());
        c.setSourceVersion(0L);
        c.setFullName(b.getFullName());
        c.setCustomerName(b.getCustomerName());
        c.setSubjectName(b.getSubjectName());
        c.setOperation("ADJUST");
        c.setStatus("APPROVED");
        c.setReason("核对应收");
        c.setApplicantId("applicant");
        c.setApplicantName("申请人甲");
        c.setReviewedName("复核人乙");
        c.setReviewedAt(approval.getCreateTime());
        c.setInputJson("{}");
        c.setPreviewJson("{}");
        c.setBeforeJson(before);
        StandardPersistenceTest.initialize(c);
        changes.insert(c).block(WAIT);
        var i = input();
        i.setGroupBy("DETAIL");
        var r = query("changes", i);
        assertEquals(1, r.page().getTotal());
        assertEquals("20.00", r.summary().get("adjustment"));
        assertEquals("申请人甲", r.page().getData().get(0).get("applicant"));
        assertEquals("复核人乙", r.page().getData().get(0).get("reviewer"));
        assertEquals("120.00", query("monthly", i).summary().get("debt"));
    }

    @Autowired ReactiveRepository<ReceivableChangeEntity, String> changes;

    @Configuration
    @Import({
        InvoiceCompanyPersistenceTest.Config.class,
        AnalysisSql.class,
        FinanceAnalysisService.class,
        ReceivableAnalysisSource.class,
        FundAnalysisSource.class,
        ActivityAnalysisSource.class,
        ChangeAnalysisSource.class,
        MonthlySettlementSource.class
    })
    static class Config {}
}
