package org.jetlinks.pro.property.base.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.entity.PropertyOrganizationEntity;
import org.jetlinks.pro.property.base.entity.PropertySpaceBindingEntity;
import org.jetlinks.pro.property.base.organization.OrganizationType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.*;

/** 仅维护组织镜像的业务类型；读写权限均关联 SaaS 原 organization 资产。 */
@Service
@RequiredArgsConstructor
public class PropertyOrganizationService {
    // 来自 SaaS OrganizationAssetType.type_organization；不依赖 authentication-manager 实现模块。
    static final AssetType ORGANIZATION = AssetType.of("organization");
    private final ReactiveRepository<PropertyOrganizationEntity, String> repository;
    private final ReactiveRepository<PropertySpaceBindingEntity, String> bindings;

    public Mono<List<PropertyOrganizationEntity>> tree() {
        return authenticated()
                .thenMany(
                        AssetsHolder.filterAssets(
                                repository
                                        .createQuery()
                                        .where(PropertyOrganizationEntity::getDeleted, false)
                                        .fetch(),
                                ORGANIZATION,
                                PropertyOrganizationEntity::getId,
                                CrudAssetPermission.read))
                .collectList()
                .map(this::toTree);
    }

    public Mono<PropertyOrganizationEntity> detail(String id) {
        return assertAccess(id, CrudAssetPermission.read).then(active(id));
    }

    @Transactional
    public Mono<PropertyOrganizationEntity> updateType(
            String id, OrganizationType type, long version) {
        return assertAccess(id, CrudAssetPermission.save)
                .then(lock(id))
                .then(active(id))
                .flatMap(old -> old.getOrganizationType() == type ? Mono.just(old) : bindings.createQuery()
                        .where("organizationId", id).and("deleted", false).count()
                        .flatMap(count -> count == 0 ? Mono.just(old) : Mono.error(ArchiveScope.error("organization_in_use", 409))))
                .then(authenticated())
                .flatMap(
                        auth -> {
                            // 只更新自有字段，版本 CAS 与 deleted 条件防止并发覆盖或复活已删除的组织。
                            var update =
                                    repository
                                            .createUpdate()
                                            .set(
                                                    PropertyOrganizationEntity::getVersion,
                                                    version + 1)
                                            .set(
                                                    PropertyOrganizationEntity::getModifierId,
                                                    auth.getUser().getId())
                                            .set(
                                                    PropertyOrganizationEntity::getModifierName,
                                                    auth.getUser().getUsername())
                                            .set(
                                                    PropertyOrganizationEntity::getModifyTime,
                                                    System.currentTimeMillis());
                            if (type == null)
                                update.setNull(PropertyOrganizationEntity::getOrganizationType);
                            else update.set(PropertyOrganizationEntity::getOrganizationType, type);
                            return update.where(PropertyOrganizationEntity::getId, id)
                                    .and(PropertyOrganizationEntity::getVersion, version)
                                    .and(PropertyOrganizationEntity::getDeleted, false)
                                    .execute()
                                    .flatMap(
                                            count ->
                                                    count == 1
                                                            ? active(id)
                                                            : Mono.error(
                                                                    new BusinessException
                                                                            .NoStackTrace(
                                                                            "error.property_org_version_conflict",
                                                                            409)));
                        });
    }

    private Mono<Void> assertAccess(String id, CrudAssetPermission permission) {
        return authenticated().then(AssetsHolder.assertPermission(ORGANIZATION, id, permission));
    }
    private Mono<Void> lock(String id) {
        var query = new org.hswebframework.web.api.crud.entity.QueryParamEntity(); query.setForUpdate(true);
        return repository.createQuery().setParam(query).where("id", id).and("deleted", false).fetchOne()
                .switchIfEmpty(Mono.error(ArchiveScope.error("project_not_found", 404))).then();
    }

    private Mono<Authentication> authenticated() {
        // 平台管理员可以没有普通 Holder，空 Holder 由框架解释为不受限；匿名仍必须拒绝。
        return Authentication.currentReactive()
                .switchIfEmpty(Mono.error(UnAuthorizedException.NoStackTrace::new));
    }

    private Mono<PropertyOrganizationEntity> active(String id) {
        return repository
                .findById(id)
                .filter(entity -> !Boolean.TRUE.equals(entity.getDeleted()))
                .switchIfEmpty(
                        Mono.error(
                                new BusinessException.NoStackTrace(
                                        "error.property_org_not_found", 404)));
    }

    private List<PropertyOrganizationEntity> toTree(List<PropertyOrganizationEntity> entities) {
        entities.sort(
                Comparator.comparing(
                                PropertyOrganizationEntity::getSortIndex,
                                Comparator.nullsLast(Long::compareTo))
                        .thenComparing(PropertyOrganizationEntity::getId));
        var byId = new LinkedHashMap<String, PropertyOrganizationEntity>();
        entities.forEach(
                entity -> {
                    entity.setChildren(new ArrayList<>());
                    byId.put(entity.getId(), entity);
                });
        var roots = new ArrayList<PropertyOrganizationEntity>();
        entities.forEach(
                entity -> {
                    var parent = byId.get(entity.getParentId());
                    // 父级不可见时，直接作为可见根节点展示，不查询或补出越权父级名称。
                    if (parent == null) roots.add(entity);
                    else parent.getChildren().add(entity);
                });
        return roots;
    }
}
