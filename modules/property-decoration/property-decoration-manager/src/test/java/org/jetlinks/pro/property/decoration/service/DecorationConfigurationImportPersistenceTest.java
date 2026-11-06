package org.jetlinks.pro.property.decoration.service;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimplePermission;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.jetlinks.pro.property.base.dictionary.PropertyDictionaryItemInfo;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.jetlinks.pro.property.decoration.entity.DecorationAttachmentEntity;
import org.jetlinks.pro.property.decoration.entity.DecorationConfigurationEntity;
import org.jetlinks.pro.property.decoration.entity.DecorationConfigurationImportEntity;
import org.jetlinks.pro.property.decoration.excel.DecorationCheckImportPlan;
import org.jetlinks.pro.property.decoration.excel.DecorationCheckWorkbook;
import org.jetlinks.pro.property.decoration.model.DecorationModels;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 整批导入使用真实PostgreSQL/CAS/事务，仅替换公共目录及文件服务。 */
@SpringBootTest(classes=DecorationPersistenceTest.Config.class, webEnvironment=SpringBootTest.WebEnvironment.NONE)
class DecorationConfigurationImportPersistenceTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jP1sAAAAASUVORK5CYII=");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { DecorationPersistenceTest.database(registry); }
    @Autowired DecorationConfigurationImportService service;
    @Autowired DecorationConfigurationImportWriter writer;
    @Autowired DecorationConfigurationImportRepository imports;
    @Autowired DecorationConfigurationRepository configs;
    @Autowired DecorationConfigurationService configurations;
    @Autowired DecorationAttachmentRepository files;
    @Autowired DecorationFileStorage storage;
    @Autowired DecorationScope scope;

    @BeforeEach void prepare() {
        imports.deleteById(imports.createQuery().fetch().map(DecorationConfigurationImportEntity::getId)).block(WAIT);
        configs.deleteById(configs.createQuery().fetch().map(DecorationConfigurationEntity::getId)).block(WAIT);
        files.deleteById(files.createQuery().fetch().map(DecorationAttachmentEntity::getId)).block(WAIT);
        reset(scope, storage);
        when(scope.current()).thenAnswer(call -> Authentication.currentReactive().map(auth -> new DecorationScope.Context("tenant", "app", auth.getUser().getId(), auth.getUser().getName(), auth)));
        var project = new PropertyOrganizationInfo(); project.setId("project"); project.setName("项目");
        when(scope.projects()).thenReturn(Flux.just(project));
        when(scope.project(anyString())).thenAnswer(call -> "project".equals(call.getArgument(0)) ? Mono.just(project) : Mono.error(new AccessDenyException()));
        when(scope.employee()).thenReturn(Mono.just(new PropertyEmployeeInfo("admin", "管理员", "", List.of("project"))));
        when(scope.dictionaryItems("house-type")).thenReturn(Flux.just(new PropertyDictionaryItemInfo("residential", "住宅", 1L)));
        when(storage.delete(anyString())).thenReturn(Mono.empty());
        configurations.save(config("daily", 0)).contextWrite(auth("admin")).block(WAIT);
    }
    private DecorationRequests.Config config(String scene, long version) {
        var input = new DecorationRequests.Config(); input.setName("原配置"); input.setKind("checks"); input.setProjectId("project");
        input.setHouseType("residential"); input.setDecorationType("first"); input.setScene(scene); input.setVersion(version);
        input.setContent(new DecorationModels.Configuration()); return input;
    }
    private static Context auth(String id) {
        var user = new SimpleUser(); user.setId(id); user.setName(id);
        var auth = new SimpleAuthentication(); auth.setUser(user); auth.setDimensions(List.of());
        auth.setPermissions(List.of(SimplePermission.builder().id("decoration-configuration").actions(Set.of("save")).build()));
        return Context.of(Authentication.class, auth);
    }
    private record Part(byte[] bytes) implements FilePart {
        @Override public String filename() { return "configuration.xlsx"; }
        @Override public String name() { return "file"; }
        @Override public HttpHeaders headers() { return new HttpHeaders(); }
        @Override public Flux<DataBuffer> content() { return Flux.defer(() -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes))); }
        @Override public Mono<Void> transferTo(Path path) { return Mono.error(new UnsupportedOperationException()); }
    }
    private byte[] workbook(boolean images, String... scenes) throws Exception {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("检查配置"); var header = sheet.createRow(0);
            for (int col = 0; col < 19; col++) header.createCell(col).setCellValue(DecorationCheckWorkbook.HEADERS.get(col));
            for (int index = 0; index < scenes.length; index++) {
                var values = List.of(scenes[index], "项目", "住宅", "首次装修", "安全", "1", "检查项" + index, images ? "图像" : "文本", "是", "是", String.valueOf(index + 1), "标准", "", "", "", "", "", "", "图片标准");
                var row = sheet.createRow(index + 1); for (int col = 0; col < 19; col++) row.createCell(col).setCellValue(values.get(col));
                if (images) {
                    var anchor = new XSSFClientAnchor(); anchor.setCol1(17); anchor.setCol2(18); anchor.setRow1(index + 1); anchor.setRow2(index + 2);
                    sheet.createDrawingPatriarch().createPicture(anchor, book.addPicture(PNG, Workbook.PICTURE_TYPE_PNG));
                }
            }
            book.write(out); return out.toByteArray();
        }
    }
    private Mono<String> uploaded(Flux<DataBuffer> content, String id) {
        return DataBufferUtils.join(content).map(buffer -> {
            try { byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); assertArrayEquals(PNG, bytes); return id; }
            finally { DataBufferUtils.release(buffer); }
        });
    }

    @Test void batchSuccessIdempotencyDigestAndReadOnlyLookupPreserveSavedVersions() throws Exception {
        var bytes = workbook(false, "日常巡检", "首次验收");
        var result = service.importChecks("same", new Part(bytes)).contextWrite(auth("admin")).block(WAIT);
        assertEquals(2, result.importedCount()); assertEquals(2, result.configurations().size()); assertTrue(result.errors().isEmpty());
        assertEquals(2, configs.createQuery().count().block(WAIT)); assertEquals(1, imports.createQuery().count().block(WAIT));
        assertEquals(result, service.importChecks("same", new Part(bytes)).contextWrite(auth("admin")).block(WAIT));
        assertEquals(result, service.result("same").contextWrite(auth("admin")).block(WAIT));
        assertEquals(1, imports.createQuery().count().block(WAIT));
        var different = workbook(false, "二次验收");
        StepVerifier.create(service.importChecks("same", new Part(different)).contextWrite(auth("admin")))
            .expectErrorMatches(e -> e.getMessage().contains("request_conflict")).verify(WAIT);
        StepVerifier.create(service.result("same").contextWrite(auth("other")))
            .expectErrorMatches(e -> e.getMessage().contains("request_not_found")).verify(WAIT);
        when(scope.project("project")).thenReturn(Mono.error(new AccessDenyException()));
        StepVerifier.create(service.result("same").contextWrite(auth("admin"))).expectError(AccessDenyException.class).verify(WAIT);
        verifyNoInteractions(storage);
    }

    @Test void anyInvalidRowOrUnavailableDirectoryPreventsEveryConfigurationWrite() throws Exception {
        var invalid = service.importChecks("invalid", new Part(workbook(false, "日常巡检", "unknown"))).contextWrite(auth("admin")).block(WAIT);
        assertEquals(0, invalid.importedCount()); assertTrue(invalid.errors().stream().anyMatch(e -> e.rowNumber() == 3 && e.code().equals("choice")));
        assertEquals(1, configs.createQuery().count().block(WAIT)); assertEquals(0, imports.createQuery().count().block(WAIT));
        when(scope.dictionaryItems("house-type")).thenReturn(Flux.error(DecorationRules.error("directory_unavailable", 503)));
        StepVerifier.create(service.importChecks("missing-directory", new Part(workbook(false, "日常巡检"))).contextWrite(auth("admin")))
            .expectErrorMatches(e -> e.getMessage().contains("directory_unavailable")).verify(WAIT);
        verifyNoInteractions(storage);
    }

    @Test void staleSecondConfigurationRollsBackEarlierConfigurationAndReceipt() throws Exception {
        var ctx = scope.current().contextWrite(auth("admin")).block(WAIT);
        var plan = DecorationCheckImportPlan.resolve(DecorationCheckWorkbook.read(workbook(false, "日常巡检", "首次验收")),
            scope.projects().collectList().block(WAIT), scope.dictionaryItems("house-type").collectList().block(WAIT));
        var prepared = writer.prepare(plan, ctx).block(WAIT);
        configurations.save(config("firstAcceptance", 0)).contextWrite(auth("admin")).block(WAIT);
        StepVerifier.create(writer.write("stale", "digest", plan, prepared, List.of(), ctx).contextWrite(auth("admin")))
            .expectErrorMatches(e -> e.getMessage().contains("version_conflict")).verify(WAIT);
        var daily = configs.createQuery().where("scene", "daily").fetchOne().block(WAIT);
        assertEquals(1L, daily.getVersion()); assertTrue(daily.getContent().getChecks().isEmpty());
        assertEquals(0, imports.createQuery().count().block(WAIT));
    }

    @Test void importedPicturesArePrivateAndRetainedByTheSuccessfulReceipt() throws Exception {
        when(storage.save(anyString(), any())).thenAnswer(call -> uploaded(call.getArgument(1), "public-file-" + java.util.UUID.randomUUID()));
        var result = service.importChecks("images", new Part(workbook(true, "日常巡检", "日常巡检"))).contextWrite(auth("admin")).block(WAIT);
        assertEquals(2, result.importedCount()); assertEquals(2, result.attachmentIds().size()); assertEquals(2, files.createQuery().count().block(WAIT));
        var saved = configs.createQuery().where("scene", "daily").fetchOne().block(WAIT);
        assertEquals(Set.copyOf(result.attachmentIds()), saved.getContent().getChecks().stream().map(DecorationModels.CheckItem::getExampleFileId).collect(java.util.stream.Collectors.toSet()));
        for (var id : result.attachmentIds()) {
            var receipt = files.findById(id).block(WAIT); assertEquals("template", receipt.getPurpose()); assertEquals("project", receipt.getProjectId());
            assertEquals("app", receipt.getApplicationId()); assertEquals("tenant", receipt.getTenantId()); assertNull(receipt.getDecorationId());
        }
        verify(storage, never()).delete(anyString());
    }

    @Test void laterImageUploadFailureCompensatesEarlierImagesWithoutPartialConfigurations() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(storage.save(anyString(), any())).thenAnswer(call -> uploaded(call.getArgument(1), "first-stored").flatMap(id -> calls.incrementAndGet() == 1
            ? Mono.just(id) : Mono.error(DecorationRules.error("file_service_unavailable", 503))));
        StepVerifier.create(service.importChecks("image-failure", new Part(workbook(true, "日常巡检", "日常巡检"))).contextWrite(auth("admin")))
            .expectErrorMatches(e -> e.getMessage().contains("file_service_unavailable")).verify(WAIT);
        assertEquals(0, files.createQuery().count().block(WAIT)); assertEquals(0, imports.createQuery().count().block(WAIT));
        assertEquals(1L, configs.createQuery().where("scene", "daily").fetchOne().block(WAIT).getVersion());
        verify(storage).delete("first-stored");
    }

    @Test void configurationConflictAfterImageUploadCompensatesTheUncommittedImage() throws Exception {
        when(storage.save(anyString(), any())).thenAnswer(call -> uploaded(call.getArgument(1), "uploaded-before-conflict")
            .flatMap(id -> { var concurrent = config("daily", 1); concurrent.setName("其他编辑已保存"); return configurations.save(concurrent).thenReturn(id); }));
        StepVerifier.create(service.importChecks("concurrent-edit", new Part(workbook(true, "日常巡检"))).contextWrite(auth("admin")))
            .expectErrorMatches(e -> e.getMessage().contains("version_conflict")).verify(WAIT);
        var saved = configs.createQuery().where("scene", "daily").fetchOne().block(WAIT);
        assertEquals(2L, saved.getVersion()); assertEquals("其他编辑已保存", saved.getName()); assertTrue(saved.getContent().getChecks().isEmpty());
        assertEquals(0, files.createQuery().count().block(WAIT)); assertEquals(0, imports.createQuery().count().block(WAIT));
        verify(storage).delete("uploaded-before-conflict");
    }

    @Test void competingSameRequestRecoversTheCommittedWinnerAfterItsOwnTransactionRollsBack() throws Exception {
        for (var scene : List.of("日常巡检", "首次验收")) {
            var enteredFirst = Sinks.<Boolean>one(); var enteredSecond = Sinks.<Boolean>one();
            var firstGate = Sinks.<Boolean>one(); var secondGate = Sinks.<Boolean>one();
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var project = new PropertyOrganizationInfo(); project.setId("project"); project.setName("项目");
            when(scope.project("project")).thenAnswer(call -> Mono.defer(() -> {
                int index = calls.incrementAndGet();
                if (index == 1) { enteredFirst.tryEmitValue(true); return firstGate.asMono().thenReturn(project); }
                if (index == 2) { enteredSecond.tryEmitValue(true); return secondGate.asMono().thenReturn(project); }
                return Mono.just(project);
            }));
            var bytes = workbook(false, scene); String requestId = "concurrent-" + scene;
            var first = service.importChecks(requestId, new Part(bytes)).contextWrite(auth("admin")).toFuture();
            assertEquals(true, enteredFirst.asMono().block(WAIT));
            var second = service.importChecks(requestId, new Part(bytes)).contextWrite(auth("admin")).toFuture();
            assertEquals(true, enteredSecond.asMono().block(WAIT));
            firstGate.tryEmitValue(true); var winner = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            secondGate.tryEmitValue(true); assertEquals(winner, second.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(winner, service.result(requestId).contextWrite(auth("admin")).block(WAIT));
            assertEquals(winner.configurations().get(0).version(), configs.findById(winner.configurations().get(0).id()).block(WAIT).getVersion());
        }
        assertEquals(2, imports.createQuery().count().block(WAIT)); verifyNoInteractions(storage);
    }

    @Test void disconnectedRequestStillFinishesTheStartedImportAndCanBeLookedUp() throws Exception {
        var entered = Sinks.<Boolean>one(); var gate = Sinks.<String>one();
        when(storage.save(anyString(), any())).thenAnswer(call -> uploaded(call.getArgument(1), "ready").flatMap(id -> {
            entered.tryEmitValue(true); return gate.asMono();
        }));
        var subscriber = service.importChecks("disconnected", new Part(workbook(true, "日常巡检"))).contextWrite(auth("admin")).subscribe();
        assertEquals(true, entered.asMono().block(WAIT)); subscriber.dispose(); gate.tryEmitValue("stored-after-disconnect");
        var result = service.result("disconnected").contextWrite(auth("admin"))
            .retryWhen(reactor.util.retry.Retry.fixedDelay(100, Duration.ofMillis(10)).filter(e -> e.getMessage().contains("request_not_found"))).block(WAIT);
        assertEquals(1, result.importedCount()); assertEquals(1, files.createQuery().count().block(WAIT));
        verify(storage, never()).delete(anyString());
    }
}
