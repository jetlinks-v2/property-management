package org.jetlinks.pro.patrol.service;

import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.NotFoundException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/** 复用受控附件预览及其资产权限；图片缩为导出尺寸，图片解码与编码不占用响应式线程。 */
@Service
@AllArgsConstructor
public class PatrolExportImages {
    private final PatrolAttachmentService attachments;
    public record Image(byte[] bytes, String message, int width, int height) {
        public Image(byte[] bytes, String message) { this(bytes, message, 0, 0); }
    }

    public Mono<Image> read(String id) {
        return ReactiveAuthenticationHolder.get().filter(auth -> auth.hasPermission("patrol-attachment", "query"))
                .switchIfEmpty(Mono.error(AccessDenyException.NoStackTrace::new))
                .then(Mono.defer(() -> attachments.preview(id)))
                .flatMap(preview -> Mono.fromCallable(() -> thumbnail(Base64.getDecoder().decode(
                        preview.dataUrl().substring(preview.dataUrl().indexOf(',') + 1)))).subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(AccessDenyException.class, error -> Mono.just(new Image(new byte[0], "无图片查看权限")))
                .onErrorResume(NotFoundException.class, error -> Mono.just(new Image(new byte[0], "图片已失效")));
    }

    /** 读取尺寸后先抽样解码，避免小文件中的超大像素图片撑满堆内存。 */
    static Image thumbnail(byte[] content) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return new Image(new byte[0], "图片格式暂不支持");
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                var param = reader.getDefaultReadParam();
                int sampling = Math.max(1, Math.max(width / 600, height / 360));
                param.setSourceSubsampling(sampling, sampling, 0, 0);
                var source = reader.read(0, param);
                double scale = Math.min(1d, Math.min(300d / source.getWidth(), 180d / source.getHeight()));
                var image = new BufferedImage(Math.max(1, (int) (source.getWidth() * scale)),
                        Math.max(1, (int) (source.getHeight() * scale)), BufferedImage.TYPE_INT_ARGB);
                var graphics = image.createGraphics();
                try { graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.drawImage(source, 0, 0, image.getWidth(), image.getHeight(), null);
                } finally { graphics.dispose(); source.flush(); }
                try (var output = new ByteArrayOutputStream()) {
                    ImageIO.write(image, "png", output); image.flush(); return new Image(output.toByteArray(), "", image.getWidth(), image.getHeight());
                }
            } finally { reader.dispose(); }
        }
    }
}
