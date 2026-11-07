package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.impl.entity.DimensionAssetBindingEntity;
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

/** 独立PG验证全范围SQL汇总、真实资产过滤及金额快照，不连接开发库。 */
@SpringBootTest(
        classes = ReceivableReportPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReceivableReportPersistenceTest {
    static final Duration WAIT = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        InvoiceCompanyPersistenceTest.database(registry);
    }

    @Autowired ReceivableReportService reports;
    @Autowired ReactiveRepository<ReceivableEntity, String> bills;
    @Autowired ReactiveRepository<DimensionAssetBindingEntity, String> bindings;
    @Autowired DatabaseOperator database;
    @Autowired ReceivableReportExportService exports;
    @Autowired org.jetlinks.pro.property.finance.web.ReceivableReportController controller;
    String project;
    Context allowed;

    @BeforeEach
    void setup() {
        project = UUID.randomUUID().toString();
        allowed = new HouseSettingPersistenceTest().allowed();
    }

    ReceivableReportInput input() {
        var i = new ReceivableReportInput();
        i.setProjectIds(List.of(project));
        return i;
    }

    ReceivableEntity bill(String net, String paid) {
        var fixture = new CashierPersistenceTest();
        fixture.project = project;
        fixture.bills = bills;
        var b = fixture.bill("report");
        bills.createUpdate()
                .set("actualReceivableAmount", new BigDecimal(net))
                .set("paidAmount", new BigDecimal(paid))
                .set(
                        "arrears",
                        new BigDecimal(net).subtract(new BigDecimal(paid)).max(BigDecimal.ZERO))
                .set("reviewState", "APPROVED")
                .where("id", b.getId())
                .execute()
                .block(WAIT);
        return bills.findById(b.getId()).block(WAIT);
    }

    ReceivableReportResult query(ReceivableReportInput i) {
        return reports.query(i).contextWrite(allowed).block(WAIT);
    }

    @Test
    void drillMatchesExactGroupingSnapshotAndExportUsesSameScope() {
        bill("10", "1");
        var renamed = bill("20", "2");
        bills.createUpdate()
                .set("subjectName", "历史新名")
                .where("id", renamed.getId())
                .execute()
                .block(WAIT);
        var i = input();
        i.setSubjectGroups(List.of(new ReportGroupKey("subject", "历史新名")));
        assertEquals("20.00", query(i).summary().netAmount());
        assertEquals(1, query(i).page().getTotal());
        assertEquals(
                query(i).summary(),
                reports.exportData(i).contextWrite(allowed).block(WAIT).summary());
    }

    @Test
    void overviewUsesExactApprovedScopeAndNonOverlappingAgeBoundaries() {
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        for (int days : List.of(1, 30, 31, 90, 91, 180, 181, 365, 366)) {
            var b = bill("10", "0");
            bills.createUpdate()
                    .set("dueDate", today.minusDays(days).toString())
                    .where("id", b.getId())
                    .execute()
                    .block(WAIT);
        }
        var dueToday = bill("10", "0");
        bills.createUpdate()
                .set("dueDate", today.toString())
                .where("id", dueToday.getId())
                .execute()
                .block(WAIT);
        var pending = bill("999", "0");
        bills.createUpdate()
                .set("reviewState", "PENDING")
                .where("id", pending.getId())
                .execute()
                .block(WAIT);
        var split = bill("999", "0");
        bills.createUpdate().set("split", true).where("id", split.getId()).execute().block(WAIT);
        var result = reports.overview(input()).contextWrite(allowed).block(WAIT);
        assertEquals(query(input()).summary(), result.summary());
        assertEquals("100.00", result.summary().netAmount());
        assertEquals("90.00", result.summary().overdueAmount());
        assertEquals(
                List.of("20.00", "20.00", "20.00", "20.00", "10.00"),
                result.ageBuckets().stream().map(row -> row.amount()).toList());
        assertEquals(1, result.overdueHouseCount());
        assertEquals("100.00", result.subjects().get(0).amounts().netAmount());
        var forbidden = input();
        forbidden.setProjectIds(List.of(project, "forbidden"));
        rejects(reports.overview(forbidden), 403);
    }

    @Test
    void summaryUsesAllFilteredRowsAndIsExactWhilePagingStaysStable() {
        var a = bill("9999999999999999.99", "10.01");
        var b = bill("100.01", "50.00");
        var i = input();
        i.setPageSize(1);
        var first = query(i);
        assertEquals(2, first.page().getTotal());
        assertEquals(1, first.page().getData().size());
        assertEquals("10000000000000100.00", first.summary().netAmount());
        assertEquals("60.01", first.summary().receivedAmount());
        assertEquals("10000000000000039.99", first.summary().arrearsAmount());
        i.setPageIndex(1);
        var second = query(i);
        assertNotEquals(first.page().getData().get(0).id(), second.page().getData().get(0).id());
        assertEquals(first.summary(), second.summary());
        assertEquals(
                Set.of(a.getId(), b.getId()),
                Set.of(first.page().getData().get(0).id(), second.page().getData().get(0).id()));
        assertEquals(a.getVersion(), bills.findById(a.getId()).block(WAIT).getVersion());
    }

    @Test
    void remissionIsNotDeductedTwiceAndOnlyEffectiveLateReductionIsCounted() {
        var b = bill("85.00", "20.00");
        bills.createUpdate()
                .set("feeReductionAmount", new BigDecimal("10"))
                .set("accruedLateFeeAmount", new BigDecimal("5"))
                .set("lateFeeReductionAmount", new BigDecimal("9"))
                .where("id", b.getId())
                .execute()
                .block(WAIT);
        var r = query(input());
        assertEquals("100.00", r.summary().receivableAmount());
        assertEquals("15.00", r.summary().remissionAmount());
        assertEquals("85.00", r.summary().netAmount());
        assertEquals("65.00", r.summary().arrearsAmount());
        assertEquals("23.53", r.summary().collectionRate());
        assertEquals(r.summary(), r.page().getData().get(0).amounts());
    }

    @Test
    void businessDateIsIndependentAndOutstandingIsFlooredPerBill() {
        var b = bill("90", "30");
        bills.createUpdate()
                .set("receivableDate", "2023-12-31")
                .set("dueDate", "2024-02-29")
                .set("arrears", new BigDecimal("999"))
                .set("feeReductionAmount", new BigDecimal("10"))
                .where("id", b.getId())
                .execute()
                .block(WAIT);
        bill("10", "20");
        var all = query(input());
        assertEquals("60.00", all.summary().unreceivedAmount());
        assertEquals("60.00", all.summary().arrearsAmount());
        assertEquals("50.00", all.summary().collectionRate());
        var i = input();
        i.setReceivableDateStart("2023-12-31");
        i.setReceivableDateEnd("2023-12-31");
        var result = query(i);
        assertEquals(1, result.page().getTotal());
        assertEquals("2024-02-29", result.page().getData().get(0).dueDate());
        assertEquals("2023-12-31", result.page().getData().get(0).receivableDate());
        assertEquals("100.00", result.summary().receivableAmount());
        assertEquals("90.00", result.summary().netAmount());
        assertEquals("33.33", result.summary().collectionRate());
        assertEquals(
                result.summary(),
                reports.exportData(i).contextWrite(allowed).block(WAIT).summary());
        i.setReceivableDateStart(null);
        i.setReceivableDateEnd("2023-12-30");
        assertEquals(0, query(i).page().getTotal());
    }

    @Test
    void deletedSplitAndUnauditedBillsNeverEnterAnyReportView() {
        var kept = bill("10", "0");
        for (String state : List.of("PENDING", "REJECTED", "split", "deleted")) {
            var b = bill("500", "0");
            var u = bills.createUpdate().where("id", b.getId());
            if (Set.of("split", "deleted").contains(state)) u.set(state, true);
            else u.set("reviewState", state);
            u.execute().block(WAIT);
        }
        bills.createUpdate()
                .set("frozen", true)
                .set("financialClosed", true)
                .where("id", kept.getId())
                .execute()
                .block(WAIT);
        for (String view : List.of("DETAIL", "PROJECT", "SUBJECT")) {
            var i = input();
            i.setGroupBy(view);
            var r = query(i);
            assertEquals(1, r.page().getTotal());
            assertEquals("10.00", r.summary().netAmount());
        }
    }

    @Test
    void collectionStatusUsesPersistedNetIncludingRefundRestoredArrears() {
        bill("0", "0");
        bill("10", "0");
        bill("10", "4");
        bill("10", "10");
        for (var entry : Map.of("PAID", 2L, "UNPAID", 1L, "PARTIAL", 1L).entrySet()) {
            var i = input();
            i.setCollectionStatus(entry.getKey());
            assertEquals(entry.getValue().intValue(), query(i).page().getTotal());
        }
    }

    @Test
    void datesNamesAndLiteralUserTextAreFilteredBeforeSummary() {
        var b = bill("123.45", "1");
        bills.createUpdate()
                .set("customerName", "O'Reilly %客户")
                .where("id", b.getId())
                .execute()
                .block(WAIT);
        var i = input();
        i.setCustomerName("O'Reilly");
        i.setDueDateStart("2024-02-29");
        i.setDueDateEnd("2024-02-29");
        assertEquals("123.45", query(i).summary().netAmount());
        i.setDueDateStart("2024-03-01");
        i.setDueDateEnd(null);
        assertEquals(0, query(i).page().getTotal());
        i.setDueDateStart(null);
        i.setCustomerName("' OR 1=1 --");
        assertEquals("0.00", query(i).summary().netAmount());
        i.setCustomerName(null);
        i.setReceivableNo(b.getId());
        assertEquals(1, query(i).page().getTotal());
        i.setSpaceIds(List.of("secret"));
        assertEquals(0, query(i).page().getTotal());
    }

    @Test
    void multipleProjectsAndSnapshotSubjectNamesHavePagedAggregations() {
        bill("10", "2");
        var renamed = bill("20", "3");
        bills.createUpdate()
                .set("subjectName", "物业费新名称")
                .where("id", renamed.getId())
                .execute()
                .block(WAIT);
        var firstProject = project;
        project = UUID.randomUUID().toString();
        bill("30", "5");
        var i = input();
        i.setProjectIds(List.of(firstProject, project));
        i.setGroupBy("PROJECT");
        i.setPageSize(1);
        var r = query(i);
        assertEquals(2, r.page().getTotal());
        assertEquals("60.00", r.summary().netAmount());
        assertEquals(1, r.page().getData().size());
        i.setGroupBy("SUBJECT");
        i.setPageSize(12);
        assertEquals(2, query(i).page().getTotal());
        assertEquals(
                Set.of("物业费", "物业费新名称"),
                query(i).page().getData().stream()
                        .map(ReceivableReportRow::subjectName)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void oneDeniedProjectRejectsEntireRequestRatherThanReturningPartialMoney() {
        bill("99", "0");
        var i = input();
        i.setProjectIds(List.of(project, "forbidden"));
        rejects(reports.query(i), 403);
    }

    @Test
    void invalidFiltersFailVisiblyAndNeverWrite() {
        for (String bad : List.of("2024-02-30", "2024-2-01", "bad")) {
            var i = input();
            i.setDueDateStart(bad);
            rejects(reports.query(i), 400);
        }
        var i = input();
        i.setDueDateStart("2024-03-01");
        i.setDueDateEnd("2024-01-01");
        rejects(reports.query(i), 400);
        i = input();
        i.setProjectIds(List.of());
        rejects(reports.query(i), 400);
        i = input();
        i.setGroupBy("sql");
        rejects(reports.query(i), 400);
        i = input();
        i.setCollectionStatus("sql");
        rejects(reports.query(i), 400);
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void actualPlatformDimensionScopeProtectsCountSummaryGroupsAndExport() {
        bill("11", "1");
        var secret = bill("999", "0");
        bills.createUpdate()
                .set("spaceId", "secret")
                .where("id", secret.getId())
                .execute()
                .block(WAIT);
        var authority = new CarryForwardAuthorityTest();
        authority.holders();
        try {
            database.getMetadata()
                    .addFeature(
                            new org.jetlinks.pro.assets.impl.terms.DimensionAssetsTermBuilder());
            String reader = "report-" + UUID.randomUUID();
            authority.rights.put(reader, Set.of("room"));
            var auth = authority.auth(reader);
            var b = new DimensionAssetBindingEntity();
            b.setAssetId("room");
            b.setAssetType("space");
            b.setTargetId(reader);
            b.setTargetType("test");
            b.setPermission(CrudAssetPermission.read.getMask());
            b.setUpdateTime(System.currentTimeMillis());
            b.generateTargetKey();
            b.setId(b.generateId());
            bindings.insert(b).block(WAIT);
            allowed =
                    allowed.put(Authentication.class, auth)
                            .put(AssetsHolder.class, AssetsHolder.fromAuth(auth).block(WAIT));
            for (String view : List.of("DETAIL", "PROJECT", "SUBJECT")) {
                var i = input();
                i.setGroupBy(view);
                var r = query(i);
                assertEquals(1, r.page().getTotal());
                assertEquals("11.00", r.summary().netAmount());
                assertEquals(
                        1,
                        reports.exportData(i)
                                .contextWrite(allowed)
                                .block(WAIT)
                                .page()
                                .getData()
                                .size());
            }
        } finally {
            authority.close();
        }
    }

    @Test
    void exportUsesAllPagesButRejectsOverLimitWithoutTruncation() {
        bill("10", "1");
        bill("20", "2");
        var i = input();
        i.setPageIndex(3);
        i.setPageSize(1);
        var data = reports.exportData(i).contextWrite(allowed).block(WAIT);
        assertEquals(2, data.page().getData().size());
        assertEquals("30.00", data.summary().netAmount());
        var template = bill("1", "0");
        var bulk = new ArrayList<ReceivableEntity>();
        for (int n = 0; n < 9998; n++) {
            var row = new ReceivableEntity();
            org.springframework.beans.BeanUtils.copyProperties(template, row);
            row.setId(UUID.randomUUID().toString());
            row.setBillKey(row.getId());
            bulk.add(row);
        }
        reactor.core.publisher.Flux.fromIterable(bulk)
                .buffer(250)
                .concatMap(bills::insertBatch)
                .then()
                .block(WAIT);
        rejects(reports.exportData(i), 400);
    }

    @Test
    void httpFilesKeepChineseExactMoneyTextAndEscapeHtmlWithoutMutation() throws Exception {
        var b = bill("100.01", "10.01");
        bills.createUpdate()
                .set("customerName", "=SUM(1,2)<script>evil</script>")
                .where("id", b.getId())
                .execute()
                .block(WAIT);
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                controller)
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) -> chain.filter(exchange).contextWrite(allowed))
                        .build();
        var root = "/property/finance/reports/receivables";
        client.post()
                .uri(root + "/_query")
                .bodyValue(input())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.summary.netAmount")
                .isEqualTo("100.01")
                .jsonPath("$.page.data[0].snapshotJson")
                .doesNotExist();
        byte[] bytes =
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
            assertEquals("应收统计", sheet.getSheetName());
            assertEquals("项目", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(
                    org.apache.poi.ss.usermodel.CellType.STRING,
                    sheet.getRow(1).getCell(2).getCellType());
            assertTrue(sheet.getRow(1).getCell(2).getStringCellValue().startsWith("=SUM"));
            int amountColumn = -1;
            for (var cell : sheet.getRow(0))
                if ("净应收（元）".equals(cell.getStringCellValue()))
                    amountColumn = cell.getColumnIndex();
            assertTrue(amountColumn >= 0);
            assertEquals(
                    org.apache.poi.ss.usermodel.CellType.NUMERIC,
                    sheet.getRow(1).getCell(amountColumn).getCellType());
            assertEquals(
                    100.01, sheet.getRow(1).getCell(amountColumn).getNumericCellValue(), 0.00001);
        }
        var pdf = exports.export(input(), "pdf").contextWrite(allowed).block(WAIT);
        try (var document = org.apache.pdfbox.pdmodel.PDDocument.load(pdf)) {
            String text =
                    java.text.Normalizer.normalize(
                            new org.apache.pdfbox.text.PDFTextStripper().getText(document),
                            java.text.Normalizer.Form.NFKC);
            assertTrue(text.contains("应收统计"), text);
            assertTrue(text.contains("100.01"), text);
            assertTrue(text.contains("物业费"), text);
            assertTrue(
                    text.replaceAll("\\s+", "").contains("=SUM(1,2)<script>evil</script>"), text);
        }
        client.post()
                .uri(root + "/_export/html")
                .bodyValue(input())
                .exchange()
                .expectStatus()
                .isBadRequest();
        client.post()
                .uri(root + "/_query")
                .bodyValue(Map.of("projectIds", List.of(project), "groupBy", "sql"))
                .exchange()
                .expectStatus()
                .isBadRequest();
        client.delete().uri(root).exchange().expectStatus().isNotFound();
        assertEquals(b.getVersion(), bills.findById(b.getId()).block(WAIT).getVersion());
    }

    void rejects(Mono<?> action, int code) {
        StepVerifier.create(action.contextWrite(allowed))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, code))
                .verify(WAIT);
    }

    @Test
    void overdueOnlyExcludesDueTodayFuturePaidAndUnknownDates() throws Exception {
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var overdue = bill("100", "20");
        bills.createUpdate()
                .set("dueDate", today.minusDays(3).toString())
                .where("id", overdue.getId())
                .execute()
                .block(WAIT);
        for (var date : List.of(today.toString(), today.plusDays(1).toString())) {
            var b = bill("100", "0");
            bills.createUpdate().set("dueDate", date).where("id", b.getId()).execute().block(WAIT);
        }
        var paid = bill("100", "100");
        bills.createUpdate()
                .set("dueDate", today.minusDays(4).toString())
                .where("id", paid.getId())
                .execute()
                .block(WAIT);
        var i = input();
        i.setOverdueOnly(true);
        var result = query(i);
        assertEquals(1, result.page().getTotal());
        assertEquals("80.00", result.summary().overdueAmount());
        assertEquals(3, result.page().getData().get(0).overdueDays());
        i.setGroupBy("SUBJECT");
        i.setExportMode("BOTH");
        var workbook =
                reports.exportWorkbook(i, Locale.CHINESE, new ReceivableReportFile())
                        .contextWrite(allowed)
                        .block(WAIT);
        var bytes = FinanceReportFile.write(workbook, "excel");
        try (var book =
                new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                        new java.io.ByteArrayInputStream(bytes))) {
            assertEquals(3, book.getNumberOfSheets());
            assertNotNull(book.getSheet("查询条件"));
            assertEquals(1, workbook.sheets().get(1).rows().size());
            assertEquals("80.00", workbook.sheets().get(1).summary().get("overdueAmount"));
        }
    }

    @Configuration
    @EnableEasyormRepository("org.jetlinks.pro.assets.impl.entity")
    @Import({
        InvoiceCompanyPersistenceTest.Config.class,
        ReceivableReportService.class,
        ReceivableReportFile.class,
        ReceivableReportExportService.class,
        org.jetlinks.pro.property.finance.web.ReceivableReportController.class
    })
    static class Config {}
}
