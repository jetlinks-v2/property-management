package org.jetlinks.pro.property.base.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.io.file.FileManager;
import org.jetlinks.pro.property.base.entity.PropertyOrganizationEntity;
import org.jetlinks.pro.property.base.organization.OrganizationType;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.jetlinks.pro.property.base.organization.PropertyProjectSeal;
import reactor.core.scheduler.Schedulers;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.Set;

/** 项目章绑定原组织资产；只维护文件引用，更换和移除不得删除可能被单据使用的旧文件。 */
@Service
@RequiredArgsConstructor
public class PropertyProjectSealService {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    // 保留既有文件引用接口的构造契约；上传及读取沿公共存储能力惰性解析。
    @Autowired
    private ObjectProvider<PropertyOrganizationService> organizationServices;
    @Autowired
    private ObjectProvider<PropertyProjectSealStorage> storageProviders;
    private static final long MAX_SIZE = 5 * 1024 * 1024;
    private final ReactiveRepository<PropertyOrganizationEntity, String> organizations;
    private final FileManager files;

    @Transactional
    public Mono<PropertyOrganizationEntity> update(String id, String fileId, long version) {
        return Authentication.currentReactive()
                .switchIfEmpty(Mono.error(UnAuthorizedException.NoStackTrace::new))
                .flatMap(auth -> AssetsHolder.assertPermission(
                                PropertyOrganizationService.ORGANIZATION, id, CrudAssetPermission.save)
                        .then(project(id))
                        .flatMap(project -> {
                            if (project.getVersion() != version) {
                                return Mono.error(error("version_conflict", 409));
                            }
                            return validateFile(fileId).then(save(id, fileId, version, auth));
                        }));
    }

    private Mono<PropertyOrganizationEntity> project(String id) {
        var query = new QueryParamEntity();
        query.setForUpdate(true);
        return organizations.createQuery().setParam(query)
                .where(PropertyOrganizationEntity::getId, id)
                .and(PropertyOrganizationEntity::getDeleted, false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("not_found", 404)))
                .flatMap(project -> project.getOrganizationType() == OrganizationType.project
                        ? Mono.just(project)
                        : Mono.error(error("seal_project_required", 409)));
    }

    private Mono<Void> validateFile(String fileId) {
        if (fileId == null) return Mono.empty();
        if (fileId.isBlank() || fileId.length() > 64) {
            return Mono.error(error("seal_file_invalid", 400));
        }
        // 文件类型及长度使用平台元数据，禁止相信浏览器随保存请求传入的声明。
        return AssetsHolder.assertPermission(AssetType.of("file"), fileId, CrudAssetPermission.read)
                .then(Mono.defer(() -> files.getFile(fileId)))
                .switchIfEmpty(Mono.error(error("seal_file_invalid", 400)))
                .flatMap(file -> {
                    String type = file.mediaType().toString();
                    return Set.of("image/png", "image/jpeg").contains(type)
                                    && file.getLength() > 0 && file.getLength() <= MAX_SIZE
                            ? Mono.<Void>empty()
                            : Mono.error(error("seal_file_invalid", 400));
                });
    }

    private Mono<PropertyOrganizationEntity> save(
            String id, String fileId, long version, Authentication auth) {
        var update = organizations.createUpdate()
                .set(PropertyOrganizationEntity::getVersion, version + 1)
                .set(PropertyOrganizationEntity::getModifierId, auth.getUser().getId())
                .set(PropertyOrganizationEntity::getModifierName, auth.getUser().getUsername())
                .set(PropertyOrganizationEntity::getModifyTime, System.currentTimeMillis());
        if (fileId == null) update.setNull(PropertyOrganizationEntity::getProjectSealFileId);
        else update.set(PropertyOrganizationEntity::getProjectSealFileId, fileId);
        update.setNull(PropertyOrganizationEntity::getProjectSealMediaType);
        // 与类型标注共享版本；条件同时防止覆盖并发修改或复活被来源删除的组织。
        return update.where(PropertyOrganizationEntity::getId, id)
                .and(PropertyOrganizationEntity::getVersion, version)
                .and(PropertyOrganizationEntity::getDeleted, false)
                .and(PropertyOrganizationEntity::getOrganizationType, OrganizationType.project)
                .execute()
                .flatMap(count -> count == 1
                        ? organizations.findById(id)
                        : Mono.error(error("version_conflict", 409)));
    }

    private static BusinessException error(String code, int status) {
        return new BusinessException.NoStackTrace("error.property_org_" + code, status);
    }

    /** 上传与只读命令复用项目资产权限；既有文件引用维护入口继续保留。 */
    public Mono<PropertyOrganizationEntity> upload(String id, long version, FilePart file) {
        return organizationServices.getObject().sealWritable(id, version)
            .then(DataBufferUtils.join(file.content(), MAX_BYTES))
            .onErrorMap(org.springframework.core.io.buffer.DataBufferLimitException.class, error -> ArchiveScope.error("seal_invalid", 400))
            .switchIfEmpty(Mono.error(ArchiveScope.error("seal_invalid", 400)))
            .map(PropertyProjectSealStorage::bytes)
            .publishOn(Schedulers.boundedElastic()).flatMap(bytes -> {
                String type = imageType(bytes);
                return storageProviders.getObject().save("project-seal." + (type.equals("image/png") ? "png" : "jpg"), bytes)
                    .flatMap(fileId -> organizationServices.getObject().updateSeal(id, version, fileId, type)
                        // 上传已确认成功但数据库拒绝时回收本次文件，不触碰原章。
                        .onErrorResume(error -> storageProviders.getObject().delete(fileId).onErrorComplete().then(Mono.error(error))));
            });
    }
    public Mono<PropertyOrganizationEntity> remove(String id, long version) {
        return organizationServices.getObject().updateSeal(id, version, null, null);
    }
    public Mono<PropertyProjectSeal> read(String id) {
        return organizationServices.getObject().detail(id).flatMap(row -> {
            if (row.getOrganizationType() != OrganizationType.project)
                return Mono.error(ArchiveScope.error("seal_project_only", 409));
            if (row.getProjectSealFileId() == null) return Mono.empty();
            return storageProviders.getObject().read(row.getProjectSealFileId()).publishOn(Schedulers.boundedElastic())
                .map(bytes -> new PropertyProjectSeal(id, imageType(bytes), bytes));
        });
    }
    /** 先验证真实格式与尺寸再解码，拒绝扩展名伪装和超大像素，运行在boundedElastic。 */
    static String imageType(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw ArchiveScope.error("seal_invalid", 400);
        try (var input = javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            var readers = javax.imageio.ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw ArchiveScope.error("seal_invalid", 400);
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName();
                if (!(format.equalsIgnoreCase("png") || format.equalsIgnoreCase("jpeg"))
                    || reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0
                    || reader.getWidth(0) > 4096 || reader.getHeight(0) > 4096 || reader.read(0) == null)
                    throw ArchiveScope.error("seal_invalid", 400);
                return format.equalsIgnoreCase("png") ? "image/png" : "image/jpeg";
            } finally { reader.dispose(); }
        } catch (java.io.IOException | IllegalArgumentException error) { throw ArchiveScope.error("seal_invalid", 400); }
    }
}
