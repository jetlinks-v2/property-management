package org.jetlinks.pro.property.decoration.excel;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.nio.file.Path;

/** 已通过工作簿校验的图片复用私有附件上传流程，不写入本机临时文件。 */
public final class DecorationImportImagePart implements FilePart {
    private final DecorationCheckWorkbook.Image image;
    public DecorationImportImagePart(DecorationCheckWorkbook.Image image) { this.image = image; }
    @Override public String filename() { return "inspection-example." + image.extension(); }
    @Override public String name() { return "file"; }
    @Override public HttpHeaders headers() {
        var headers = new HttpHeaders(); headers.setContentType("png".equals(image.extension()) ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG);
        headers.setContentDispositionFormData(name(), filename()); return headers;
    }
    @Override public Flux<DataBuffer> content() { return Flux.defer(() -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(image.bytes()))); }
    @Override public Mono<Void> transferTo(Path path) { return Mono.error(new UnsupportedOperationException("In-memory import image")); }
}
