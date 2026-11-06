package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.fastjson.JSON;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.property.base.archive.PropertyHousePage;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.core.publisher.*;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.*;

/** 真实PostgreSQL验证整文件原子性、四类财务发布、版本冲突、编辑与批量事务。 */
@SpringBootTest(
        classes = CollectionImportPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CollectionImportPersistenceTest {
    @BeforeAll
    static void messages() {
        var source = new org.springframework.context.support.ResourceBundleMessageSource();
        source.setBasename("i18n/property-finance-manager/messages");
        source.setDefaultEncoding("UTF-8");
        source.setDefaultLocale(Locale.CHINESE);
        org.hswebframework.web.i18n.MessageSourceInitializer.init(source);
    }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        HouseSettingPersistenceTest.database(r);
    }

    @Autowired CollectionImportService service;
    @Autowired CollectionImportSources sources;
    @Autowired FundsService funds;
    @Autowired CashierConfiguration configuration;
    @Autowired HouseArchiveSource archive;
    @Autowired ReactiveRepository<StandardEntity, String> standards;
    @Autowired ReactiveRepository<ProjectSubjectEntity, String> issued;
    @Autowired ReactiveRepository<SubjectEntity, String> subjects;
    @Autowired ReactiveRepository<ReceivableEntity, String> bills;
    @Autowired ReactiveRepository<PaymentEntity, String> payments;
    @Autowired ReactiveRepository<CollectionImportEntity, String> records;
    @Autowired ReactiveRepository<CollectionImportLineEntity, String> drafts;
    @Autowired ReactiveRepository<FundAccountEntity, String> accounts;
    @Autowired ReactiveRepository<FundEntryEntity, String> entries;
    @Autowired FinancialPeriodTestSupport periods;
    FundsPersistenceTest data;
    String project;

    @BeforeEach
    void setup() {
        data = new FundsPersistenceTest();
        data.service = funds;
        data.configuration = configuration;
        data.archive = archive;
        data.standards = standards;
        data.issued = issued;
        data.subjects = subjects;
        data.bills = bills;
        data.setup();
        project = data.project;
        when(configuration.collectionOptions(anyString()))
                .thenReturn(
                        Mono.just(
                                List.of(
                                        new CashierConfiguration.CompanyOption(
                                                "company",
                                                "测试物业公司",
                                                List.of(
                                                        new CashierConfiguration.FundingOption(
                                                                "1", "1", "现金", null, null))))));
        when(archive.houses(eq(project), any(), any(), anyInt(), anyInt()))
                .thenReturn(
                        Mono.just(
                                new PropertyHousePage(
                                        1, 0, 200, List.of(data.setting.context("room").house()))));
    }

    CollectionImportInput input(String source) {
        var i = new CollectionImportInput();
        i.setExternalNo(source);
        i.setFullName("园区/1栋/101");
        i.setCustomerName("龚超");
        i.setSubjectName("物业费");
        i.setStandardName("面积月费");
        i.setCompanyName("测试物业公司");
        i.setMethodText("现金");
        i.setPaidAt("2024-02-15T12:00:00");
        i.setPayerName("脱敏缴款人");
        i.setAmount(new BigDecimal("20.01"));
        i.setStartDate("2024-02-01");
        i.setEndDate("2024-02-29");
        i.setAccountPeriod("2024-02");
        return i;
    }

    FilePart file(CollectionImportInput... inputs) throws Exception {
        try (var book = new XSSFWorkbook();
                var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("data");
            var head = sheet.createRow(0);
            for (int c = 0; c < CollectionImportWorkbook.HEADERS.size(); c++)
                head.createCell(c).setCellValue(CollectionImportWorkbook.HEADERS.get(c));
            for (int r = 0; r < inputs.length; r++) {
                var row = sheet.createRow(r + 1);
                var wrap = new BeanWrapperImpl(inputs[r]);
                for (int c = 0; c < CollectionImportWorkbook.FIELDS.size(); c++) {
                    var value = wrap.getPropertyValue(CollectionImportWorkbook.FIELDS.get(c));
                    if (value != null) row.createCell(c).setCellValue(value.toString());
                }
            }
            book.write(out);
            var bytes = out.toByteArray();
            var file = mock(FilePart.class);
            when(file.filename()).thenReturn("test.xlsx");
            when(file.content())
                    .thenAnswer(
                            ignored ->
                                    Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes)));
            return file;
        }
    }

    CollectionImportEntity upload(String kind, CollectionImportInput... inputs) throws Exception {
        return service.importFile(project, kind, file(inputs))
                .contextWrite(data.fixture.allowed())
                .block(WAIT);
    }

    List<CollectionImportLineEntity> rows(String batch) {
        return drafts.createQuery().where("importId", batch).fetch().collectList().block(WAIT);
    }

    CollectionImportEntity decide(
            String kind,
            CollectionImportEntity record,
            List<CollectionImportService.Item> items,
            boolean approve) {
        return service.decide(
                        project,
                        kind,
                        record.getId(),
                        new CollectionImportService.Decision(record.getVersion(), "核对原凭证", items),
                        approve)
                .contextWrite(data.fixture.allowed())
                .block(WAIT);
    }

    @Test
    void invalidLastRowKeepsFailurePhysicalRowAndNoDrafts() throws Exception {
        var bad = input("last");
        bad.setAmount(new BigDecimal("1.001"));
        var batch = upload("ADVANCE", input("first"), bad);
        assertEquals("FAILED", batch.getStatus());
        assertTrue(batch.getErrorMessage().contains("3"));
        assertTrue(batch.getErrorMessage().contains("收款金额"));
        assertFalse(batch.getErrorMessage().contains("error.property_finance_"));
        assertFalse(batch.getErrorMessage().contains("[3,"));
        assertEquals(0, rows(batch.getId()).size());
        assertEquals(0, entries.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void invalidLastReferenceDoesNotStageAnyValidFirstRow() throws Exception {
        var bad = input("bad-reference");
        bad.setCustomerName("不属于房屋的客户");
        var batch = upload("ADVANCE", input("valid"), bad);
        assertEquals("FAILED", batch.getStatus());
        assertEquals(2, batch.getTotalCount());
        assertTrue(batch.getErrorMessage().contains("客户名称"));
        assertEquals(0, rows(batch.getId()).size());
    }

    @Test
    void receivedPublishesLinkedSettlementAndReplayCannotChargeAgain() throws Exception {
        var bill = data.fixture.bill("import");
        var i = input("received-001");
        i.setReceivableId(bill.getId());
        i.setAmount(new BigDecimal("100.00"));
        var batch = upload("RECEIVED", i);
        assertEquals("PENDING", batch.getStatus());
        assertEquals(
                new BigDecimal("100.00"), bills.findById(bill.getId()).block(WAIT).getArrears());
        var approved = decide("RECEIVED", batch, List.of(), true);
        assertEquals("APPROVED", approved.getStatus());
        assertEquals(new BigDecimal("0.00"), bills.findById(bill.getId()).block(WAIT).getArrears());
        assertEquals(
                new BigDecimal("100.00"), bills.findById(bill.getId()).block(WAIT).getPaidAmount());
        data.fixture.rejects(
                service.decide(
                        project,
                        "RECEIVED",
                        batch.getId(),
                        new CollectionImportService.Decision(batch.getVersion(), "重复", List.of()),
                        true),
                409);
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals("FAILED", upload("RECEIVED", i).getStatus());
    }

    @Test
    void advanceDepositAndTemporaryUseOfficialLedgers() throws Exception {
        for (var kind : List.of("ADVANCE", "DEPOSIT", "TEMPORARY")) {
            var i = input(kind + "-001");
            if (kind.equals("DEPOSIT")) i.setStandardName(null);
            var batch = upload(kind, i);
            assertEquals("PENDING", batch.getStatus(), batch.getErrorMessage());
            assertEquals("APPROVED", decide(kind, batch, List.of(), true).getStatus());
            assertNotNull(rows(batch.getId()).get(0).getResultId());
        }
        assertEquals(2, entries.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(2, accounts.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                1,
                payments.createQuery()
                        .where("projectId", project)
                        .and("sourceType", "TEMPORARY")
                        .count()
                        .block(WAIT));
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
        accounts.createQuery()
                .where("projectId", project)
                .fetch()
                .doOnNext(a -> assertEquals(new BigDecimal("20.01"), a.getBalance()))
                .then()
                .block(WAIT);
    }

    @Test
    void pendingEditRevalidatesThenMixedLineDecisionsUseRevisedAmount() throws Exception {
        var batch = upload("ADVANCE", input("edit-001"), input("reject-002"));
        var row = rows(batch.getId()).get(0);
        var i = JSON.parseObject(row.getPayloadJson(), CollectionImportInput.class);
        i.setAmount(new BigDecimal("1.001"));
        data.fixture.rejects(
                service.edit(
                        project,
                        "ADVANCE",
                        batch.getId(),
                        row.getId(),
                        new CollectionImportService.Edit(batch.getVersion(), row.getVersion(), i)),
                400);
        assertEquals(0L, drafts.findById(row.getId()).block(WAIT).getVersion());
        i.setAmount(new BigDecimal("35.00"));
        var edited =
                service.edit(
                                project,
                                "ADVANCE",
                                batch.getId(),
                                row.getId(),
                                new CollectionImportService.Edit(
                                        batch.getVersion(), row.getVersion(), i))
                        .contextWrite(data.fixture.allowed())
                        .block(WAIT);
        assertEquals(1L, edited.line().version());
        var partial =
                decide(
                        "ADVANCE",
                        edited.record(),
                        List.of(
                                new CollectionImportService.Item(
                                        row.getId(), edited.line().version())),
                        true);
        assertEquals("PENDING", partial.getStatus());
        var done = decide("ADVANCE", partial, List.of(), false);
        assertEquals("COMPLETED", done.getStatus());
        assertEquals(1, done.getSuccessCount());
        assertEquals(1, done.getRejectedCount());
        assertEquals(
                new BigDecimal("35.00"),
                accounts.createQuery()
                        .where("projectId", project)
                        .fetchOne()
                        .block(WAIT)
                        .getBalance());
    }

    @Test
    void multipleBatchesRollbackOnAnyVersionConflict() throws Exception {
        var a = upload("ADVANCE", input("bulk-a"));
        var b = upload("ADVANCE", input("bulk-b"));
        data.fixture.rejects(
                service.decideMany(
                        project,
                        "ADVANCE",
                        new CollectionImportService.BulkDecision(
                                List.of(
                                        new CollectionImportService.Item(a.getId(), a.getVersion()),
                                        new CollectionImportService.Item(b.getId(), 99L)),
                                "批量核对"),
                        true),
                409);
        assertEquals(0, entries.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals("PENDING", records.findById(a.getId()).block(WAIT).getStatus());
        var approved =
                service.decideMany(
                                project,
                                "ADVANCE",
                                new CollectionImportService.BulkDecision(
                                        List.of(
                                                new CollectionImportService.Item(
                                                        a.getId(), a.getVersion()),
                                                new CollectionImportService.Item(
                                                        b.getId(), b.getVersion())),
                                        "批量核对"),
                                true)
                        .contextWrite(data.fixture.allowed())
                        .block(WAIT);
        assertEquals(2, approved.size());
        assertEquals(2, entries.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void closedPeriodBlocksStagingAndApprovalWithoutAnyMoney() throws Exception {
        var batch = upload("ADVANCE", input("period-a"));
        periods.close(project, java.time.LocalDate.parse("2024-02-29"))
                .contextWrite(data.fixture.allowed())
                .block(WAIT);
        data.fixture.rejects(
                service.decide(
                        project,
                        "ADVANCE",
                        batch.getId(),
                        new CollectionImportService.Decision(batch.getVersion(), "审核", List.of()),
                        true),
                409);
        assertEquals(0, entries.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals("PENDING", records.findById(batch.getId()).block(WAIT).getStatus());
        var failed = upload("ADVANCE", input("period-b"));
        assertEquals("FAILED", failed.getStatus());
        assertTrue(failed.getErrorMessage().contains("第2行"), failed.getErrorMessage());
        assertFalse(
                failed.getErrorMessage().contains("error.property_finance_"),
                failed.getErrorMessage());
        assertFalse(failed.getErrorMessage().contains("[2,"), failed.getErrorMessage());
    }

    @Test
    void lineQueryAppliesSpaceScopeAndForeignBatchCannotBeRead() throws Exception {
        var batch = upload("ADVANCE", input("scope"));
        var q = new QueryParamEntity();
        q.toQuery().and("projectId", "foreign");
        assertEquals(
                0,
                service.lines(project, "ADVANCE", batch.getId(), q)
                        .contextWrite(data.fixture.allowed())
                        .block(WAIT)
                        .getTotal());
        data.fixture.rejects(
                service.lines("foreign-project", "ADVANCE", batch.getId(), new QueryParamEntity()),
                404);
        var denied = mock(AssetsHolder.class);
        when(denied.assertPermission(anyString(), anyCollection(), any()))
                .thenReturn(Mono.error(FinanceScope.error("collection_not_found", 403)));
        var auth = data.fixture.allowed().put(AssetsHolder.class, denied);
        assertThrows(
                RuntimeException.class,
                () ->
                        service.lines(project, "ADVANCE", batch.getId(), new QueryParamEntity())
                                .contextWrite(auth)
                                .block(WAIT));
    }

    @Test
    void depositTemplateIncludesIssuedSubjectWithoutAnyStandard() throws Exception {
        standards
                .createUpdate()
                .set("deleted", true)
                .where("id", data.setting.standardId)
                .execute()
                .block(WAIT);
        var refs = sources.references(project).contextWrite(data.fixture.allowed()).block(WAIT);
        assertTrue(refs.get("科目标准参考").contains(List.of("物业费", "")));
        var input = input("deposit-without-standard");
        input.setStandardName(null);
        var batch = upload("DEPOSIT", input);
        assertEquals("PENDING", batch.getStatus(), batch.getErrorMessage());
        assertEquals("APPROVED", decide("DEPOSIT", batch, List.of(), true).getStatus());
        assertEquals(
                new BigDecimal("20.01"),
                accounts.createQuery()
                        .where("projectId", project)
                        .fetchOne()
                        .block(WAIT)
                        .getBalance());
    }

    @Test
    void httpUploadQueryEditAndApprovePublishTheRevisedCashAmount() throws Exception {
        var bill = data.fixture.bill("http");
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web
                                        .CollectionImportController(service))
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange).contextWrite(data.fixture.allowed()))
                        .build();
        var path = "/property/finance/projects/" + project + "/collection-imports/RECEIVED";
        client.get()
                .uri(path + "/template.xlsx")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        var input = input("http-source");
        input.setReceivableId(bill.getId());
        input.setAmount(new BigDecimal("100.00"));
        var buffer =
                org.springframework.core.io.buffer.DataBufferUtils.join(file(input).content())
                        .block(WAIT);
        var bytes = new byte[buffer.readableByteCount()];
        try {
            buffer.read(bytes);
        } finally {
            org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
        }
        var body = new org.springframework.http.client.MultipartBodyBuilder();
        body.part("file", new org.springframework.core.io.ByteArrayResource(bytes))
                .filename("receipts.xlsx");
        var batch =
                client.post()
                        .uri(path)
                        .body(
                                org.springframework.web.reactive.function.BodyInserters
                                        .fromMultipartData(body.build()))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(CollectionImportEntity.class)
                        .returnResult()
                        .getResponseBody();
        assertEquals("PENDING", batch.getStatus(), batch.getErrorMessage());
        client.post()
                .uri(path + "/_query")
                .bodyValue(Map.of())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data[0].id")
                .isEqualTo(batch.getId());
        client.post()
                .uri(path + "/" + batch.getId() + "/lines/_query")
                .bodyValue(Map.of())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data[0].payload.customerName")
                .isEqualTo("龚超")
                .jsonPath("$.data[0].lineNumber")
                .isEqualTo(2);
        var row = rows(batch.getId()).get(0);
        var payload = JSON.parseObject(row.getPayloadJson());
        payload.put("amount", "80.00");
        payload.put("reductionAmount", "20.00");
        payload.put("reductionReason", "原始凭证减免");
        payload.put("paidAt", "2024-02-15 10:30");
        var edited =
                client.put()
                        .uri(path + "/" + batch.getId() + "/lines/" + row.getId())
                        .bodyValue(
                                Map.of(
                                        "version",
                                        batch.getVersion(),
                                        "lineVersion",
                                        row.getVersion(),
                                        "payload",
                                        payload))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(CollectionImportService.EditResult.class)
                        .returnResult()
                        .getResponseBody();
        assertEquals(1L, edited.line().version());
        client.post()
                .uri(path + "/" + batch.getId() + "/_approve")
                .bodyValue(
                        Map.of(
                                "version",
                                edited.record().getVersion(),
                                "reason",
                                "核对原始凭证",
                                "items",
                                List.of(
                                        Map.of(
                                                "id",
                                                row.getId(),
                                                "version",
                                                edited.line().version()))))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("APPROVED");
        assertEquals(
                new BigDecimal("80.00"), bills.findById(bill.getId()).block(WAIT).getPaidAmount());
        assertEquals(new BigDecimal("0.00"), bills.findById(bill.getId()).block(WAIT).getArrears());
        assertEquals(1, payments.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void receivedReductionPublishesCashAndDiscountThroughCashierTogether() throws Exception {
        var bill = data.fixture.bill("reduction");
        var input = input("received-reduction");
        input.setReceivableId(bill.getId());
        input.setAmount(new BigDecimal("80.00"));
        input.setReductionAmount(new BigDecimal("20.00"));
        input.setReductionReason("原凭证优惠");
        var batch = upload("RECEIVED", input);
        assertEquals("PENDING", batch.getStatus(), batch.getErrorMessage());
        assertEquals("APPROVED", decide("RECEIVED", batch, List.of(), true).getStatus());
        var saved = bills.findById(bill.getId()).block(WAIT);
        assertEquals(new BigDecimal("80.00"), saved.getPaidAmount());
        assertEquals(new BigDecimal("20.00"), saved.getFeeReductionAmount());
        assertEquals(new BigDecimal("0.00"), saved.getArrears());
        assertEquals(
                new BigDecimal("80.00"),
                payments.createQuery()
                        .where("projectId", project)
                        .fetchOne()
                        .block(WAIT)
                        .getAmount());
    }

    @Test
    void concurrentApprovalsCreateOnlyOneFundEntry() throws Exception {
        var batch = upload("ADVANCE", input("concurrent-source"));
        var request = new CollectionImportService.Decision(batch.getVersion(), "并发核对", List.of());
        var results =
                Flux.merge(
                                service.decide(project, "ADVANCE", batch.getId(), request, true)
                                        .map(ignored -> "APPROVED")
                                        .onErrorResume(
                                                e ->
                                                        TaxItemPersistenceTest.status(e, 409)
                                                                ? Mono.just("CONFLICT")
                                                                : Mono.error(e)),
                                service.decide(project, "ADVANCE", batch.getId(), request, true)
                                        .map(ignored -> "APPROVED")
                                        .onErrorResume(
                                                e ->
                                                        TaxItemPersistenceTest.status(e, 409)
                                                                ? Mono.just("CONFLICT")
                                                                : Mono.error(e)))
                        .collectList()
                        .contextWrite(data.fixture.allowed())
                        .block(WAIT);
        assertEquals(1, results.stream().filter("APPROVED"::equals).count());
        assertEquals(1, results.stream().filter("CONFLICT"::equals).count());
        assertEquals(1, entries.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(
                new BigDecimal("20.01"),
                accounts.createQuery()
                        .where("projectId", project)
                        .fetchOne()
                        .block(WAIT)
                        .getBalance());
    }

    @Test
    void editingClosedOriginalDateCannotMoveReceiptToOpenPeriod() throws Exception {
        var batch = upload("ADVANCE", input("closed-original"));
        var row = rows(batch.getId()).get(0);
        var revised = JSON.parseObject(row.getPayloadJson(), CollectionImportInput.class);
        revised.setPaidAt("2024-03-15 10:30");
        periods.close(project, java.time.LocalDate.parse("2024-02-29"))
                .contextWrite(data.fixture.allowed())
                .block(WAIT);
        data.fixture.rejects(
                service.edit(
                        project,
                        "ADVANCE",
                        batch.getId(),
                        row.getId(),
                        new CollectionImportService.Edit(
                                batch.getVersion(), row.getVersion(), revised)),
                409);
        assertEquals(0L, drafts.findById(row.getId()).block(WAIT).getVersion());
        assertEquals(0, entries.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Configuration
    @Import({
        FundsPersistenceTest.Config.class,
        TemporaryPersistenceTest.Config.class,
        CollectionImportSources.class,
        CollectionImportService.class
    })
    static class Config {}
}
