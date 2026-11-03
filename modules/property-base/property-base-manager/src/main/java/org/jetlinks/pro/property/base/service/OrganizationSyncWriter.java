package org.jetlinks.pro.property.base.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.base.entity.OrganizationSyncStateEntity;
import org.jetlinks.pro.property.base.entity.PropertyOrganizationEntity;
import org.jetlinks.pro.property.base.organization.OrganizationSyncResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/** 校验租约后锁住状态行直到事务提交；来源字段更新不触碰物业标注及其版本。 */
@Service
@RequiredArgsConstructor
public class OrganizationSyncWriter {
    private final ReactiveRepository<PropertyOrganizationEntity, String> organizations;
    private final ReactiveRepository<OrganizationSyncStateEntity, String> states;

    @Transactional
    public Mono<OrganizationSyncResult> apply(
            String owner, Map<String, PropertyOrganizationEntity> snapshot) {
        long now = System.currentTimeMillis();
        return states.createUpdate()
                .set(OrganizationSyncStateEntity::getLeaseOwner, owner)
                .where(OrganizationSyncStateEntity::getId, OrganizationSyncService.STATE_ID)
                .and(OrganizationSyncStateEntity::getLeaseOwner, owner)
                .gt(OrganizationSyncStateEntity::getLeaseUntil, now)
                .execute()
                .flatMap(
                        locked ->
                                locked == 1
                                        ? organizations
                                                .createQuery()
                                                .fetch()
                                                .collectMap(PropertyOrganizationEntity::getId)
                                        : Mono.error(
                                                new BusinessException.NoStackTrace(
                                                        "error.property_org_sync_busy", 409)))
                .flatMap(
                        existing -> {
                            if (snapshot.isEmpty()
                                    && existing.values().stream()
                                            .anyMatch(e -> !Boolean.TRUE.equals(e.getDeleted()))) {
                                return Mono.error(
                                        new BusinessException.NoStackTrace(
                                                "error.property_org_snapshot_empty", 502));
                            }
                            var added = new ArrayList<PropertyOrganizationEntity>();
                            var updated = new ArrayList<PropertyOrganizationEntity>();
                            snapshot.forEach(
                                    (id, entity) -> {
                                        if (!existing.containsKey(id)) {
                                            entity.setDeleted(false);
                                            entity.setVersion(0L);
                                            entity.setSourceUpdateTime(now);
                                            added.add(entity);
                                        } else if (OrganizationSnapshot.changed(
                                                existing.get(id), entity)) updated.add(entity);
                                    });
                            var deleted =
                                    existing.values().stream()
                                            .filter(
                                                    e ->
                                                            !Boolean.TRUE.equals(e.getDeleted())
                                                                    && !snapshot.containsKey(
                                                                            e.getId()))
                                            .map(PropertyOrganizationEntity::getId)
                                            .toList();
                            // 批量新增/删除；不同来源值的更新逐条执行，整轮事务保证失败不留下半棵树。
                            Mono<Void> inserts =
                                    added.isEmpty()
                                            ? Mono.empty()
                                            : organizations.insertBatch(added).then();
                            Mono<Void> removals =
                                    deleted.isEmpty()
                                            ? Mono.empty()
                                            : organizations
                                                    .createUpdate()
                                                    .set(
                                                            PropertyOrganizationEntity::getDeleted,
                                                            true)
                                                    .set(
                                                            PropertyOrganizationEntity
                                                                    ::getDeletedTime,
                                                            now)
                                                    .in(PropertyOrganizationEntity::getId, deleted)
                                                    .execute()
                                                    .then();
                            return inserts.thenMany(
                                            Flux.fromIterable(updated)
                                                    .concatMap(entity -> updateSource(entity, now)))
                                    .then(removals)
                                    .then(
                                            Mono.defer(
                                                    () -> {
                                                        var result =
                                                                new OrganizationSyncResult(
                                                                        added.size(),
                                                                        updated.size(),
                                                                        deleted.size(),
                                                                        System.currentTimeMillis());
                                                        return states.createUpdate()
                                                                .set(
                                                                        OrganizationSyncStateEntity
                                                                                ::getLastSuccessTime,
                                                                        result.completedTime())
                                                                .set(
                                                                        OrganizationSyncStateEntity
                                                                                ::getAdded,
                                                                        result.added())
                                                                .set(
                                                                        OrganizationSyncStateEntity
                                                                                ::getUpdated,
                                                                        result.updated())
                                                                .set(
                                                                        OrganizationSyncStateEntity
                                                                                ::getDeleted,
                                                                        result.deleted())
                                                                .setNull(
                                                                        OrganizationSyncStateEntity
                                                                                ::getLastError)
                                                                .where(
                                                                        OrganizationSyncStateEntity
                                                                                ::getId,
                                                                        OrganizationSyncService
                                                                                .STATE_ID)
                                                                .and(
                                                                        OrganizationSyncStateEntity
                                                                                ::getLeaseOwner,
                                                                        owner)
                                                                .execute()
                                                                .thenReturn(result);
                                                    }));
                        });
    }

    private Mono<Integer> updateSource(PropertyOrganizationEntity entity, long now) {
        var update =
                organizations
                        .createUpdate()
                        .set(PropertyOrganizationEntity::getName, entity.getName())
                        .set(PropertyOrganizationEntity::getSortIndex, entity.getSortIndex())
                        .set(PropertyOrganizationEntity::getDeleted, false)
                        .setNull(PropertyOrganizationEntity::getDeletedTime)
                        .set(PropertyOrganizationEntity::getSourceUpdateTime, now);
        if (entity.getParentId() == null) update.setNull(PropertyOrganizationEntity::getParentId);
        else update.set(PropertyOrganizationEntity::getParentId, entity.getParentId());
        if (entity.getCode() == null) update.setNull(PropertyOrganizationEntity::getCode);
        else update.set(PropertyOrganizationEntity::getCode, entity.getCode());
        return update.where(PropertyOrganizationEntity::getId, entity.getId()).execute();
    }
}
