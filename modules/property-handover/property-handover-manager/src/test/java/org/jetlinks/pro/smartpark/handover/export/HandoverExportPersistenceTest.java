package org.jetlinks.pro.smartpark.handover.export;

import static org.jetlinks.pro.smartpark.handover.service.HandoverPersistenceFixture.WAIT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.io.file.FileInfo;
import org.jetlinks.pro.io.file.FileManager;
import org.jetlinks.pro.smartpark.handover.api.ExportRequest;
import org.jetlinks.pro.smartpark.handover.entity.*;
import org.jetlinks.pro.smartpark.handover.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.buffer.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.core.publisher.*;

import java.util.*;
import java.util.function.Supplier;

@SpringBootTest(
        classes = HandoverPersistenceFixture.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HandoverExportPersistenceTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        HandoverPersistenceFixture.database(registry);
    }

    @Autowired HandoverExportRepository tasks;
    @Autowired HandoverScope scope;
    HandoverExportScope authorization;
    HandoverExportRows rows;
    FileManager files;
    HandoverExportService service;

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void prepare() {
        tasks.createDelete().where("tenantId", "tenant").execute().block(WAIT);
        authorization = mock(HandoverExportScope.class);
        rows = mock(HandoverExportRows.class);
        files = mock(FileManager.class);
        doAnswer(call -> ((Supplier<Mono>) call.getArgument(1)).get())
                .when(authorization)
                .execute(any(), any());
        service = new HandoverExportService(tasks, scope, authorization, rows, files);
    }

    HandoverExportEntity insert(String id) {
        var task = new HandoverExportEntity();
        task.setId(id);
        task.setTenantId("tenant");
        task.setApplicationId("application");
        task.setProjectId("project");
        task.setCreatorId("creator");
        task.setCreatorName("creator");
        task.setCreateTime(System.currentTimeMillis());
        task.setModifyTime(task.getCreateTime());
        task.setDeleted(false);
        task.setVersion(0);
        task.setKind("problem");
        task.setStatus("pending");
        task.setAttempts(0);
        task.setLeaseUntil(0L);
        task.setFileName(id + ".xlsx");
        var request = new ExportRequest();
        request.setProjectId("project");
        request.setKind("problem");
        task.setRequest(request);
        tasks.insert(task).block(WAIT);
        return task;
    }

    @Test
    void crossProjectLedgerTaskPersistsAndHistoryIsLimitedToCreatorAndBusinessKind() {
        var input = new ExportRequest(); input.setKind("ledger");
        when(rows.selector(any(), anyInt(), any())).thenReturn(Mono.just(new org.hswebframework.web.api.crud.entity.QueryParamEntity()));
        var task = service.create(input).contextWrite(HandoverPersistenceFixture.access("creator", Set.of())).block(WAIT);
        assertEquals("", task.getProjectId()); assertNotNull(task.getId());
        assertEquals(task.getId(), service.visible(task.getId()).contextWrite(HandoverPersistenceFixture.access("creator", Set.of())).block(WAIT).getId());
        var query = new HandoverQuery(); query.setKind("ledger");
        assertEquals(1, service.query(query).contextWrite(HandoverPersistenceFixture.access("creator", Set.of())).block(WAIT).getTotal());
        assertEquals(0, service.query(query).contextWrite(HandoverPersistenceFixture.access("other", Set.of())).block(WAIT).getTotal());
        assertThrows(AccessDenyException.class, () -> service.visible(task.getId()).contextWrite(HandoverPersistenceFixture.access("other", Set.of())).block(WAIT));
    }

    @Test
    void databaseCompareAndSetAllowsOnlyOneConcurrentWorkerAndRestartReclaimsExpiredLease() {
        insert("concurrent");
        var a = tasks.findById("concurrent").block(WAIT);
        var b = tasks.findById("concurrent").block(WAIT);
        var second = new HandoverExportService(tasks, scope, authorization, rows, files);
        List<Boolean> results =
                Flux.merge(service.claim(a), second.claim(b)).collectList().block(WAIT);
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        var running = tasks.findById("concurrent").block(WAIT);
        assertEquals("running", running.getStatus());
        assertEquals(1, running.getAttempts());
        assertFalse(second.claim(tasks.findById("concurrent").block(WAIT)).block(WAIT));
        tasks.createUpdate()
                .set("leaseUntil", System.currentTimeMillis() - 1)
                .where("id", "concurrent")
                .execute()
                .block(WAIT);
        var restarted = new HandoverExportService(tasks, scope, authorization, rows, files);
        assertTrue(restarted.claim(tasks.findById("concurrent").block(WAIT)).block(WAIT));
        var recovered = tasks.findById("concurrent").block(WAIT);
        assertEquals(2, recovered.getAttempts());
        assertNotEquals(running.getLeaseOwner(), recovered.getLeaseOwner());
    }

    @Test
    void missingPhotoMarksTaskFailedAndNeverUploadsPartialWorkbook() {
        insert("bad-photo");
        var problem = new HandoverProblemEntity();
        problem.setId("problem");
        when(rows.page(any(), eq(0))).thenReturn(Mono.just(List.of(problem)));
        when(rows.values(problem)).thenReturn(Mono.just(Map.of("description", "需要图片")));
        var photo = new HandoverPhotoEntity();
        photo.setFileId("unreadable");
        photo.setPhotoType("problem");
        when(rows.photos(problem)).thenReturn(Flux.just(photo));
        when(rows.nodes(problem)).thenReturn(Flux.empty());
        when(rows.verify(any(), anyList())).thenReturn(Mono.empty());
        when(files.read("unreadable"))
                .thenReturn(Flux.error(new java.io.IOException("storage unavailable")));
        service.tick()
                .contextWrite(HandoverPersistenceFixture.access("creator", Set.of("project")))
                .block(WAIT);
        var failed = tasks.findById("bad-photo").block(WAIT);
        assertEquals("failed", failed.getStatus());
        assertTrue(failed.getError().contains("unreadable"));
        assertNull(failed.getFileId());
        verify(files, never()).saveFile(anyString(), any(Flux.class));
        var retried =
                service.retry("bad-photo")
                        .contextWrite(
                                HandoverPersistenceFixture.access("creator", Set.of("project")))
                        .block(WAIT);
        assertEquals("pending", retried.getStatus());
        assertEquals(1, retried.getAttempts());
    }

    @Test
    void originalSelectorSurvivesPersistenceAndEveryPageIsReadBeyondTwoHundred() {
        var task = insert("pages");
        task.getRequest().setSource("QUALITY_ACCEPTANCE");
        task.getRequest()
                .setFilters(
                        Map.of("inspectionTimeFrom", 100L, "inspectionTimeTo", 200L, "status", 2));
        tasks.updateById(task.getId(), task).block(WAIT);
        var page = new ArrayList<HandoverEntity>();
        for (int i = 0; i < 100; i++) {
            var p = new HandoverCategoryEntity();
            p.setId("row-" + i);
            page.add(p);
            when(rows.values(p)).thenReturn(Mono.just(Map.of("name", "分类")));
        }
        when(rows.page(any(), eq(0))).thenReturn(Mono.just(page));
        when(rows.page(any(), eq(1))).thenReturn(Mono.just(page));
        when(rows.page(any(), eq(2))).thenReturn(Mono.just(page.subList(0, 31)));
        when(rows.verify(any(), anyList())).thenReturn(Mono.empty());
        FileInfo workbook = new FileInfo();
        workbook.setId("workbook");
        FileInfo manifest = new FileInfo();
        manifest.setId("manifest");
        doAnswer(
                        call -> {
                            String name = call.getArgument(0);
                            Flux<DataBuffer> data = call.getArgument(1);
                            return data.doOnNext(DataBufferUtils::release)
                                    .then(Mono.just(name.endsWith(".xlsx") ? workbook : manifest));
                        })
                .when(files)
                .saveFile(anyString(), any(Flux.class));
        service.tick()
                .contextWrite(HandoverPersistenceFixture.access("creator", Set.of("project")))
                .block(WAIT);
        assertEquals("completed", tasks.findById(task.getId()).block(WAIT).getStatus());
        var selector = org.mockito.ArgumentCaptor.forClass(ExportRequest.class);
        verify(rows).page(selector.capture(), eq(2));
        assertEquals("QUALITY_ACCEPTANCE", selector.getValue().getSource());
        assertEquals(
                100,
                ((Number) selector.getValue().getFilters().get("inspectionTimeFrom")).longValue());
        verify(rows, times(3)).verify(any(), anyList());
    }

    @Test
    void downloadRechecksAllSourceBatchesAndRejectsRevokedRecordBeforeReadingWorkbook() {
        var task = insert("download");
        tasks.createUpdate()
                .set("status", "completed")
                .set("fileId", "xlsx")
                .set("manifestFileId", "manifest")
                .where("id", task.getId())
                .execute()
                .block(WAIT);
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 231; i++) source.append("source-").append(i).append('\n');
        when(files.read("manifest"))
                .thenAnswer(
                        call ->
                                Flux.just(
                                        DefaultDataBufferFactory.sharedInstance.wrap(
                                                source.toString()
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8))));
        when(rows.verify(any(), anyList()))
                .thenAnswer(
                        call -> {
                            List<String> ids = call.getArgument(1);
                            return ids.contains("source-230")
                                    ? Mono.error(new AccessDenyException())
                                    : Mono.empty();
                        });
        reactor.test.StepVerifier.create(
                        service.authorizeDownload(task.getId())
                                .contextWrite(
                                        HandoverPersistenceFixture.access(
                                                "creator", Set.of("project"))))
                .expectError(AccessDenyException.class)
                .verify(WAIT);
        verify(rows, times(3)).verify(any(), anyList());
        verify(files, never()).read("xlsx");
    }

    @Test
    void filesAreDeletedWhenDatabaseCompletionLosesItsLease() {
        var task = insert("compensate");
        when(rows.page(any(), eq(0))).thenReturn(Mono.just(List.of()));
        when(files.delete(anyString())).thenReturn(Mono.empty());
        doAnswer(
                        call -> {
                            String name = call.getArgument(0);
                            Flux<DataBuffer> data = call.getArgument(1);
                            FileInfo file = new FileInfo();
                            file.setId(
                                    name.endsWith(".xlsx") ? "saved-workbook" : "saved-manifest");
                            Mono<Void> steal =
                                    name.endsWith(".xlsx")
                                            ? Mono.empty()
                                            : tasks.createUpdate()
                                                    .set("status", "failed")
                                                    .set("leaseOwner", "another-worker")
                                                    .where("id", task.getId())
                                                    .execute()
                                                    .then();
                            return data.doOnNext(DataBufferUtils::release)
                                    .then(steal)
                                    .thenReturn(file);
                        })
                .when(files)
                .saveFile(anyString(), any(Flux.class));
        service.tick()
                .contextWrite(HandoverPersistenceFixture.access("creator", Set.of("project")))
                .block(WAIT);
        assertEquals("failed", tasks.findById(task.getId()).block(WAIT).getStatus());
        verify(files).delete("saved-workbook");
        verify(files).delete("saved-manifest");
    }

    @Test
    void optionalClientMimeTypeDoesNotSilentlyDropLedgerPictures() throws Exception {
        var task = insert("ledger-images");
        task.setKind("ledger");
        task.getRequest().setKind("ledger");
        tasks.updateById(task.getId(), task).block(WAIT);
        var ledger = new HandoverLedgerEntity();
        ledger.setId("ledger");
        var ref = new org.jetlinks.pro.smartpark.handover.api.FileRef();
        ref.setFileId("optional-type-image");
        ledger.setFiles(List.of(ref));
        when(rows.page(any(), eq(0))).thenReturn(Mono.just(List.of(ledger)));
        when(rows.values(ledger)).thenReturn(Mono.just(Map.of("title", "有图台账")));
        when(rows.verify(any(), anyList())).thenReturn(Mono.empty());
        var metadata = new FileInfo();
        metadata.setId(ref.getFileId());
        metadata.setName("image.png");
        metadata.setExtension("png");
        when(files.getFile(ref.getFileId())).thenReturn(Mono.just(metadata));
        byte[] image;
        try (var out = new java.io.ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(
                    new java.awt.image.BufferedImage(
                            2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
                    "png",
                    out);
            image = out.toByteArray();
        }
        when(files.read(ref.getFileId()))
                .thenAnswer(call -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(image)));
        doAnswer(
                        call -> {
                            Flux<DataBuffer> data = call.getArgument(1);
                            FileInfo file = new FileInfo();
                            file.setId("saved-" + call.getArgument(0));
                            return data.doOnNext(DataBufferUtils::release).then(Mono.just(file));
                        })
                .when(files)
                .saveFile(anyString(), any(Flux.class));
        service.tick()
                .contextWrite(HandoverPersistenceFixture.access("creator", Set.of("project")))
                .block(WAIT);
        var saved = tasks.findById(task.getId()).block(WAIT);
        assertEquals("completed", saved.getStatus(), saved.getError());
        verify(files).read(ref.getFileId());
    }
}
