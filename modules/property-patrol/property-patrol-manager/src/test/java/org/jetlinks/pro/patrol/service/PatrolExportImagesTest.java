package org.jetlinks.pro.patrol.service;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PatrolExportImagesTest {
    @Test void thumbnailsKeepAspectRatioAndPixelsAreBounded() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1200, 2400, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var image = PatrolExportImages.thumbnail(bytes.toByteArray());
        var decoded = ImageIO.read(new ByteArrayInputStream(image.bytes()));
        assertEquals(90, decoded.getWidth()); assertEquals(180, decoded.getHeight());
        assertEquals(90, image.width()); assertEquals(180, image.height());
        assertEquals("图片格式暂不支持", PatrolExportImages.thumbnail(new byte[]{1, 2}).message());
    }
    @Test void supportedWebpAttachmentsBecomeEmbeddablePng() throws Exception {
        for (var file : java.util.List.of("export-lossless.webp", "export-lossy.webp")) {
            try (var input = getClass().getResourceAsStream("/patrol/" + file)) {
                assertNotNull(input);
                var result = PatrolExportImages.thumbnail(input.readAllBytes());
                var image = ImageIO.read(new ByteArrayInputStream(result.bytes()));
                assertEquals(16, image.getWidth()); assertEquals(32, image.getHeight());
                assertEquals("", result.message());
            }
        }
    }
    @Test void imagePermissionAndAssetFailuresNeverRevealAttachmentIdsOrReadUnpermittedFiles() {
        var attachments = mock(PatrolAttachmentService.class);
        var service = new PatrolExportImages(attachments); var auth = mock(Authentication.class);
        StepVerifier.create(service.read("private").contextWrite(Context.of(Authentication.class, auth)))
                .assertNext(image -> { assertEquals(0, image.bytes().length); assertEquals("无图片查看权限", image.message()); }).verifyComplete();
        verifyNoInteractions(attachments);
        when(auth.hasPermission("patrol-attachment", "query")).thenReturn(true);
        when(attachments.preview("private")).thenReturn(Mono.error(new AccessDenyException.NoStackTrace()));
        when(attachments.preview("expired")).thenReturn(Mono.error(new NotFoundException()));
        StepVerifier.create(service.read("private").contextWrite(Context.of(Authentication.class, auth)))
                .assertNext(image -> assertEquals("无图片查看权限", image.message())).verifyComplete();
        StepVerifier.create(service.read("expired").contextWrite(Context.of(Authentication.class, auth)))
                .assertNext(image -> assertEquals("图片已失效", image.message())).verifyComplete();
    }
    @Test void authorizedPreviewIsDecodedAndInfrastructureFailureDoesNotBecomeEmptyImage() throws Exception {
        var attachments = mock(PatrolAttachmentService.class); var auth = mock(Authentication.class);
        when(auth.hasPermission("patrol-attachment", "query")).thenReturn(true);
        var output = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(12, 20, BufferedImage.TYPE_INT_RGB), "png", output);
        when(attachments.preview("image")).thenReturn(Mono.just(new PatrolAttachmentService.Preview("data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray()))));
        when(attachments.preview("broken")).thenReturn(Mono.error(new IllegalStateException("storage unavailable")));
        var service = new PatrolExportImages(attachments);
        StepVerifier.create(service.read("image").contextWrite(Context.of(Authentication.class, auth)))
                .assertNext(image -> { assertEquals(12, image.width()); assertEquals(20, image.height()); assertTrue(image.bytes().length > 0); }).verifyComplete();
        StepVerifier.create(service.read("broken").contextWrite(Context.of(Authentication.class, auth)))
                .expectError(IllegalStateException.class).verify();
    }
}
