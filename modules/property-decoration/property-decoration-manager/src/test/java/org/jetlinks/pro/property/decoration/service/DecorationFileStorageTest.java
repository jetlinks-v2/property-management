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
                    assertTrue(context.stream().anyMatch(entry -> "public-file".equals(entry.getValue())));
                    assertEquals("tenant", TenantHolder.readTenantId(context));
                    return Mono.just(support);
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
        withProvider(support, () -> StepVerifier.create(new DecorationFileStorage("public-file")
            .save("template.png", Flux.just(DefaultDataBufferFactory.sharedInstance.wrap("image-bytes".getBytes(StandardCharsets.UTF_8))))
            .contextWrite(TenantHolder.makeCurrent("tenant")))
            .expectNext("remote-file").verifyComplete());
    }

    @Test void downloadUsesStoredIdAndReturnsTheRemoteBytes() {
        var support = CommandSupport.create(DownloadFileCommand::new, command -> {
            assertEquals("remote-file", command.getUrl());
            return Flux.just(Unpooled.wrappedBuffer(new byte[]{1, 2, 3}));
        });
        withProvider(support, () -> StepVerifier.create(new DecorationFileStorage("public-file").read("remote-file")
                .contextWrite(TenantHolder.makeCurrent("tenant")))
            .assertNext(buffer -> {
                try { byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); assertArrayEquals(new byte[]{1,2,3}, bytes); }
                finally { DataBufferUtils.release(buffer); }
            }).verifyComplete());
    }

    @Test void missingTargetAndUnsupportedUploadNeverFallBackToLocalDisk() {
        StepVerifier.create(new DecorationFileStorage("").save("template.png", Flux.empty()))
            .expectErrorSatisfies(error -> assertEquals(503, ((BusinessException) error).getStatus())).verify();
        var support = CommandSupport.create(DownloadFileCommand::new, command -> Flux.empty());
        withProvider(support, () -> StepVerifier.create(new DecorationFileStorage("public-file").save("template.png", Flux.empty())
                .contextWrite(TenantHolder.makeCurrent("tenant")))
            .expectErrorSatisfies(error -> assertEquals(503, ((BusinessException) error).getStatus())).verify());
    }
}
