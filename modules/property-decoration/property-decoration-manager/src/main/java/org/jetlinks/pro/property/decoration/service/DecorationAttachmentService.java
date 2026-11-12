package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.pro.property.decoration.entity.DecorationAttachmentEntity;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import static org.jetlinks.pro.property.decoration.service.DecorationRules.*;

/** 私有文件回执；客户端只能提交回执ID，不能把外部URL或任意平台文件ID加入申请。 */
@Service
@RequiredArgsConstructor
public class DecorationAttachmentService {
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "heic", "pdf", "doc", "docx", "xls", "xlsx", "gif", "bmp", "dwg", "dxf");
    private final DecorationScope scope;
    private final DecorationAttachmentRepository receipts;
    private final DecorationApplicationRepository applications;
    private final DecorationFileStorage files;
    public record Receipt(String id, String name, long size, String contentType) {}

    public Mono<Receipt> upload(String projectId, String decorationId, String purpose, FilePart part) {
        require(Set.of("draft", "template", "permit").contains(purpose), "invalid_attachment");
        String name = java.nio.file.Paths.get(part.filename()).getFileName().toString();
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        require(EXTENSIONS.contains(extension) && name.length() <= 512, "invalid_attachment");
        if ("permit".equals(purpose)) require(decorationId != null && "png".equals(extension), "invalid_attachment");
        return scope.current().flatMap(ctx -> scope.project(projectId)
            .then("template".equals(purpose) ? scope.employee().then(Mono.fromRunnable(() -> require(ctx.authentication().hasPermission("decoration-configuration", "save"), "forbidden"))) : Mono.empty())
            .then("permit".equals(purpose) ? scope.employee().then(Mono.fromRunnable(() -> require(ctx.authentication().hasPermission("decoration-application", "print"), "forbidden"))) : Mono.empty())
            .then(decorationId == null ? Mono.empty() : application(decorationId, ctx).doOnNext(a -> require(a.getProjectId().equals(projectId), "invalid_attachment")).then())
            .then(Mono.defer(() -> save(ctx, projectId, decorationId, purpose, name, extension, part))));
    }
    private Mono<Receipt> save(DecorationScope.Context ctx, String project, String application, String purpose, String name, String extension, FilePart part) {
        long[] bytes = {0};
        Flux<DataBuffer> content = part.content().<DataBuffer>handle((buffer, sink) -> {
            bytes[0] += buffer.readableByteCount();
            if (bytes[0] > MAX_BYTES) { DataBufferUtils.release(buffer); sink.error(error("attachment_too_large", 413)); }
            else sink.next(buffer);
        }).doOnDiscard(DataBuffer.class, DataBufferUtils::release);
        if ("permit".equals(purpose)) {
            // 专用图片先限量聚合并验证实际PNG，在非事件线程读取尺寸，避免只凭扩展名归档。
            content = DataBufferUtils.join(content, (int) MAX_BYTES).publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMapMany(buffer -> {
                    byte[] image = new byte[buffer.readableByteCount()];
                    try { buffer.read(image); } finally { DataBufferUtils.release(buffer); }
                    validatePermitImage(image);
                    return Flux.just(org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance.wrap(image));
                });
        }
        // 不使用 publicAccess；文件内容始终通过本服务的有权回执读取。
        return files.save("decoration-" + IDGenerator.MD5.generate() + "." + extension, content).flatMap(file -> {
            var row = new DecorationAttachmentEntity(); row.setId(IDGenerator.MD5.generate()); row.setFileId(file);
            row.setApplicationId(ctx.applicationId()); row.setTenantId(ctx.tenantId()); row.setProjectId(project); row.setCreatorId(ctx.userId());
            row.setDecorationId(application); row.setPurpose(purpose); row.setName(name); row.setSize(bytes[0]);
            row.setContentType("pdf".equals(extension) ? "application/pdf" : Set.of("jpg","jpeg","png","webp").contains(extension) ? "image/" + ("jpg".equals(extension) ? "jpeg" : extension) : "application/octet-stream");
            row.setCreateTime(System.currentTimeMillis());
            return receipts.insert(row).thenReturn(new Receipt(row.getId(), name, bytes[0], row.getContentType()))
                .onErrorResume(e -> files.delete(file).then(Mono.error(e)));
        });
    }
    static void validatePermitImage(byte[] image) {
        require(image.length > 8 && image[0] == (byte) 0x89 && image[1] == 0x50 && image[2] == 0x4e && image[3] == 0x47
            && image[4] == 13 && image[5] == 10 && image[6] == 26 && image[7] == 10, "invalid_attachment");
        try (var input = javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(image))) {
            var readers = javax.imageio.ImageIO.getImageReaders(input);
            require(readers.hasNext(), "invalid_attachment");
            var reader = readers.next();
            try {
                reader.setInput(input);
                require(reader.getWidth(0) > 0 && reader.getHeight(0) > 0 && reader.getWidth(0) <= 4096 && reader.getHeight(0) <= 4096, "invalid_attachment");
                require(reader.read(0) != null, "invalid_attachment");
            } finally { reader.dispose(); }
        } catch (java.io.IOException failure) { throw error("invalid_attachment", 400); }
    }
    /** 打印图片必须是当前操作者为本申请上传的专用回执，不接受资料图片或外部URL。 */
    Mono<Void> validatePermitSnapshot(String id, org.jetlinks.pro.property.decoration.entity.DecorationApplicationEntity application, DecorationScope.Context ctx) {
        return receipts.findById(id).filter(row -> row.getApplicationId().equals(ctx.applicationId())
            && row.getTenantId().equals(ctx.tenantId()) && row.getProjectId().equals(application.getProjectId())
            && application.getId().equals(row.getDecorationId()) && row.getCreatorId().equals(ctx.userId())
            && "permit".equals(row.getPurpose()) && "image/png".equals(row.getContentType()) && row.getSize() > 0)
            .switchIfEmpty(Mono.error(error("invalid_attachment", 403))).then();
    }
    public Mono<Void> validate(List<String> ids, String project, String application, DecorationScope.Context ctx, boolean templates) {
        require(ids.size() <= 5000, "too_many");
        return Flux.fromIterable(ids).distinct().concatMap(id -> receipts.findById(id)
            .filter(r -> r.getApplicationId().equals(ctx.applicationId()) && r.getTenantId().equals(ctx.tenantId()) && r.getProjectId().equals(project)
                && (templates && "template".equals(r.getPurpose()) || Objects.equals(r.getDecorationId(), application) && application != null || r.getDecorationId() == null && r.getCreatorId().equals(ctx.userId()))
                && (!templates || "template".equals(r.getPurpose())))
            .switchIfEmpty(Mono.error(error("invalid_attachment", 403)))).then();
    }
    public Mono<Void> consume(List<String> ids, String application, DecorationScope.Context ctx) {
        if (ids.isEmpty()) return Mono.empty();
        return receipts.createUpdate().set("decorationId", application).in("id", ids).isNull("decorationId")
            .and("applicationId", ctx.applicationId()).and("tenantId", ctx.tenantId()).and("creatorId", ctx.userId()).execute()
            .then(receipts.createQuery().in("id", ids).and("decorationId", application).count())
            .doOnNext(count -> require(count == ids.stream().distinct().count(), "invalid_attachment")).then();
    }
    /** 仅供导入失败补偿，ids由本次内部上传产生，尚未返回客户端；已提交批次必须先从调用方排除。 */
    Mono<Void> discardImportTemplates(List<String> ids, DecorationScope.Context ctx) {
        return Flux.fromIterable(ids).concatMap(id -> receipts.findById(id)
            .filter(row -> row.getApplicationId().equals(ctx.applicationId()) && row.getTenantId().equals(ctx.tenantId())
                && row.getCreatorId().equals(ctx.userId()) && "template".equals(row.getPurpose()) && row.getDecorationId() == null)
            .flatMap(row -> files.delete(row.getFileId()).then(receipts.deleteById(row.getId())))).then();
    }
    /** 元信息和正文复用下载的应用、租户、项目及申请访问校验。 */
    private Mono<DecorationAttachmentEntity> authorized(String id) {
        return scope.current().flatMap(ctx -> scope.query(QueryParamEntity.newQuery().where("id", id).getParam(), ctx)
            .flatMap(q -> receipts.createQuery().setParam(q).fetchOne()).switchIfEmpty(Mono.error(error("invalid_attachment", 404)))
            .flatMap(row -> authorize(row, ctx).thenReturn(row)));
    }
    public Mono<Receipt> metadata(String id) {
        return authorized(id).map(row -> new Receipt(row.getId(), row.getName(), row.getSize(), row.getContentType()));
    }
    /** 二进制Word在装修服务内提取可阅读正文，不向第三方公开私有文件。解析移到专用工作线程。 */
    public Mono<String> wordText(String id) {
        return authorized(id).flatMap(row -> {
            require(row.getName().toLowerCase(Locale.ROOT).endsWith(".doc"), "invalid_attachment");
            return DataBufferUtils.join(files.read(row.getFileId()), (int) MAX_BYTES)
                .map(buffer -> { try { byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); return bytes; }
                    finally { DataBufferUtils.release(buffer); } })
                .publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .map(DecorationWordPreview::text);
        });
    }
    public Mono<ResponseEntity<Flux<DataBuffer>>> download(String id) {
        return authorized(id).map(row -> ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(row.getContentType()))
            .header("X-Content-Type-Options", "nosniff")
            .header("Cache-Control", "private, no-store")
            .header("Content-Disposition", ContentDisposition.inline().filename(row.getName(), StandardCharsets.UTF_8).build().toString())
            .body(files.read(row.getFileId())));
    }
    private Mono<Void> authorize(DecorationAttachmentEntity row, DecorationScope.Context ctx) {
        if ("template".equals(row.getPurpose()) || row.getCreatorId().equals(ctx.userId())) return Mono.empty();
        require(row.getDecorationId() != null, "forbidden");
        return application(row.getDecorationId(), ctx).then();
    }
    private Mono<org.jetlinks.pro.property.decoration.entity.DecorationApplicationEntity> application(String id, DecorationScope.Context ctx) {
        return scope.query(QueryParamEntity.newQuery().where("id", id).getParam(), ctx)
            .flatMap(q -> applications.createQuery().setParam(q).and("deleted", false).fetchOne())
            .filter(row -> row.getOwnerUserId().equals(ctx.userId()) || ctx.authentication().hasPermission("decoration-application", "query"))
            .switchIfEmpty(Mono.error(error("forbidden", 403)));
    }
}
