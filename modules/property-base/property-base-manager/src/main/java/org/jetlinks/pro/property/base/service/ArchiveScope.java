package org.jetlinks.pro.property.base.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.entity.*;
import org.jetlinks.pro.property.base.organization.OrganizationType;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/** 先校验组织归属，再按 SaaS space 资产授权；叶子判定始终来自完整源树。 */
@Service
@RequiredArgsConstructor
public class ArchiveScope {
    public static final AssetType SPACE = AssetType.of("space");
    public static final AssetType ORGANIZATION = AssetType.of("organization");
    private final ReactiveRepository<PropertyOrganizationEntity, String> organizations;
    private final ReactiveRepository<PropertySpaceBindingEntity, String> bindings;
    private final ReactiveRepository<PropertySpaceEntity, String> spaces;

    /** 沿完整镜像的父链派生展示字段；权限裁剪后仅返回允许访问的空间记录。 */
    static void attachSpaceInformation(List<PropertySpaceEntity> nodes) {
        var index = new HashMap<String, PropertySpaceEntity>();
        nodes.forEach(node -> index.put(node.getId(), node));
        for (var node : nodes) {
            var segments = new LinkedList<PropertySpaceEntity.SpaceSegment>();
            var seen = new HashSet<String>();
            var current = node;
            while (current != null && seen.add(current.getId())) {
                segments.addFirst(
                        new PropertySpaceEntity.SpaceSegment(
                                current.getId(),
                                current.getName(),
                                current.getSpaceTypeId(),
                                current.getSpaceTypeName()));
                current = current.getParentId() == null ? null : index.get(current.getParentId());
            }
            node.setSpaceSegments(List.copyOf(segments));
        }
    }

    public Mono<Authentication> authenticated() {
        return Authentication.currentReactive()
                .switchIfEmpty(Mono.error(UnAuthorizedException.NoStackTrace::new));
    }

    public Mono<PropertyOrganizationEntity> project(String id, CrudAssetPermission permission) {
        if (id == null || id.isBlank()) return Mono.error(error("project_required", 400));
        return authenticated()
                .then(AssetsHolder.assertPermission(ORGANIZATION, id, permission))
                .then(organizations.findById(id))
                .filter(
                        o ->
                                !Boolean.TRUE.equals(o.getDeleted())
                                        && o.getOrganizationType() == OrganizationType.project)
                .switchIfEmpty(Mono.error(error("project_not_found", 404)));
    }

    /** 事务内锁住项目行，串行化同项目的房屋和客户写入，不更改来源或审计字段。 */
    public Mono<Void> lock(String projectId) {
        var query = new org.hswebframework.web.api.crud.entity.QueryParamEntity();
        query.setForUpdate(true);
        return organizations
                .createQuery()
                .setParam(query)
                .where("id", projectId)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("project_not_found", 404)))
                .then();
    }

    public Mono<List<PropertySpaceEntity>> spaces(
            String projectId, String spaceId, CrudAssetPermission permission) {
        return project(projectId, permission)
                .thenMany(
                        bindings.createQuery()
                                .where("organizationId", projectId)
                                .and("deleted", false)
                                .fetch())
                .map(PropertySpaceBindingEntity::getId)
                .collectList()
                .flatMap(
                        roots ->
                                roots.isEmpty()
                                        ? Mono.just(List.<PropertySpaceEntity>of())
                                        : spaces.createQuery()
                                                .in("rootId", roots)
                                                .and("deleted", false)
                                                .fetch()
                                                .take(20001)
                                                .collectList())
                .flatMap(
                        all -> {
                            if (all.size() > 20000) return Mono.error(error("too_many", 400));
                            attachSpaceInformation(all);
                            Set<String> selected = new HashSet<>();
                            if (spaceId != null && !spaceId.isBlank()) {
                                if (all.stream().noneMatch(e -> e.getId().equals(spaceId)))
                                    return Mono.error(error("space_not_found", 404));
                                selected.add(spaceId);
                                boolean changed;
                                do {
                                    changed = false;
                                    for (var node : all)
                                        if (selected.contains(node.getParentId()))
                                            changed |= selected.add(node.getId());
                                } while (changed);
                            }
                            Mono<Void> check =
                                    spaceId == null || spaceId.isBlank()
                                            ? Mono.empty()
                                            : AssetsHolder.assertPermission(
                                                    SPACE, spaceId, permission);
                            return check.thenMany(
                                            AssetsHolder.filterAssets(
                                                    Flux.fromIterable(all)
                                                            .filter(
                                                                    e ->
                                                                            selected.isEmpty()
                                                                                    || selected
                                                                                            .contains(
                                                                                                    e
                                                                                                            .getId())),
                                                    SPACE,
                                                    PropertySpaceEntity::getId,
                                                    permission))
                                    .collectList();
                        });
    }

    public Mono<PropertySpaceEntity> leaf(
            String projectId, String spaceId, CrudAssetPermission permission) {
        return spaces(projectId, spaceId, permission)
                .flatMapMany(Flux::fromIterable)
                .filter(e -> e.getId().equals(spaceId) && Boolean.TRUE.equals(e.getLeaf()))
                .next()
                .switchIfEmpty(Mono.error(error("space_not_leaf", 409)));
    }

    public static BusinessException error(String suffix, int status) {
        return new BusinessException.NoStackTrace("error.property_archive_" + suffix, status);
    }
}
