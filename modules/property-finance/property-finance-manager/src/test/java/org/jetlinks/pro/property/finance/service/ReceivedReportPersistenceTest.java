package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.report.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.util.context.Context;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/** 独立PG检验实际现金与原始流水，不使用可变应收余额推算实收。 */
@SpringBootTest(
        classes = ReceivedReportPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReceivedReportPersistenceTest {
    static final Duration WAIT = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        InvoiceCompanyPersistenceTest.database(r);
    }

    @Autowired ReceivedReportService reports;
    @Autowired ReactiveRepository<PaymentEntity, String> payments;
    @Autowired ReactiveRepository<PaymentAllocationEntity, String> lines;
    @Autowired ReactiveRepository<RefundEntity, String> refunds;
    @Autowired ReactiveRepository<RefundAllocationEntity, String> refundLines;
    @Autowired ReactiveRepository<FundEntryEntity, String> entries;
    @Autowired ReactiveRepository<FundAccountEntity, String> accounts;
    @Autowired ReactiveRepository<HandoverEntity, String> handovers;
    @Autowired ReactiveRepository<HandoverSourceEntity, String> handoverSources;
    @Autowired org.hswebframework.ezorm.rdb.operator.DatabaseOperator overviewDatabase;

    @Test
    void overviewCashUsesOwnRefundDateExcludesOffsetsAndKeepsCurrentBalances() {
        var p = payment("100", "1", "room", "2024-02-01T12:00:00");
        refund(p, "20", "CASH", "2024-03-02T12:00:00");
        fund("50", "TOPUP", "ADVANCE", "2024-02-02T12:00:00");
        fund("0", "OFFSET", "ADVANCE", "2024-02-02T13:00:00");
        fund("60", "TOPUP", "DEPOSIT", "2024-02-03T12:00:00");
        var i = input();
        i.setReceivedDateStart("2024-02-01");
        i.setReceivedDateEnd("2024-02-29");
        var feb = reports.overview(i, "DAY").contextWrite(allowed).block(WAIT);
        assertEquals("210.00", feb.summary().incomeAmount());
        assertEquals("0.00", feb.summary().refundAmount());
        assertEquals(3, feb.trend().size());
        assertEquals(query(i).summary().netAmount(), feb.summary().netAmount());
        i.setReceivedDateEnd("2024-03-31");
        var months = reports.overview(i, "MONTH").contextWrite(allowed).block(WAIT);
        assertEquals(
                List.of("2024-02", "2024-03"),
                months.trend().stream().map(row -> row.period()).toList());
        assertEquals("-20.00", months.trend().get(1).netAmount());
        var service =
                new FinanceOverviewService(null, null, null, null, accounts, overviewDatabase);
        var balance = service.balances(List.of(project)).contextWrite(allowed).block(WAIT);
        assertEquals("200.00", balance.advanceAmount());
        assertEquals("100.00", balance.depositAmount());
    }

    HandoverEntity bindHandover(String type, String id, String state, String tenant) {
        var b = new HandoverEntity();
        b.setId(UUID.randomUUID().toString());
        b.setTenantId(tenant);
        b.setProjectId(project);
        b.setRequestFingerprint("fixture");
        b.setStatus(state);
        b.setClosingDate("2024-02-01");
        b.setSubmittedAt("2024-02-01T10:00:00");
        b.setSubmittedBy("fixture");
        b.setSourceCount(1);
        b.setAmount(BigDecimal.TEN);
        b.setCashAmount(BigDecimal.TEN);
        StandardPersistenceTest.initialize(b);
        handovers.insert(b).block(WAIT);
        var s = new HandoverSourceEntity();
        s.setId(UUID.randomUUID().toString());
        s.setTenantId(tenant);
        s.setProjectId(project);
        s.setSourceType(type);
        s.setSourceId(id);
        s.setBatchId(b.getId());
        StandardPersistenceTest.initialize(s);
        handoverSources.insert(s).block(WAIT);
        return b;
    }

    @Test
    void handoverFiltersAllCashSourcesBeforePagingSummaryAndExport() throws Exception {
        var p = payment("10", "1", "room", "2024-02-01T10:00:00");
        var extra = new PaymentAllocationEntity();
        org.springframework.beans.BeanUtils.copyProperties(p, extra);
        extra.setId(UUID.randomUUID().toString());
        lines.insert(extra).block(WAIT);
        var r = refund(p, "2", "CASH", "2024-02-02T10:00:00");
        var advance = fund("30", "TOPUP", "ADVANCE", "2024-02-03T10:00:00");
        var deposit = fund("40", "TOPUP", "DEPOSIT", "2024-02-04T10:00:00");
        bindHandover("PAYMENT", p.getPaymentId(), "PENDING", "");
        bindHandover("REFUND", r.getId(), "APPROVED", "");
        bindHandover("FUND", advance.getId(), "APPROVED", "");
        // 同一业务ID在其他租户的绑定不能污染当前租户的投影。
        bindHandover("FUND", deposit.getId(), "APPROVED", "foreign-tenant");
        var i = input();
        var all = query(i);
        assertEquals(5, all.page().getTotal());
        assertEquals("88.00", all.summary().netAmount());
        assertEquals("40.00", all.summary().unhandedAmount());
        assertEquals("20.00", all.summary().pendingAmount());
        assertEquals("28.00", all.summary().approvedAmount());
        var collectors = input();
        collectors.setGroupBy("COLLECTOR");
        var grouped = query(collectors);
        assertEquals(all.summary(), grouped.summary());
        assertEquals(
                new BigDecimal("88.00"),
                grouped.page().getData().stream()
                        .map(row -> new BigDecimal(row.amounts().netAmount()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        i.setHandoverState("PENDING");
        i.setPageSize(1);
        var first = query(i);
        assertEquals(2, first.page().getTotal());
        assertEquals(1, first.page().getData().size());
        assertEquals("20.00", first.summary().netAmount());
        assertEquals("PENDING", first.page().getData().get(0).handoverState());
        i.setPageIndex(1);
        assertNotEquals(first.page().getData().get(0).id(), query(i).page().getData().get(0).id());
        var exported = reports.exportData(i).contextWrite(allowed).block(WAIT);
        assertEquals(2, exported.page().getData().size());
        assertEquals(first.summary(), exported.summary());
        var bytes = new ReceivedReportFile().write(exported, "excel", Locale.SIMPLIFIED_CHINESE);
        try (var workbook =
                new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                        new java.io.ByteArrayInputStream(bytes))) {
            var text = new org.apache.poi.xssf.extractor.XSSFExcelExtractor(workbook);
            assertTrue(text.getText().contains("交账状态"));
            assertTrue(text.getText().contains("已收未入账"));
        }
        i.setPageIndex(0);
        i.setHandoverState("APPROVED");
        assertEquals(2, query(i).page().getTotal());
        assertEquals("28.00", query(i).summary().netAmount());
        i.setHandoverState("UNHANDED");
        assertEquals(deposit.getId(), query(i).page().getData().get(0).id());
        assertEquals("40.00", query(i).summary().netAmount());
        i.setHandoverState("' OR 1=1 --");
        rejects(i, 400);
    }

    String project;
    Context allowed;

    @BeforeEach
    void setup() {
        project = UUID.randomUUID().toString();
        allowed = new HouseSettingPersistenceTest().allowed();
    }

    ReceivedReportInput input() {
        var i = new ReceivedReportInput();
        i.setProjectIds(List.of(project));
        return i;
    }

    ReceivedReportResult query(ReceivedReportInput i) {
        return reports.query(i).contextWrite(allowed).block(WAIT);
    }

    PaymentAllocationEntity payment(String amount, String method, String room, String date) {
        var p = new PaymentEntity();
        p.setId(UUID.randomUUID().toString());
        p.setProjectId(project);
        p.setCompanyId("company");
        p.setCompanyName("公司");
        p.setRequestFingerprint("fingerprint");
        p.setStatus("SUCCEEDED");
        p.setPayerName("付款人");
        p.setPaidAt(date);
        p.setAmount(new BigDecimal(amount));
        p.setModifierName("收费员甲");
        StandardPersistenceTest.initialize(p);
        payments.insert(p).block(WAIT);
        var l = new PaymentAllocationEntity();
        l.setId(UUID.randomUUID().toString());
        l.setPaymentId(p.getId());
        l.setProjectId(project);
        l.setSpaceId(room);
        l.setCustomerId("customer");
        l.setCustomerName("客户甲");
        l.setFullName("园区/1栋/101");
        l.setSubjectId("subject");
        l.setSubjectName("物业费");
        l.setCalcStartDate("2024-02-01");
        l.setCalcEndDate("2024-02-29");
        l.setSortIndex(0);
        l.setMethod(method);
        l.setMethodText("1".equals(method) ? "现金" : "预收冲抵");
        l.setAmount(new BigDecimal(amount));
        l.setRefundedAmount(BigDecimal.ZERO);
        StandardPersistenceTest.initialize(l);
        lines.insert(l).block(WAIT);
        return l;
    }

    RefundEntity refund(
            PaymentAllocationEntity source, String amount, String destination, String date) {
        var r = new RefundEntity();
        r.setId(UUID.randomUUID().toString());
        r.setProjectId(project);
        r.setDestination(destination);
        r.setCompanyId("company");
        r.setCompanyName("公司");
        r.setRequestFingerprint("refund");
        r.setStatus("SUCCEEDED");
        r.setReceiverName("客户甲");
        r.setRefundedAt(date);
        r.setAmount(new BigDecimal(amount));
        r.setMethod("1");
        r.setMethodText("现金");
        r.setReason("退费");
        StandardPersistenceTest.initialize(r);
        refunds.insert(r).block(WAIT);
        var l = new RefundAllocationEntity();
        l.setId(UUID.randomUUID().toString());
        l.setProjectId(project);
        l.setRefundId(r.getId());
        l.setSourceLineId(source.getId());
        l.setPaymentId(source.getPaymentId());
        l.setSpaceId(source.getSpaceId());
        l.setCustomerName(source.getCustomerName());
        l.setFullName(source.getFullName());
        l.setSubjectName(source.getSubjectName());
        l.setSortIndex(0);
        l.setAmount(new BigDecimal(amount));
        StandardPersistenceTest.initialize(l);
        refundLines.insert(l).block(WAIT);
        return r;
    }

    FundEntryEntity fund(String cash, String action, String type, String date) {
        var a = new FundAccountEntity();
        a.setId(UUID.randomUUID().toString());
        a.setProjectId(project);
        a.setCompanyId("company");
        a.setCompanyName("公司");
        a.setType(type);
        a.setSpaceId("room");
        a.setFullName("房屋现名");
        a.setCustomerId("customer");
        a.setRelationshipId("relation");
        a.setCustomerName("客户甲");
        a.setSubjectId("fund-subject");
        a.setSubjectName("现科目名");
        a.setStandardId("standard");
        a.setStandardName("标准");
        a.setBalance(new BigDecimal("100"));
        a.setInvoiceOccupiedAmount(BigDecimal.ZERO);
        StandardPersistenceTest.initialize(a);
        accounts.insert(a).block(WAIT);
        var e = new FundEntryEntity();
        e.setId(UUID.randomUUID().toString());
        e.setProjectId(project);
        e.setSpaceId("room");
        e.setAccountId(a.getId());
        e.setTransactionId(UUID.randomUUID().toString());
        e.setAction(action);
        e.setCompanyId("company");
        e.setCompanyName("公司");
        e.setFullName("园区/1栋/101");
        e.setCustomerName("客户甲");
        e.setSubjectName("预收物业费");
        e.setStandardName("标准");
        e.setOccurredAt(date);
        e.setPayerName("客户甲");
        e.setMethod("1");
        e.setMethodText("现金");
        e.setReason("资金");
        e.setRequestFingerprint("fund");
        e.setInputSnapshotJson("{}");
        e.setAmount(new BigDecimal(cash));
        e.setCashAmount(new BigDecimal(cash));
        e.setBeforeBalance(BigDecimal.ZERO);
        e.setAfterBalance(new BigDecimal("100"));
        StandardPersistenceTest.initialize(e);
        entries.insert(e).block(WAIT);
        return e;
    }

    @Test
    void drillMatchesExactGroupingSnapshotAndExportUsesSameScope() {
        payment("10", "1", "room", "2024-02-01T12:00:00");
        var renamed = payment("20", "1", "room", "2024-02-01T12:00:00");
        lines.createUpdate()
                .set("methodText", "历史现金名称")
                .where("id", renamed.getId())
                .execute()
                .block(WAIT);
        var i = input();
        i.setMethodGroups(List.of(new ReportGroupKey("1", "历史现金名称")));
        assertEquals("20.00", query(i).summary().netAmount());
        assertEquals(1, query(i).page().getTotal());
        assertEquals(
                query(i).summary(),
                reports.exportData(i).contextWrite(allowed).block(WAIT).summary());
    }

    @Test
    void referencesAndRefundKindsFollowOriginalCashWithoutCountingOffsets() {
        var normal = payment("100", "1", "room", "2024-02-01T12:00:00");
        lines.createUpdate()
                .set("receivableId", "YS-1")
                .where("id", normal.getId())
                .execute()
                .block(WAIT);
        refund(normal, "20", "CASH", "2024-03-02T12:00:00");
        var temporary = payment("10", "1", "room", "2024-02-01T12:00:00");
        lines.createUpdate()
                .set("sourceType", "TEMPORARY")
                .where("id", temporary.getId())
                .execute()
                .block(WAIT);
        refund(temporary, "5", "CASH", "2024-03-02T12:00:00");
        fund("100", "TOPUP", "ADVANCE", "2024-02-01T12:00:00");
        fund("0", "OFFSET", "ADVANCE", "2024-02-02T12:00:00");
        fund("-20", "REFUND", "ADVANCE", "2024-03-02T12:00:00");
        fund("-7", "REFUND", "DEPOSIT", "2024-03-02T12:00:00");
        var result = query(input());
        assertEquals("158.00", result.summary().netAmount());
        var row =
                result.page().getData().stream()
                        .filter(r -> normal.getId().equals(r.id()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("YS-1", row.receivableId());
        assertEquals("RECEIVABLE-" + normal.getPaymentId(), row.sourceNo());
        var i = input();
        i.setPaymentNo(row.sourceNo());
        assertEquals(1, query(i).page().getTotal());
        i = input();
        i.setReceivedDateStart("2024-03-01");
        i.setSourceTypes(List.of("ADVANCE_REFUND", "DEPOSIT_REFUND", "TEMPORARY_REFUND"));
        assertEquals("-32.00", query(i).summary().netAmount());
        assertEquals(
                query(i).summary(),
                reports.exportData(i).contextWrite(allowed).block(WAIT).summary());
    }

    @Test
    void cashSourcesAndRefundDatesDoNotDoubleCountOffsetsOrMutableRefundedAmounts() {
        var p = payment("100.01", "1", "room", "2024-02-29T23:59:59");
        lines.createUpdate()
                .set("refundedAmount", new BigDecimal("30"))
                .where("id", p.getId())
                .execute()
                .block(WAIT);
        payment("500", "ADVANCE", "room", "2024-02-29T12:00:00");
        refund(p, "30", "CASH", "2024-03-01T00:00:00");
        refund(p, "10", "BALANCE", "2024-02-29T12:00:00");
        fund("40", "CREDIT", "ADVANCE", "2024-02-29T12:00:00");
        fund("-5", "REFUND", "DEPOSIT", "2024-03-01T12:00:00");
        fund("0", "OFFSET", "ADVANCE", "2024-02-29T12:00:00");
        var all = query(input());
        assertEquals(4, all.page().getTotal());
        assertEquals("140.01", all.summary().incomeAmount());
        assertEquals("35.00", all.summary().refundAmount());
        assertEquals("105.01", all.summary().netAmount());
        assertNull(all.summary().currentPeriodAmount());
        var feb = input();
        feb.setReceivedDateStart("2024-02-01");
        feb.setReceivedDateEnd("2024-02-29");
        var result = query(feb);
        assertEquals(2, result.page().getTotal());
        assertEquals("140.01", result.summary().netAmount());
        assertEquals("100.01", result.summary().currentPeriodAmount());
        assertEquals("40.00", result.summary().otherPeriodAmount());
        feb.setReceivedDateStart("2024-03-01");
        feb.setReceivedDateEnd("2024-03-01");
        assertEquals("-35.00", query(feb).summary().netAmount());
    }

    @Test
    void fundPeriodSnapshotUsesSavedDatesAndNeverCurrentAccountFields() {
        var e = fund("40", "TOPUP", "ADVANCE", "2024-02-29T12:00:00");
        entries.createUpdate()
                .set("calcStartDate", "2024-02-01")
                .set("calcEndDate", "2024-02-29")
                .where("id", e.getId())
                .execute()
                .block(WAIT);
        var i = input();
        i.setReceivedDateStart("2024-02-01");
        var r = query(i);
        assertEquals("40.00", r.summary().currentPeriodAmount());
        assertEquals("0.00", r.summary().otherPeriodAmount());
        assertEquals("2024-02-01", r.page().getData().get(0).calcStartDate());
    }

    @Test
    void permissionsFilterEverySourceBeforeTotalsGroupingAndPaging() {
        payment("10", "1", "room", "2024-02-01T00:00:00");
        var hidden = payment("999", "1", "secret", "2024-02-01T00:00:00");
        refund(hidden, "90", "CASH", "2024-02-01T00:00:00");
        var i = input();
        i.setPageSize(1);
        var r = query(i);
        assertEquals(1, r.page().getTotal());
        assertEquals("10.00", r.summary().netAmount());
        i.setGroupBy("PROJECT");
        assertEquals("10.00", query(i).page().getData().get(0).amounts().netAmount());
        i.setProjectIds(List.of(project, "forbidden"));
        rejects(i, 403);
    }

    @Test
    void precisionPagingAllGroupingAndHistoricalNamesRemainStable() {
        payment("9999999999999999.99", "1", "room", "2024-02-01T00:00:00");
        var e = fund("0.01", "CREDIT", "ADVANCE", "2024-02-01T00:00:00");
        accounts.createUpdate()
                .set("deleted", true)
                .where("id", e.getAccountId())
                .execute()
                .block(WAIT);
        var i = input();
        i.setPageSize(1);
        var a = query(i);
        i.setPageIndex(1);
        var b = query(i);
        assertNotEquals(a.page().getData().get(0).id(), b.page().getData().get(0).id());
        assertEquals(a.summary(), b.summary());
        assertEquals("10000000000000000.00", a.summary().netAmount());
        for (String group : List.of("PROJECT", "SUBJECT", "METHOD", "SOURCE")) {
            i.setGroupBy(group);
            i.setPageIndex(0);
            i.setPageSize(200);
            var grouped = query(i);
            assertEquals(a.summary(), grouped.summary());
            assertEquals(
                    0,
                    new BigDecimal(a.summary().netAmount())
                            .compareTo(
                                    grouped.page().getData().stream()
                                            .map(x -> new BigDecimal(x.amounts().netAmount()))
                                            .reduce(BigDecimal.ZERO, BigDecimal::add)));
        }
        i.setGroupBy("DETAIL");
        assertTrue(
                query(i).page().getData().stream().anyMatch(x -> "预收物业费".equals(x.subjectName())));
    }

    @Test
    void filtersAreBoundAndInvalidRequestsFailWithoutWriting() {
        var p = payment("10", "1", "room", "2024-02-01T00:00:00");
        var i = input();
        i.setCustomerName("' OR 1=1 --");
        assertEquals(0, query(i).page().getTotal());
        i.setCustomerName("客户");
        i.setPaymentMethods(List.of("2"));
        assertEquals(0, query(i).page().getTotal());
        i.setPaymentMethods(List.of("1"));
        assertEquals(1, query(i).page().getTotal());
        i.setSourceTypes(List.of("ADVANCE"));
        assertEquals(0, query(i).page().getTotal());
        i.setReceivedDateStart("2024-02-30");
        rejects(i, 400);
        i.setReceivedDateStart(null);
        i.setGroupBy("SQL");
        rejects(i, 400);
        assertEquals(p.getVersion(), lines.findById(p.getId()).block(WAIT).getVersion());
    }

    @Autowired org.hswebframework.ezorm.rdb.operator.DatabaseOperator database;

    @Autowired
    ReactiveRepository<org.jetlinks.pro.assets.impl.entity.DimensionAssetBindingEntity, String>
            bindings;

    @Autowired ReceivedReportExportService exports;
    @Autowired org.jetlinks.pro.property.finance.web.ReceivedReportController controller;

    @Test
    void collectorNamesUseScopedDirectoryForSearchPagingAndExportWithoutRewritingHistory()
            throws Exception {
        String operator = "collector-" + UUID.randomUUID();
        var original = payment("100", "1", "room", "2024-02-15T12:00:00");
        var returned = refund(original, "10", "CASH", "2024-02-16T12:00:00");
        var advance = fund("40", "CREDIT", "ADVANCE", "2024-02-17T12:00:00");
        payments.createUpdate()
                .set("modifierId", operator)
                .set("modifierName", "pm_account")
                .where("id", original.getPaymentId())
                .execute()
                .block(WAIT);
        refunds.createUpdate()
                .set("modifierId", operator)
                .set("modifierName", "pm_account")
                .where("id", returned.getId())
                .execute()
                .block(WAIT);
        entries.createUpdate()
                .set("modifierId", operator)
                .set("modifierName", "pm_account")
                .where("id", advance.getId())
                .execute()
                .block(WAIT);
        var hidden = payment("999", "1", "secret", "2024-02-15T12:00:00");
        payments.createUpdate()
                .set("modifierId", "hidden-operator")
                .where("id", hidden.getPaymentId())
                .execute()
                .block(WAIT);

        var providers = org.jetlinks.pro.command.CommandSupportManagerProvider.supports;
        var previous = providers.get("propertyService").orElse(null);
        var support = org.mockito.Mockito.mock(org.jetlinks.core.command.CommandSupport.class);
        var user = new org.jetlinks.pro.property.base.user.PropertyUserInfo();
        user.setId(operator);
        user.setName("超级管理员");
        user.setUsername("pm_account");
        org.mockito.Mockito.when(
                        support.execute(
                                org.mockito.ArgumentMatchers.any(
                                        org.jetlinks.pro.property.base.command
                                                .QueryPropertyUsersCommand.class)))
                .thenAnswer(
                        call -> {
                            org.jetlinks.pro.property.base.command.QueryPropertyUsersCommand
                                    command = call.getArgument(0);
                            assertEquals(List.of(operator), command.getIds(), "不能为无空间权限的流水查询操作人");
                            return reactor.core.publisher.Flux.deferContextual(
                                    context -> {
                                        assertTrue(
                                                context.hasKey(
                                                        org.hswebframework.web.authorization
                                                                .Authentication.class));
                                        assertTrue(
                                                context.hasKey(
                                                        org.jetlinks.pro.assets.AssetsHolder
                                                                .class));
                                        return reactor.core.publisher.Flux.just(user);
                                    });
                        });
        providers.register(
                "propertyService",
                new org.jetlinks.pro.command.CommandSupportManagerProvider() {
                    @Override
                    public String getProvider() {
                        return "propertyService";
                    }

                    @Override
                    public reactor.core.publisher.Mono<
                                    ? extends org.jetlinks.core.command.CommandSupport>
                            getCommandSupport(String module, Map<String, Object> options) {
                        return "directory".equals(module)
                                ? reactor.core.publisher.Mono.just(support)
                                : reactor.core.publisher.Mono.empty();
                    }
                });
        try {
            var input = input();
            input.setCollectorName("超级管理员");
            input.setPageSize(1);
            var page = query(input);
            assertEquals(3, page.page().getTotal());
            assertEquals(1, page.page().getData().size());
            assertEquals("130.00", page.summary().netAmount());
            assertEquals("超级管理员", page.page().getData().get(0).collectorName());
            input.setPageIndex(1);
            assertEquals("超级管理员", query(input).page().getData().get(0).collectorName());
            var exported = reports.exportData(input).contextWrite(allowed).block(WAIT);
            assertEquals(3, exported.page().getData().size());
            assertTrue(
                    exported.page().getData().stream()
                            .allMatch(row -> "超级管理员".equals(row.collectorName())));
            var bytes = exports.export(input, "excel").contextWrite(allowed).block(WAIT);
            try (var book =
                    new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                            new java.io.ByteArrayInputStream(bytes))) {
                var sheet = book.getSheetAt(0);
                var headers = new ArrayList<String>();
                sheet.getRow(0).forEach(cell -> headers.add(cell.getStringCellValue()));
                assertFalse(headers.contains("收款/退款ID"));
                assertTrue(headers.contains("收款/退款编号"));
                assertTrue(headers.contains("关联应收单号"));
                int collector = headers.indexOf("收款员");
                assertTrue(collector >= 0);
                assertEquals("超级管理员", sheet.getRow(1).getCell(collector).getStringCellValue());
            }
            assertEquals(
                    "pm_account",
                    payments.findById(original.getPaymentId()).block(WAIT).getModifierName());
            assertEquals(
                    "pm_account", refunds.findById(returned.getId()).block(WAIT).getModifierName());
            assertEquals(
                    "pm_account", entries.findById(advance.getId()).block(WAIT).getModifierName());

            // 没有姓名时使用账号；目录中已不存在或不可读时仍能解析历史快照。
            user.setName(" ");
            input.setCollectorName("pm_account");
            assertEquals(3, query(input).page().getTotal());
            assertEquals("pm_account", query(input).page().getData().get(0).collectorName());
            org.mockito.Mockito.when(
                            support.execute(
                                    org.mockito.ArgumentMatchers.any(
                                            org.jetlinks.pro.property.base.command
                                                    .QueryPropertyUsersCommand.class)))
                    .thenReturn(reactor.core.publisher.Flux.empty());
            assertEquals("pm_account", query(input).page().getData().get(0).collectorName());
            input.setCollectorName("超级管理员");
            assertEquals(0, query(input).page().getTotal());
        } finally {
            providers.unregister("propertyService");
            if (previous != null) providers.register("propertyService", previous);
        }
    }

    @Test
    void receivedLedgerUsesPathProjectAndKeepsPositiveReceiptsAndNegativeRefunds() {
        var original = payment("100.01", "1", "room", "2024-02-15T12:00:00");
        refund(original, "10.01", "CASH", "2024-02-16T12:00:00");
        var ledger =
                new org.jetlinks.pro.property.finance.web.ReceivedLedgerController(
                        reports, exports);
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(ledger)
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) -> chain.filter(exchange).contextWrite(allowed))
                        .build();
        var forged = input();
        forged.setProjectIds(List.of("foreign-project"));
        forged.setGroupBy("PROJECT");
        client.post()
                .uri("/property/finance/projects/" + project + "/received/_query")
                .bodyValue(forged)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.groupBy")
                .isEqualTo("DETAIL")
                .jsonPath("$.page.total")
                .isEqualTo(2)
                .jsonPath("$.summary.netAmount")
                .isEqualTo("90.00");
    }

    @Test
    void actualDimensionAssetsProtectsAllSourceTotalsAndExports() {
        var visible = payment("20", "1", "room", "2024-02-01T00:00:00");
        var hidden = payment("999", "1", "secret", "2024-02-01T00:00:00");
        refund(visible, "3", "CASH", "2024-02-02T00:00:00");
        refund(hidden, "90", "CASH", "2024-02-02T00:00:00");
        fund("5", "CREDIT", "ADVANCE", "2024-02-02T00:00:00");
        var authority = new CarryForwardAuthorityTest();
        authority.holders();
        try {
            database.getMetadata()
                    .addFeature(
                            new org.jetlinks.pro.assets.impl.terms.DimensionAssetsTermBuilder());
            String reader = "cash-report-" + UUID.randomUUID();
            authority.rights.put(reader, Set.of("room"));
            var auth = authority.auth(reader);
            var binding = new org.jetlinks.pro.assets.impl.entity.DimensionAssetBindingEntity();
            binding.setAssetId("room");
            binding.setAssetType("space");
            binding.setTargetId(reader);
            binding.setTargetType("test");
            binding.setPermission(org.jetlinks.pro.assets.crud.CrudAssetPermission.read.getMask());
            binding.setUpdateTime(System.currentTimeMillis());
            binding.generateTargetKey();
            binding.setId(binding.generateId());
            bindings.insert(binding).block(WAIT);
            allowed =
                    allowed.put(org.hswebframework.web.authorization.Authentication.class, auth)
                            .put(
                                    org.jetlinks.pro.assets.AssetsHolder.class,
                                    org.jetlinks.pro.assets.AssetsHolder.fromAuth(auth)
                                            .block(WAIT));
            for (String group : List.of("DETAIL", "PROJECT", "SUBJECT", "METHOD", "SOURCE")) {
                var i = input();
                i.setGroupBy(group);
                var r = query(i);
                assertEquals("22.00", r.summary().netAmount());
                var exported = reports.exportData(i).contextWrite(allowed).block(WAIT);
                assertEquals(r.summary(), exported.summary());
                assertEquals(
                        0,
                        exported.page().getData().stream()
                                .filter(row -> "secret".equals(row.spaceId()))
                                .count());
            }
        } finally {
            authority.close();
        }
    }

    @Test
    void unsuccessfulDeletedAndDifferentProjectsNeverContaminateCash() {
        var kept = payment("10", "1", "room", "2024-02-01T00:00:00");
        for (String state : List.of("PENDING", "FAILED", "deleted")) {
            var l = payment("999", "1", "room", "2024-02-01T00:00:00");
            var update = payments.createUpdate().where("id", l.getPaymentId());
            if (state.equals("deleted")) update.set("deleted", true);
            else update.set("status", state);
            update.execute().block(WAIT);
        }
        var badRefund = refund(kept, "5", "CASH", "2024-02-01T00:00:00");
        refunds.createUpdate()
                .set("status", "FAILED")
                .where("id", badRefund.getId())
                .execute()
                .block(WAIT);
        var first = project;
        project = UUID.randomUUID().toString();
        payment("20", "1", "room", "2024-02-01T00:00:00");
        var i = input();
        assertEquals("20.00", query(i).summary().netAmount());
        i.setProjectIds(List.of(first, project));
        i.setGroupBy("PROJECT");
        assertEquals(2, query(i).page().getTotal());
        assertEquals("30.00", query(i).summary().netAmount());
    }

    @Test
    void realHttpExcelAndPdfUseTheSameSafeRows() throws Exception {
        var p = payment("100.01", "1", "room", "2024-02-29T23:59:59");
        lines.createUpdate()
                .set("customerName", "=SUM(1,2)<script>evil</script>")
                .where("id", p.getId())
                .execute()
                .block(WAIT);
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                controller)
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) -> chain.filter(exchange).contextWrite(allowed))
                        .build();
        String root = "/property/finance/reports/received";
        client.post()
                .uri(root + "/_query")
                .bodyValue(input())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.summary.netAmount")
                .isEqualTo("100.01")
                .jsonPath("$.page.total")
                .isEqualTo(1);
        var bytes =
                client.post()
                        .uri(root + "/_export/excel")
                        .bodyValue(input())
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectHeader()
                        .contentType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        .expectBody(byte[].class)
                        .returnResult()
                        .getResponseBody();
        try (var book =
                new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                        new java.io.ByteArrayInputStream(bytes))) {
            var sheet = book.getSheetAt(0);
            assertEquals("实收统计", sheet.getSheetName());
            assertEquals("项目", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(
                    org.apache.poi.ss.usermodel.CellType.STRING,
                    sheet.getRow(1).getCell(2).getCellType());
            assertTrue(sheet.getRow(1).getCell(2).getStringCellValue().startsWith("=SUM"));
            int amountColumn = -1;
            for (var cell : sheet.getRow(0))
                if ("实收总额（元）".equals(cell.getStringCellValue()))
                    amountColumn = cell.getColumnIndex();
            assertTrue(amountColumn >= 0);
            assertEquals(
                    org.apache.poi.ss.usermodel.CellType.NUMERIC,
                    sheet.getRow(1).getCell(amountColumn).getCellType());
            assertEquals(
                    100.01, sheet.getRow(1).getCell(amountColumn).getNumericCellValue(), 0.00001);
        }
        var pdf = exports.export(input(), "pdf").contextWrite(allowed).block(WAIT);
        try (var doc = org.apache.pdfbox.pdmodel.PDDocument.load(pdf)) {
            var text =
                    java.text.Normalizer.normalize(
                            new org.apache.pdfbox.text.PDFTextStripper().getText(doc),
                            java.text.Normalizer.Form.NFKC);
            assertTrue(text.contains("实收统计"), text);
            assertTrue(text.contains("100.01"), text);
            assertTrue(
                    text.replaceAll("\\s+", "").contains("=SUM(1,2)<script>evil</script>"), text);
        }
        client.post()
                .uri(root + "/_export/html")
                .bodyValue(input())
                .exchange()
                .expectStatus()
                .isBadRequest();
        client.delete().uri(root).exchange().expectStatus().isNotFound();
    }

    @Test
    void exportReadsAllPagesAndRejectsOver10000RatherThanTruncating() {
        var p = payment("1", "1", "room", "2024-02-01T00:00:00");
        var batch = new ArrayList<PaymentAllocationEntity>();
        for (int n = 0; n < 10000; n++) {
            var l = new PaymentAllocationEntity();
            org.springframework.beans.BeanUtils.copyProperties(p, l);
            l.setId(UUID.randomUUID().toString());
            batch.add(l);
        }
        reactor.core.publisher.Flux.fromIterable(batch)
                .buffer(500)
                .concatMap(rows -> lines.insertBatch(reactor.core.publisher.Mono.just(rows)))
                .then()
                .block(WAIT);
        assertEquals(10001, query(input()).page().getTotal());
        reactor.test.StepVerifier.create(reports.exportData(input()).contextWrite(allowed))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 400))
                .verify(WAIT);
        var grouped = input();
        grouped.setGroupBy("PROJECT");
        var result = reports.exportData(grouped).contextWrite(allowed).block(WAIT);
        assertEquals(1, result.page().getData().size());
        assertEquals("10001.00", result.summary().netAmount());
    }

    void rejects(ReceivedReportInput i, int code) {
        reactor.test.StepVerifier.create(reports.query(i).contextWrite(allowed))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, code))
                .verify(WAIT);
    }

    @Configuration
    @org.hswebframework.web.crud.annotation.EnableEasyormRepository(
            "org.jetlinks.pro.assets.impl.entity")
    @Import({
        InvoiceCompanyPersistenceTest.Config.class,
        HandoverStateQuery.class,
        ReceivedReportService.class,
        ReceivedReportFile.class,
        ReceivedReportExportService.class,
        org.jetlinks.pro.property.finance.web.ReceivedReportController.class
    })
    static class Config {}
}
