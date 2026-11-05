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
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.Set;

/** 项目章绑定原组织资产；只维护文件引用，更换和移除不得删除可能被单据使用的旧文件。 */
@Service
@RequiredArgsConstructor
public class PropertyProjectSealService {
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
}
