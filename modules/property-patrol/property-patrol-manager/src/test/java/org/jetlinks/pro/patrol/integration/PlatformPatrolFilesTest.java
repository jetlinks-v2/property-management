package org.jetlinks.pro.patrol.integration;

import io.netty.buffer.Unpooled;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.sdk.server.file.DownloadFileCommand;
import org.jetlinks.sdk.server.file.FileInfo;
import org.jetlinks.sdk.server.file.UploadFileCommand;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformPatrolFilesTest {
    @Test
    void uploadUsesRealSdkCommandAndReturnsPlatformIdentity() {
        var support = mock(CommandSupport.class);
        var file = new FileInfo();
        file.setId("platform-id");
        file.setAccessUrl("platform-owned-location");
        when(support.execute(any(UploadFileCommand.class))).thenAnswer(invocation -> {
            UploadFileCommand command = invocation.getArgument(0);
            assertEquals("image.png", command.getFileName());
            assertEquals("image/png", command.getContentType());
            assertEquals(3, command.getContentLength());
            byte[] content = new byte[3]; command.getContent().readBytes(content);
            assertArrayEquals(new byte[] {1, 2, 3}, content);
            command.getContent().release();
            return Mono.just(file);
        });
        var files = new PlatformPatrolFiles(() -> Mono.just(support));
        StepVerifier.create(files.upload("image.png", "image/png", new byte[] {1, 2, 3}))
                .expectNext(file).verifyComplete();
    }

    @Test
    void downloadUsesStoredLocationAndReleasesBuffersEvenWhenOversized() {
        var support = mock(CommandSupport.class);
        var first = Unpooled.wrappedBuffer(new byte[] {1, 2});
        var second = Unpooled.wrappedBuffer(new byte[] {3});
        when(support.execute(any(DownloadFileCommand.class))).thenAnswer(invocation -> {
            DownloadFileCommand command = invocation.getArgument(0);
            assertEquals("platform-owned-location", command.getUrl());
            return Flux.just(first, second);
        });
        var files = new PlatformPatrolFiles(() -> Mono.just(support));
        StepVerifier.create(files.read("platform-owned-location"))
                .assertNext(bytes -> assertArrayEquals(new byte[] {1, 2, 3}, bytes)).verifyComplete();
        assertEquals(0, first.refCnt()); assertEquals(0, second.refCnt());
        var oversized = Unpooled.wrappedBuffer(new byte[PlatformPatrolFiles.MAX_IMAGE_BYTES + 1]);
        when(support.execute(any(DownloadFileCommand.class))).thenReturn(Flux.just(oversized));
        StepVerifier.create(files.read("platform-owned-location")).expectError(BusinessException.class).verify();
        assertEquals(0, oversized.refCnt());
    }

    @Test
    void missingPlatformFileServiceDoesNotPretendUploadSucceeded() {
        var files = new PlatformPatrolFiles(Mono::empty);
        StepVerifier.create(files.upload("x.png", "image/png", new byte[] {1}))
                .expectError(BusinessException.class).verify();
    }

    @Test
    void emptyOrIncompletePlatformResponsesFailExplicitly() {
        var support = mock(CommandSupport.class);
        when(support.execute(any(UploadFileCommand.class))).thenAnswer(invocation -> {
            UploadFileCommand command = invocation.getArgument(0);
            command.getContent().release();
            return Mono.empty();
        });
        var files = new PlatformPatrolFiles(() -> Mono.just(support));
        StepVerifier.create(files.upload("x.png", "image/png", new byte[] {1}))
                .expectError(BusinessException.class).verify();
        when(support.execute(any(UploadFileCommand.class))).thenAnswer(invocation -> {
            UploadFileCommand command = invocation.getArgument(0);
            command.getContent().release();
            return Mono.just(new FileInfo());
        });
        StepVerifier.create(files.upload("x.png", "image/png", new byte[] {1}))
                .expectError(BusinessException.class).verify();
        when(support.execute(any(DownloadFileCommand.class))).thenReturn(Flux.empty());
        StepVerifier.create(files.read("platform-owned-location"))
                .expectError(BusinessException.class).verify();
    }
}
