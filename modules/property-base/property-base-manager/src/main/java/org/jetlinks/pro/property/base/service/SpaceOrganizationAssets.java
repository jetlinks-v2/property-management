package org.jetlinks.pro.property.base.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.assets.AssetBindManager;
import org.jetlinks.pro.assets.AssetBindRequest;
import org.jetlinks.pro.assets.AssetRelation;
import org.jetlinks.pro.assets.AssetUnbindRequest;
import org.jetlinks.pro.authorize.OrgDimensionType;
import org.jetlinks.pro.property.base.entity.PropertySpaceBindingEntity;
import org.jetlinks.pro.property.base.entity.PropertySpaceEntity;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/** 将经过业务校验的项目归属登记到统一资产体系；不绑定给同步操作者，也不放宽角色权限。 */
@Component
@RequiredArgsConstructor
public class SpaceOrganizationAssets {
    private final ReactiveRepository<PropertySpaceEntity, String> spaces;
    private final ReactiveRepository<PropertySpaceBindingEntity, String> bindings;
    private final AssetBindManager assets;

    Mono<Void> change(String rootId, String previous, String next) {
        return spaces.createQuery()
                .where("rootId", rootId)
                .fetch()
                .collectList()
                .flatMap(
                        nodes -> {
                            var removed = new LinkedHashMap<String, List<String>>();
                            var added = new LinkedHashMap<String, List<String>>();
                            for (var node : nodes) {
                                String target =
                                        Boolean.TRUE.equals(node.getDeleted()) ? null : next;
                                collect(node.getId(), previous, target, removed, added);
                            }
                            return apply(removed, added);
                        });
    }

    Mono<Void> synchronize(
            Map<String, PropertySpaceEntity> before, Map<String, PropertySpaceEntity> after) {
        return bindings.createQuery()
                .where("deleted", false)
                .notNull("organizationId")
                .fetch()
                .collectMap(
                        PropertySpaceBindingEntity::getId,
                        PropertySpaceBindingEntity::getOrganizationId)
                .flatMap(
                        organizations -> {
                            var removed = new LinkedHashMap<String, List<String>>();
                            var added = new LinkedHashMap<String, List<String>>();
                            var ids = new LinkedHashSet<>(before.keySet());
                            ids.addAll(after.keySet());
                            for (String id : ids) {
                                var old = before.get(id);
                                var current = after.get(id);
                                String previous =
                                        old == null ? null : organizations.get(old.getRootId());
                                String next =
                                        current == null || Boolean.TRUE.equals(current.getDeleted())
                                                ? null
                                                : organizations.get(current.getRootId());
                                collect(id, previous, next, removed, added);
                            }
                            return apply(removed, added);
                        });
    }

    private static void collect(
            String id,
            String previous,
            String next,
            Map<String, List<String>> removed,
            Map<String, List<String>> added) {
        if (previous != null && !Objects.equals(previous, next))
            removed.computeIfAbsent(previous, ignored -> new ArrayList<>()).add(id);
        // 包括未发生结构变化的节点，幂等补齐旧版本遗漏的组织资产归属。
        if (next != null) added.computeIfAbsent(next, ignored -> new ArrayList<>()).add(id);
    }

    private Mono<Void> apply(Map<String, List<String>> removed, Map<String, List<String>> added) {
        var unbinds =
                Flux.fromIterable(removed.entrySet())
                        .concatMap(
                                entry ->
                                        Flux.fromIterable(entry.getValue())
                                                .buffer(500)
                                                .map(
                                                        ids ->
                                                                new AssetUnbindRequest(
                                                                        OrgDimensionType.org
                                                                                .getId(),
                                                                        entry.getKey(),
                                                                        ArchiveScope.SPACE.getId(),
                                                                        ids,
                                                                        AssetBindManager
                                                                                .SOURCE_AUTO)));
        var binds =
                Flux.fromIterable(added.entrySet())
                        .concatMap(
                                entry ->
                                        Flux.fromIterable(entry.getValue())
                                                .buffer(500)
                                                .map(
                                                        ids -> {
                                                            var request = new AssetBindRequest();
                                                            request.setTargetType(
                                                                    OrgDimensionType.org.getId());
                                                            request.setTargetId(entry.getKey());
                                                            request.setAssetType(
                                                                    ArchiveScope.SPACE.getId());
                                                            request.setAssetIdList(ids);
                                                            request.setRelation(
                                                                    AssetRelation.owner);
                                                            request.setAllPermission(true);
                                                            return request;
                                                        }));
        // 只维护本次业务关联的组织和空间，不解绑其他维度或该组织的其他资产。
        return assets.unbindAssets(unbinds).then(Mono.defer(() -> assets.bindAssets(binds)));
    }
}
