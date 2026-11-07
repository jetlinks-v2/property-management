package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.*;
import org.hswebframework.web.authorization.simple.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.core.publisher.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** 大文件使用真实SAX和PG暂存；房屋远端/预览被确定性fixture替换，财务发布另由原持久化测试覆盖。 */
@SpringBootTest(
        classes = CollectionImportPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FinanceImportLargeTest {
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        HouseSettingPersistenceTest.database(r);
    }

    @BeforeAll
    static void messages() {
        CollectionImportPersistenceTest.messages();
    }

    @TempDir Path directory;
    @Autowired ApplicationContext context;
    @Autowired ReactiveRepository<FinanceImportTaskEntity, String> tasks;
    @Autowired ReactiveRepository<CollectionImportEntity, String> records;
    @Autowired ReactiveRepository<ReceivableImportEntity, String> historical;
    @Autowired ReactiveRepository<CollectionImportLineEntity, String> lines;
    @Autowired org.springframework.transaction.ReactiveTransactionManager transactions;
    CollectionImportPersistenceTest fixture;
    CollectionImportService large;
    CollectionImportSources source;
    CarryForwardAuthorityTest authority;
    Authentication executor;
    FinanceImportQueue queue;
    static final Duration WAIT = Duration.ofMinutes(4);

    @BeforeEach
    void setup() {
        fixture = new CollectionImportPersistenceTest();
        context.getAutowireCapableBeanFactory().autowireBean(fixture);
        fixture.setup();
        source = mock(CollectionImportSources.class);
        when(source.prepare(anyString(), anyString(), any(), anyInt()))
                .thenAnswer(
                        call -> {
                            var i = call.getArgument(2, CollectionImportInput.class);
                            i.setSpaceId("room");
                            i.setRelationshipId("link");
                            i.setSubjectId("subject");
                            i.setFullName("园区/1栋/101");
                            i.setCustomerName("龚超");
                            return Mono.just(new CollectionImportSources.Prepared(i));
                        });
        large =
                new CollectionImportService(
                        context.getBean(FinanceScope.class),
                        context.getBean(FinancialPeriodGate.class),
                        source,
                        records,
                        lines,
                        transactions);
        authority = new CarryForwardAuthorityTest();
        authority.holders();
        authority.rights.put("saved", Set.of("room", fixture.project));
        var auth = authority.auth("saved");
        auth.setPermissions(
                List.of(
                        SimplePermission.builder()
                                .id("property-finance-collection-import")
                                .actions(Set.of("import", "approve", "reject"))
                                .build()));
        executor = auth;
        queue = newQueue();
    }

    FinanceImportQueue newQueue() {
        var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(large);
        return new FinanceImportQueue(
                context.getBean(FinanceScope.class),
                tasks,
                records,
                historical,
                provider,
                context.getBeanProvider(HistoricalReceivableService.class),
                context.getBeanProvider(HistoricalImportAuditService.class),
                transactions,
                directory.toString(),
                id -> Mono.just(executor));
    }

    @AfterEach
    void stop() {
        queue.stop();
        authority.close();
    }

    reactor.util.context.Context actor() {
        return fixture.data.fixture.allowed().put(Authentication.class, executor);
    }

    org.springframework.http.codec.multipart.FilePart workbook(int count, boolean invalidLast)
            throws Exception {
        try (var book = new org.apache.poi.xssf.streaming.SXSSFWorkbook(100);
                var out = new java.io.ByteArrayOutputStream()) {
            var sheet = book.createSheet("导入");
            var header = sheet.createRow(0);
            var fields = CollectionImportSchema.fields("DEPOSIT");
            for (int c = 0; c < fields.size(); c++)
                header.createCell(c).setCellValue(fields.get(c).title());
            for (int r = 1; r <= count; r++) {
                var row = sheet.createRow(r);
                var values =
                        Map.of(
                                "fullName",
                                "园区/1栋/101",
                                "customerName",
                                "龚超",
                                "subjectName",
                                "物业费",
                                "methodText",
                                "现金",
                                "amount",
                                invalidLast && r == count ? "错误金额" : "1.00",
                                "paidAt",
                                "2024-02-15 10:25",
                                "externalNo",
                                "original-" + r);
                for (int c = 0; c < fields.size(); c++)
                    row.createCell(c).setCellValue(values.getOrDefault(fields.get(c).key(), ""));
            }
            book.write(out);
            book.dispose();
            var file = mock(org.springframework.http.codec.multipart.FilePart.class);
            when(file.filename()).thenReturn("大批量.xlsx");
            byte[] bytes = out.toByteArray();
            when(file.content())
                    .thenAnswer(
                            ignored ->
                                    Flux.just(
                                            new org.springframework.core.io.buffer
                                                            .DefaultDataBufferFactory()
                                                    .wrap(bytes)));
            return file;
        }
    }

    @Test
    void tenThousandRowsValidateBeforeFirstInsertAndResumeCommittedPagesWithoutRevalidation()
            throws Exception {
        var batch =
                queue.uploadCollection(fixture.project, "DEPOSIT", workbook(10001, false))
                        .contextWrite(actor())
                        .block(WAIT);
        assertEquals("PROCESSING", batch.getStatus());
        assertEquals(0, lines.createQuery().where("importId", batch.getId()).count().block(WAIT));
        // First execute under the same durable identity and deliberately interrupt after a
        // committed page.
        var task = tasks.findById(batch.getId()).block(WAIT);
        var raw = directory.resolve("raw.jsonl");
        var prepared = directory.resolve("prepared.jsonl");
        FinanceExcelRows.spool(directory.resolve(task.getId() + ".xlsx"), raw);
        var interruption = new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(
                RuntimeException.class,
                () ->
                        large.processLarge(
                                        fixture.project,
                                        "DEPOSIT",
                                        batch.getId(),
                                        raw,
                                        prepared,
                                        (phase, n) -> {
                                            if (phase.equals("VALIDATING"))
                                                return lines.createQuery()
                                                        .where("importId", batch.getId())
                                                        .count()
                                                        .doOnNext(none -> assertEquals(0, none))
                                                        .then();
                                            if (phase.equals("STAGING")
                                                    && n >= 100
                                                    && interruption.compareAndSet(false, true))
                                                return Mono.error(
                                                        new IllegalStateException("restart"));
                                            return Mono.empty();
                                        })
                                .contextWrite(actor())
                                .block(WAIT));
        assertEquals(100, lines.createQuery().where("importId", batch.getId()).count().block(WAIT));
        // Simulate a new service instance; prepared checkpoint must skip all source matching and
        // preserve first-page payloads.
        var never = mock(CollectionImportSources.class);
        var resumed =
                new CollectionImportService(
                        context.getBean(FinanceScope.class),
                        context.getBean(FinancialPeriodGate.class),
                        never,
                        records,
                        lines,
                        transactions);
        resumed.processLarge(
                        fixture.project,
                        "DEPOSIT",
                        batch.getId(),
                        raw,
                        prepared,
                        (phase, n) -> Mono.empty())
                .contextWrite(actor())
                .block(WAIT);
        verifyNoInteractions(never);
        assertEquals(
                10001, lines.createQuery().where("importId", batch.getId()).count().block(WAIT));
        assertEquals("PENDING", records.findById(batch.getId()).block(WAIT).getStatus());
        assertEquals(
                0,
                fixture.payments
                        .createQuery()
                        .where("projectId", fixture.project)
                        .count()
                        .block(WAIT));
    }

    @Test
    void invalidLastRowInTenThousandFileLeavesNoDraftOrFinancialData() throws Exception {
        var batch =
                queue.uploadCollection(fixture.project, "DEPOSIT", workbook(10001, true))
                        .contextWrite(actor())
                        .block(WAIT);
        queue.poll().block(WAIT);
        var failed = records.findById(batch.getId()).block(WAIT);
        assertEquals("FAILED", failed.getStatus());
        assertTrue(
                failed.getErrorMessage().replace(",", "").contains("10002"),
                failed.getErrorMessage());
        assertEquals(0, lines.createQuery().where("importId", batch.getId()).count().block(WAIT));
        assertEquals(
                0,
                fixture.entries
                        .createQuery()
                        .where("projectId", fixture.project)
                        .count()
                        .block(WAIT));
    }

    @Test
    void competingWorkersAndRestartLeaseCannotStageTwice() throws Exception {
        var batch =
                queue.uploadCollection(fixture.project, "DEPOSIT", workbook(601, false))
                        .contextWrite(actor())
                        .block(WAIT);
        var other = newQueue();
        var task = tasks.findById(batch.getId()).block(WAIT);
        Flux.merge(
                        queue.claimAndRun(task),
                        other.claimAndRun(tasks.findById(batch.getId()).block(WAIT)))
                .then()
                .block(WAIT);
        assertEquals("PENDING", records.findById(batch.getId()).block(WAIT).getStatus());
        assertEquals(601, lines.createQuery().where("importId", batch.getId()).count().block(WAIT));
        assertEquals("DONE", tasks.findById(batch.getId()).block(WAIT).getState());
        queue.poll().block(WAIT);
        assertEquals(601, lines.createQuery().where("importId", batch.getId()).count().block(WAIT));
        assertEquals(
                0,
                fixture.entries
                        .createQuery()
                        .where("projectId", fixture.project)
                        .count()
                        .block(WAIT));
    }

    @Test
    void backgroundApprovalResumesOnlyPendingRowsAfterPartialFailure() throws Exception {
        var batch =
                queue.uploadCollection(fixture.project, "DEPOSIT", workbook(601, false))
                        .contextWrite(actor())
                        .block(WAIT);
        queue.claimAndRun(tasks.findById(batch.getId()).block(WAIT)).block(WAIT);
        var pending = records.findById(batch.getId()).block(WAIT);
        assertEquals("PENDING", pending.getStatus());
        var published = new HashSet<String>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        when(source.publish(anyString(), anyString(), anyString(), any()))
                .thenAnswer(
                        call -> {
                            String request = call.getArgument(2);
                            if (published.size() == 100 && interrupted.compareAndSet(false, true))
                                return Mono.error(
                                        new IllegalStateException("restart during approval"));
                            assertTrue(
                                    published.add(request),
                                    "completed row must never publish again");
                            return Mono.just("payment-" + request);
                        });
        queue.reviewCollections(
                        fixture.project,
                        "DEPOSIT",
                        new CollectionImportService.BulkDecision(
                                List.of(
                                        new CollectionImportService.Item(
                                                pending.getId(), pending.getVersion())),
                                "approval"),
                        true)
                .contextWrite(actor())
                .block(WAIT);
        var first =
                tasks.createQuery()
                        .where("recordId", batch.getId())
                        .and("operation", "APPROVE")
                        .fetchOne()
                        .block(WAIT);
        queue.claimAndRun(first).block(WAIT);
        var failed = records.findById(batch.getId()).block(WAIT);
        assertEquals("PENDING", failed.getStatus());
        assertEquals(100, failed.getSuccessCount());
        queue.reviewCollections(
                        fixture.project,
                        "DEPOSIT",
                        new CollectionImportService.BulkDecision(
                                List.of(
                                        new CollectionImportService.Item(
                                                failed.getId(), failed.getVersion())),
                                "resume"),
                        true)
                .contextWrite(actor())
                .block(WAIT);
        var next =
                tasks.createQuery()
                        .where("recordId", batch.getId())
                        .and("operation", "APPROVE")
                        .and("state", "QUEUED")
                        .fetchOne()
                        .block(WAIT);
        queue.claimAndRun(next).block(WAIT);
        assertEquals(601, published.size());
        assertEquals("APPROVED", records.findById(batch.getId()).block(WAIT).getStatus());
        assertEquals(
                601,
                lines.createQuery()
                        .where("importId", batch.getId())
                        .and("status", "APPROVED")
                        .count()
                        .block(WAIT));
    }

    @Test
    void repeatedFileIsRejectedBeforeSecondBatchAndRevokedUserCannotExecute() throws Exception {
        var file = workbook(2, false);
        var batch =
                queue.uploadCollection(fixture.project, "DEPOSIT", file)
                        .contextWrite(actor())
                        .block(WAIT);
        assertThrows(
                RuntimeException.class,
                () ->
                        queue.uploadCollection(fixture.project, "DEPOSIT", file)
                                .contextWrite(actor())
                                .block(WAIT));
        ((SimpleAuthentication) executor).setPermissions(List.of());
        queue.poll().block(WAIT);
        assertEquals("FAILED", records.findById(batch.getId()).block(WAIT).getStatus());
        assertEquals(0, lines.createQuery().where("importId", batch.getId()).count().block(WAIT));
    }
}
