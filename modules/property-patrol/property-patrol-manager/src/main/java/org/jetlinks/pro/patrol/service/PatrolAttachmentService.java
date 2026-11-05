package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity;
import org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity.Purpose;
import org.jetlinks.pro.patrol.integration.PatrolScope;
import org.jetlinks.pro.patrol.integration.PlatformPatrolFiles;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Base64;
import java.util.Collection;
import java.util.UUID;

/** 图片以独立资产绑定到当前应用；引用时同时检查读权限、业务范围和用途。 */
@Service
public class PatrolAttachmentService extends GenericReactiveCrudService<PatrolAttachmentEntity, String> {
    private final PatrolScope scope;
    private final PlatformPatrolFiles files;
    private final TransactionalOperator transaction;

    public PatrolAttachmentService(ReactiveRepository<PatrolAttachmentEntity, String> repository,
                                   PatrolScope scope, PlatformPatrolFiles files, TransactionalOperator transaction) {
        super(repository);
        this.scope = scope;
        this.files = files;
        this.transaction = transaction;
    }

    /** 只返回业务附件编号，不泄露平台内部 URL、凭据或本地路径。 */
    public record Receipt(String id, String name, String mediaType, long length) {}
    public record Preview(String dataUrl) {}

    public Mono<Receipt> upload(FilePart file, Purpose purpose) {
        return scope.current().zipWith(ReactiveAuthenticationHolder.get())
                .flatMap(context -> readImage(file).flatMap(image ->
                        files.upload(UUID.randomUUID() + image.extension(), image.mediaType(), image.bytes())
                                .flatMap(saved -> {
                                    var attachment = new PatrolAttachmentEntity();
                                    attachment.setId(UUID.randomUUID().toString());
                                    attachment.setName("patrol-image" + image.extension());
                                    attachment.setScopeKey(context.getT1().key());
                                    attachment.setCreatorId(context.getT2().getUser().getId());
                                    attachment.setCreateTime(System.currentTimeMillis());
                                    attachment.setPurpose(purpose);
                                    attachment.setPlatformFileId(saved.getId());
                                    attachment.setInternalUrl(saved.getAccessUrl());
                                    attachment.setMediaType(image.mediaType());
                                    attachment.setLength(image.bytes().length);
                                    // 平台文件写入完成后，巡更登记与资产绑定同事务；失败不产生可引用附件。
                                    return PatrolOwnership.assignSelected(attachment).then(insert(attachment))
                                            .thenMany(AssetsHolder.bindAssets(Flux.just(attachment),
                                                    PatrolAssetType.patrolAttachment, PatrolAttachmentEntity::getId))
                                            .single()
                                            .map(PatrolAttachmentService::receipt)
                                            .as(transaction::transactional);
                                })))
                .as(MonoTracer.create("/patrol/attachments/upload"));
    }

    public Mono<Void> assertReferences(Collection<String> ids, Purpose purpose, String scopeKey, String creatorId) {
        return assertReferences(ids, purpose, scopeKey, creatorId, (String) null);
    }

    public Mono<Void> assertReferences(Collection<String> ids, Purpose purpose, String scopeKey, String creatorId,
                                       org.jetlinks.pro.patrol.entity.PatrolConfigEntity owner) {
        return assertReferences(ids, purpose, scopeKey, creatorId,
                owner == null ? null : owner.getProjectId());
    }

    public Mono<Void> assertReferences(Collection<String> ids, Purpose purpose, String scopeKey, String creatorId,
                                       String projectId) {
        return Flux.fromIterable(ids).distinct().concatMap(id -> authorized(id).flatMap(attachment -> {
            if (!scopeKey.equals(attachment.getScopeKey()) || attachment.getPurpose() != purpose
                    || creatorId != null && !creatorId.equals(attachment.getCreatorId()))
                return Mono.error(new AccessDenyException.NoStackTrace());
            if (projectId == null) return Mono.just(attachment);
            return org.jetlinks.pro.patrol.integration.PatrolProjectContext.current().flatMap(context ->
                    context.canReference(projectId, attachment.getProjectId())
                    ? Mono.just(attachment) : Mono.error(new AccessDenyException.NoStackTrace()));
        })).then();
    }

    public Mono<Preview> preview(String id) {
        // 受控、限长的预览响应保持在当前认证请求内，不把内部下载 URL 交给浏览器。
        // 平台访问地址可能是 /file/... 相对路径；下载命令支持文件 ID，避免被误当作本机文件路径。
        return authorized(id).flatMap(attachment -> files.read(attachment.getPlatformFileId())
                .map(bytes -> new Preview("data:" + attachment.getMediaType() + ";base64,"
                        + Base64.getEncoder().encodeToString(bytes))));
    }

    private Mono<PatrolAttachmentEntity> authorized(String id) {
        return AssetsHolder.assertPermission(PatrolAssetType.patrolAttachment, id, CrudAssetPermission.read)
                .then(findById(id).switchIfEmpty(Mono.error(NotFoundException::new)));
    }

    private record Image(byte[] bytes, String mediaType, String extension) {}

    private Mono<Image> readImage(FilePart file) {
        return DataBufferUtils.join(file.content(), PlatformPatrolFiles.MAX_IMAGE_BYTES)
                .switchIfEmpty(Mono.error(new BusinessException("error.patrol.file.type")))
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    try { buffer.read(bytes); } finally { DataBufferUtils.release(buffer); }
                    if (matches(bytes, 0, 0xff, 0xd8, 0xff)) return new Image(bytes, "image/jpeg", ".jpg");
                    if (matches(bytes, 0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
                        return new Image(bytes, "image/png", ".png");
                    if (matches(bytes, 0, 0x52, 0x49, 0x46, 0x46) && matches(bytes, 8, 0x57, 0x45, 0x42, 0x50))
                        return new Image(bytes, "image/webp", ".webp");
                    throw new BusinessException("error.patrol.file.type");
                })
                .onErrorMap(org.springframework.core.io.buffer.DataBufferLimitException.class,
                        error -> new BusinessException("error.patrol.file.size"));
    }

    private static boolean matches(byte[] bytes, int offset, int... signature) {
        if (bytes.length < offset + signature.length) return false;
        for (int i = 0; i < signature.length; i++)
            if ((bytes[offset + i] & 0xff) != signature[i]) return false;
        return true;
    }

    private static Receipt receipt(PatrolAttachmentEntity entity) {
        return new Receipt(entity.getId(), entity.getName(), entity.getMediaType(), entity.getLength());
    }
}
