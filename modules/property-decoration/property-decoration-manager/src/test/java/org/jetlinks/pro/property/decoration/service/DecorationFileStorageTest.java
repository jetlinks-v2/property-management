package org.jetlinks.pro.property.decoration.service;

import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.command.CommandSupportManagerProvider;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.jetlinks.sdk.server.SdkServices;
import org.jetlinks.sdk.server.file.DownloadFileCommand;
import org.jetlinks.sdk.server.file.FileInfo;
import org.jetlinks.sdk.server.file.StreamUploadFileCommand;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DecorationFileStorageTest {
    private void withProvider(CommandSupport support, Runnable verify) {
        var previous = CommandSupportManagerProvider.supports.get(SdkServices.fileService);
        var registration = CommandSupportManagerProvider.supports.register(SdkServices.fileService, new CommandSupportManagerProvider() {
            @Override public String getProvider() { return SdkServices.fileService; }
            @Override public Mono<? extends CommandSupport> getCommandSupport(String module, Map<String,Object> options) {
                return Mono.deferContextual(context -> {
                    assertEquals("tenant", TenantHolder.readTenantId(context));
                    return Mono.justOrEmpty(support);
                });
            }
        });
        try { verify.run(); }
        finally {
            registration.dispose();
            previous.ifPresent(provider -> CommandSupportManagerProvider.supports.register(SdkServices.fileService, provider));
        }
    }

    @Test void uploadUsesPublicCommandTargetAndKeepsTenantAndContent() {
        var support = CommandSupport.create(StreamUploadFileCommand::new, command -> {
            assertEquals("template.png", command.getFileName());
            return command.stream().map(buffer -> {
                try { return buffer.toString(StandardCharsets.UTF_8); }
                finally { ReferenceCountUtil.safeRelease(buffer); }
            }).collectList().map(parts -> {
                assertEquals("image-bytes", String.join("", parts));
                FileInfo result = new FileInfo(); result.setId("remote-file"); return result;
            }).flux();
        });
        withProvider(support, () -> StepVerifier.create(new DecorationFileStorage()
            .save("template.png", Flux.just(DefaultDataBufferFactory.sharedInstance.wrap("image-bytes".getBytes(StandardCharsets.UTF_8))))
            .contextWrite(TenantHolder.makeCurrent("tenant")))
            .expectNext("remote-file").verifyComplete());
    }

    @Test void downloadUsesStoredIdAndReturnsTheRemoteBytes() {
        var support = CommandSupport.create(DownloadFileCommand::new, command -> {
            assertEquals("remote-file", command.getUrl());
            return Flux.just(Unpooled.wrappedBuffer(new byte[]{1, 2, 3}));
        });
        withProvider(support, () -> StepVerifier.create(new DecorationFileStorage().read("remote-file")
                .contextWrite(TenantHolder.makeCurrent("tenant")))
            .assertNext(buffer -> {
                try { byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); assertArrayEquals(new byte[]{1,2,3}, bytes); }
                finally { DataBufferUtils.release(buffer); }
            }).verifyComplete());
    }

    @Test void missingServiceAndUnsupportedUploadNeverFallBackToLocalDisk() {
        withProvider(null, () -> StepVerifier.create(new DecorationFileStorage().save("template.png", Flux.empty())
                .contextWrite(TenantHolder.makeCurrent("tenant")))
            .expectErrorSatisfies(error -> assertEquals(503, ((BusinessException) error).getStatus())).verify());
        var support = CommandSupport.create(DownloadFileCommand::new, command -> Flux.empty());
        withProvider(support, () -> StepVerifier.create(new DecorationFileStorage().save("template.png", Flux.empty())
                .contextWrite(TenantHolder.makeCurrent("tenant")))
            .expectErrorSatisfies(error -> assertEquals(503, ((BusinessException) error).getStatus())).verify());
    }

    @Test void configuredGatewayReadsStoredIdWithOnlyCurrentAuthentication() {
        var client = WebClient.builder().baseUrl("http://public-files")
            .exchangeFunction(request -> {
                assertEquals("/file/stored-id", request.url().getPath());
                assertEquals("test-token", request.headers().getFirst("X-Access-Token"));
                assertEquals("business_application:application", request.headers().getFirst("X-Asset-Scope"));
                assertEquals("tenant", request.headers().getFirst("X-Tenant-Domain"));
                assertNull(request.headers().getFirst("Cookie"));
                assertNull(request.headers().getFirst("X-Service-Id"));
                return Mono.just(ClientResponse.create(HttpStatus.OK).body("remote-bytes").build());
            }).build();
        var headers = new HttpHeaders();
        headers.set("X-Access-Token", "test-token");
        headers.set("X-Asset-Scope", "business_application:application");
        headers.set("X-Tenant-Domain", "tenant");
        headers.set("Cookie", "not-forwarded");
        headers.set("X-Service-Id", "client-cannot-select-upstream");
        StepVerifier.create(new DecorationFileStorage(client).read("stored-id")
                .contextWrite(context -> DecorationFileStorage.withRequestHeaders(context, headers)))
            .assertNext(buffer -> {
                try { assertEquals("remote-bytes", buffer.toString(StandardCharsets.UTF_8)); }
                finally { DataBufferUtils.release(buffer); }
            }).verifyComplete();
    }

    @Test void configuredGatewayCannotReadWithoutCurrentRequestAuthentication() {
        var client = WebClient.builder().baseUrl("http://public-files")
            .exchangeFunction(request -> Mono.error(new AssertionError("unauthenticated request sent"))).build();
        StepVerifier.create(new DecorationFileStorage(client).read("stored-id"))
            .expectErrorSatisfies(error -> assertEquals(503, ((BusinessException) error).getStatus())).verify();
        StepVerifier.create(new DecorationFileStorage(client).save("test.png", Flux.empty()))
            .expectErrorSatisfies(error -> assertEquals(503, ((BusinessException) error).getStatus())).verify();
    }

    @Test void configuredGatewayUploadUsesSamePrivateAuthenticatedEndpoint() {
        var client = WebClient.builder().baseUrl("http://public-files")
            .exchangeFunction(request -> {
                assertEquals("POST", request.method().name());
                assertEquals("/file/upload", request.url().getPath());
                assertNull(request.url().getQuery());
                assertEquals("test-token", request.headers().getFirst("X-Access-Token"));
                assertTrue(org.springframework.http.MediaType.MULTIPART_FORM_DATA
                    .isCompatibleWith(request.headers().getContentType()));
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("{\"status\":200,\"result\":{\"id\":\"public-file\"}}")
                    .build());
            }).build();
        var headers = new HttpHeaders();
        headers.set("X-Access-Token", "test-token");
        StepVerifier.create(new DecorationFileStorage(client).save("test.png", Flux.empty())
                .contextWrite(context -> DecorationFileStorage.withRequestHeaders(context, headers)))
            .expectNext("public-file").verifyComplete();
    }

    @Test void failedGatewayUploadNeverReturnsAReceiptOrRetries() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var client = WebClient.builder().baseUrl("http://public-files")
            .exchangeFunction(request -> {
                calls.incrementAndGet();
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("{\"status\":500,\"result\":{\"id\":\"not-saved\"}}")
                    .build());
            }).build();
        var headers = new HttpHeaders();
        headers.set("X-Access-Token", "test-token");
        StepVerifier.create(new DecorationFileStorage(client).save("test.png", Flux.empty())
                .contextWrite(context -> DecorationFileStorage.withRequestHeaders(context, headers)))
            .expectErrorSatisfies(error -> assertEquals(503, ((BusinessException) error).getStatus())).verify();
        assertEquals(1, calls.get());
    }
}
