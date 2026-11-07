package org.jetlinks.pro.property.base.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.base.entity.PropertySpaceEntity;
import org.jetlinks.pro.property.base.entity.SpaceSyncStateEntity;
import org.jetlinks.pro.property.base.space.SpaceSyncResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Map;

/** 校验租约后锁定状态行，镜像增改、软删除及统计在同一事务内提交，不触碰本地业务表。 */
@Service
@RequiredArgsConstructor
public class SpaceSyncWriter {
    private final ReactiveRepository<PropertySpaceEntity, String> spaces;
    private final SpaceOrganizationAssets organizationAssets;
    private final ReactiveRepository<SpaceSyncStateEntity, String> states;

    @Transactional
    public Mono<SpaceSyncResult> apply(String owner, Map<String, PropertySpaceEntity> snapshot) {
        long now = System.currentTimeMillis();
        return states.createUpdate()
                .set(SpaceSyncStateEntity::getLeaseOwner, owner)
                .where(SpaceSyncStateEntity::getId, SpaceSyncService.STATE_ID)
                .and(SpaceSyncStateEntity::getLeaseOwner, owner)
                .gt(SpaceSyncStateEntity::getLeaseUntil, now)
                .execute()
                .flatMap(
                        locked ->
                                locked == 1
                                        ? spaces.createQuery()
                                                .fetch()
                                                .collectMap(PropertySpaceEntity::getId)
                                        : Mono.error(
                                                new BusinessException.NoStackTrace(
                                                        "error.property_space_sync_busy", 409)))
                .flatMap(
                        existing ->
                                lockRoots(existing)
                                        .then(
                                                Mono.defer(
                                                        () ->
                                                                applySnapshot(
                                                                        owner, snapshot, existing,
                                                                        now))));
    }

    private Mono<Void> lockRoots(Map<String, PropertySpaceEntity> existing) {
        var ids =
                existing.values().stream()
                        .filter(node -> node.getParentId() == null)
                        .map(PropertySpaceEntity::getId)
                        .sorted()
                        .toList();
        if (ids.isEmpty()) return Mono.empty();
        var query = new org.hswebframework.web.api.crud.entity.QueryParamEntity();
        query.setForUpdate(true);
        query.setPaging(false);
        query.orderBy("id");
        // 与组织关联共用根节点行锁，防止同步把已解除/变更的归属重新登记。
        return spaces.createQuery().setParam(query).in("id", ids).fetch().then();
    }

    private Mono<SpaceSyncResult> applySnapshot(
            String owner,
            Map<String, PropertySpaceEntity> snapshot,
            Map<String, PropertySpaceEntity> existing,
            long now) {
        if (snapshot.isEmpty() && !existing.isEmpty()) {
            return Mono.error(
                    new BusinessException.NoStackTrace("error.property_space_snapshot_empty", 502));
        }
        var added = new ArrayList<PropertySpaceEntity>();
        var updated = new ArrayList<PropertySpaceEntity>();
        snapshot.forEach(
                (id, entity) -> {
                    if (!existing.containsKey(id)) {
                        entity.setDeleted(false);
                        entity.setSourceUpdateTime(now);
                        added.add(entity);
                    } else if (SpaceSnapshot.changed(existing.get(id), entity)) {
                        updated.add(entity);
                    }
                });
        var deleted =
                existing.values().stream()
                        .filter(
                                entity ->
                                        !Boolean.TRUE.equals(entity.getDeleted())
                                                && !snapshot.containsKey(entity.getId()))
                        .map(PropertySpaceEntity::getId)
                        .toList();
        // 来源集合最多 20000 条；增删批量执行，差异更新顺序执行，失败回滚整轮快照。
        Mono<Void> inserts = added.isEmpty() ? Mono.empty() : spaces.insertBatch(added).then();
        Mono<Void> removals =
                deleted.isEmpty()
                        ? Mono.empty()
                        : spaces.createUpdate()
                                .set(PropertySpaceEntity::getDeleted, true)
                                .set(PropertySpaceEntity::getSourceUpdateTime, now)
                                .in(PropertySpaceEntity::getId, deleted)
                                .execute()
                                .then();
        return inserts.thenMany(
                        Flux.fromIterable(updated).concatMap(entity -> updateSource(entity, now)))
                .then(removals)
                .then(Mono.defer(() -> organizationAssets.synchronize(existing, snapshot)))
                .then(
                        Mono.defer(
                                () ->
                                        complete(
                                                owner,
                                                added.size(),
                                                updated.size(),
                                                deleted.size())));
    }

    private Mono<Integer> updateSource(PropertySpaceEntity entity, long now) {
        var update =
                spaces.createUpdate()
                        .set(PropertySpaceEntity::getName, entity.getName())
                        .set(PropertySpaceEntity::getRootId, entity.getRootId())
                        .set(PropertySpaceEntity::getFullName, entity.getFullName())
                        .set(PropertySpaceEntity::getLeaf, entity.getLeaf())
                        .set(PropertySpaceEntity::getDeleted, false)
                        .set(PropertySpaceEntity::getSourceUpdateTime, now);
        if (entity.getSpaceTypeId() == null) update.setNull(PropertySpaceEntity::getSpaceTypeId);
        else update.set(PropertySpaceEntity::getSpaceTypeId, entity.getSpaceTypeId());
        if (entity.getSpaceTypeName() == null)
            update.setNull(PropertySpaceEntity::getSpaceTypeName);
        else update.set(PropertySpaceEntity::getSpaceTypeName, entity.getSpaceTypeName());
        update.set(PropertySpaceEntity::getSpaceLocation, entity.getSpaceLocation());
        if (entity.getParentId() == null) update.setNull(PropertySpaceEntity::getParentId);
        else update.set(PropertySpaceEntity::getParentId, entity.getParentId());
        if (entity.getSortIndex() == null) update.setNull(PropertySpaceEntity::getSortIndex);
        else update.set(PropertySpaceEntity::getSortIndex, entity.getSortIndex());
        return update.where(PropertySpaceEntity::getId, entity.getId()).execute();
    }

    private Mono<SpaceSyncResult> complete(String owner, int added, int updated, int deleted) {
        var result = new SpaceSyncResult(added, updated, deleted, System.currentTimeMillis());
        return states.createUpdate()
                .set(SpaceSyncStateEntity::getLastSuccessTime, result.completedAt())
                .set(SpaceSyncStateEntity::getAdded, added)
                .set(SpaceSyncStateEntity::getUpdated, updated)
                .set(SpaceSyncStateEntity::getDeleted, deleted)
                .setNull(SpaceSyncStateEntity::getLastError)
                .where(SpaceSyncStateEntity::getId, SpaceSyncService.STATE_ID)
                .and(SpaceSyncStateEntity::getLeaseOwner, owner)
                .execute()
                .thenReturn(result);
    }
}
