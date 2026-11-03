package org.jetlinks.pro.property.base.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.base.entity.PropertyUserEntity;
import org.jetlinks.pro.property.base.entity.PropertyUserOrganizationEntity;
import org.jetlinks.pro.property.base.entity.UserSyncStateEntity;
import org.jetlinks.pro.property.base.entity.PropertyOrganizationEntity;
import org.jetlinks.sdk.server.auth.UserDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 暂存完成且缺失已复核后，锁住租约行并事务提交；始终不更新身份及标注版本。 */
@Service
@RequiredArgsConstructor
public class UserSyncWriter {
    private final ReactiveRepository<PropertyUserEntity, String> users;
    private final ReactiveRepository<PropertyUserOrganizationEntity, String> organizations;
    private final ReactiveRepository<UserSyncStateEntity, String> states;
    private final ReactiveRepository<PropertyOrganizationEntity, String> organizationTree;

    @Transactional
    public Mono<Result> apply(
            String taskId, Map<String, UserDetail> snapshot, Set<String> removed) {
        long now = System.currentTimeMillis();
        return states.createUpdate()
                .set("taskId", taskId)
                .where("id", UserSyncService.STATE_ID)
                .and("taskId", taskId)
                .gt("leaseUntil", now)
                .execute()
                .flatMap(
                        locked ->
                                locked == 1
                                        ? users.createQuery()
                                                .fetch()
                                                .collectMap(PropertyUserEntity::getId)
                                        : Mono.error(
                                                new BusinessException.NoStackTrace(
                                                        "error.property_user_sync_busy", 409)))
                .flatMap(existing -> applySnapshot(taskId, snapshot, removed, existing, now));
    }

    private Mono<Result> applySnapshot(
            String taskId,
            Map<String, UserDetail> snapshot,
            Set<String> removed,
            Map<String, PropertyUserEntity> existing,
            long now) {
        snapshot.values().forEach(SaasUserSource::validate);
        if (snapshot.isEmpty() && existing.values().stream().anyMatch(user -> !user.getDeleted()))
            return Mono.error(
                    new BusinessException.NoStackTrace("error.property_user_snapshot_empty", 502));
        if (removed.stream().anyMatch(snapshot::containsKey))
            return Mono.error(SaasUserSource.invalid());
        var added = new ArrayList<PropertyUserEntity>();
        snapshot.forEach(
                (id, source) -> {
                    if (!id.equals(source.getId())) throw SaasUserSource.invalid();
                    if (!existing.containsKey(id)) added.add(entity(source, now));
                });
        Mono<Void> inserts = added.isEmpty() ? Mono.empty() : users.insertBatch(added).then();
        return organizationTree
                .createQuery()
                .where("deleted", false)
                .fetch()
                .collectMap(PropertyOrganizationEntity::getId)
                .flatMap(
                        tree ->
                                inserts.thenMany(
                                                Flux.fromIterable(snapshot.values())
                                                        .concatMap(
                                                                user ->
                                                                        synchronizeUser(
                                                                                user,
                                                                                existing.get(
                                                                                        user
                                                                                                .getId()),
                                                                                tree,
                                                                                now)))
                                        .reduce(0, (count, changed) -> changed ? count + 1 : count))
                .flatMap(
                        updated ->
                                removeUsers(removed, now)
                                        .then(
                                                recordResult(
                                                        taskId,
                                                        added.size(),
                                                        updated,
                                                        removed.size())));
    }

    private Mono<Boolean> synchronizeUser(
            UserDetail user,
            PropertyUserEntity previous,
            Map<String, PropertyOrganizationEntity> tree,
            long now) {
        return updateSource(user, now)
                .then(updateOrganizations(user, tree))
                .map(
                        orgChanged ->
                                previous != null && (orgChanged || sourceChanged(previous, user)));
    }

    private Mono<Void> removeUsers(Set<String> removed, long now) {
        if (removed.isEmpty()) return Mono.empty();
        return users.createUpdate()
                .set("deleted", true)
                .set("deletedTime", now)
                .in("id", removed)
                .execute()
                .then(
                        organizations
                                .createUpdate()
                                .set("deleted", true)
                                .in("userId", removed)
                                .execute())
                .then();
    }

    private Mono<Result> recordResult(String taskId, int added, int updated, int deleted) {
        return Mono.defer(
                () -> {
                    var result = new Result(added, updated, deleted, System.currentTimeMillis());
                    return states.createUpdate()
                            .set("status", "succeeded")
                            .set("lastSuccessTime", result.completedTime())
                            .set("added", added)
                            .set("updated", updated)
                            .set("deleted", deleted)
                            .setNull("lastError")
                            .where("id", UserSyncService.STATE_ID)
                            .and("taskId", taskId)
                            .execute()
                            .thenReturn(result);
                });
    }

    private PropertyUserEntity entity(UserDetail source, long now) {
        var user = new PropertyUserEntity();
        user.setId(source.getId());
        user.setUsername(source.getUsername());
        user.setAccountType(source.getType());
        user.setStatus(source.getStatus());
        user.setCreateTime(source.getCreateTime());
        user.setSourceUpdateTime(now);
        return user;
    }

    private Mono<Void> updateSource(UserDetail source, long now) {
        var update =
                users.createUpdate()
                        .set("username", source.getUsername())
                        .set("accountType", source.getType())
                        .set("status", source.getStatus())
                        .set("createTime", source.getCreateTime())
                        .set("sourceUpdateTime", now)
                        .set("deleted", false)
                        .setNull("deletedTime");
        // null 必须真实清空，不能被 ORM 的忽略 null 策略保留为过期联系电话等资料。
        if (source.getName() == null) update.setNull("name");
        else update.set("name", source.getName());
        if (source.getTelephone() == null) update.setNull("telephone");
        else update.set("telephone", source.getTelephone());
        if (source.getEmail() == null) update.setNull("email");
        else update.set("email", source.getEmail());
        if (source.getModifyTime() == null) update.setNull("sourceModifyTime");
        else update.set("sourceModifyTime", source.getModifyTime());
        return update.where("id", source.getId()).execute().then();
    }

    private Mono<Boolean> updateOrganizations(
            UserDetail user, Map<String, PropertyOrganizationEntity> tree) {
        return organizations
                .createQuery()
                .where("userId", user.getId())
                .fetch()
                .collectMap(PropertyUserOrganizationEntity::getId)
                .flatMap(
                        existing -> {
                            var rows = new ArrayList<PropertyUserOrganizationEntity>();
                            if (user.getOrgList() != null)
                                user.getOrgList()
                                        .forEach(
                                                org -> {
                                                    var row = new PropertyUserOrganizationEntity();
                                                    row.setId(
                                                            UUID.nameUUIDFromBytes(
                                                                            (user.getId()
                                                                                            + ":"
                                                                                            + org
                                                                                                    .getId())
                                                                                    .getBytes(
                                                                                            StandardCharsets
                                                                                                    .UTF_8))
                                                                    .toString());
                                                    row.setUserId(user.getId());
                                                    row.setOrgId(org.getId());
                                                    row.setName(org.getName());
                                                    row.setFullName(
                                                            organizationPath(
                                                                    org.getId(),
                                                                    org.getName(),
                                                                    tree));
                                                    rows.add(row);
                                                });
                            var inserts =
                                    rows.stream()
                                            .filter(row -> !existing.containsKey(row.getId()))
                                            .toList();
                            boolean changed =
                                    existing.values().stream()
                                                            .filter(row -> !row.getDeleted())
                                                            .count()
                                                    != rows.size()
                                            || rows.stream()
                                                    .anyMatch(
                                                            row -> {
                                                                var old = existing.get(row.getId());
                                                                return old == null
                                                                        || old.getDeleted()
                                                                        || !Objects.equals(
                                                                                row.getName(),
                                                                                old.getName())
                                                                        || !Objects.equals(
                                                                                row.getFullName(),
                                                                                old.getFullName());
                                                            });
                            return organizations
                                    .createUpdate()
                                    .set("deleted", true)
                                    .where("userId", user.getId())
                                    .execute()
                                    .then(
                                            inserts.isEmpty()
                                                    ? Mono.empty()
                                                    : organizations.insertBatch(inserts).then())
                                    .thenMany(
                                            Flux.fromIterable(rows)
                                                    .filter(
                                                            row ->
                                                                    existing.containsKey(
                                                                            row.getId()))
                                                    .concatMap(
                                                            row -> {
                                                                var update =
                                                                        organizations
                                                                                .createUpdate()
                                                                                .set(
                                                                                        "deleted",
                                                                                        false);
                                                                if (row.getName() == null)
                                                                    update.setNull("name");
                                                                else
                                                                    update.set(
                                                                            "name", row.getName());
                                                                if (row.getFullName() == null)
                                                                    update.setNull("fullName");
                                                                else
                                                                    update.set(
                                                                            "fullName",
                                                                            row.getFullName());
                                                                return update.where(
                                                                                "id", row.getId())
                                                                        .execute();
                                                            }))
                                    .then(Mono.just(changed));
                        });
    }

    private boolean sourceChanged(PropertyUserEntity old, UserDetail user) {
        return old.getDeleted()
                || !Objects.equals(old.getName(), user.getName())
                || !Objects.equals(old.getUsername(), user.getUsername())
                || !Objects.equals(old.getStatus(), user.getStatus())
                || !Objects.equals(old.getTelephone(), user.getTelephone())
                || !Objects.equals(old.getEmail(), user.getEmail())
                || !Objects.equals(old.getAccountType(), user.getType())
                || !Objects.equals(old.getCreateTime(), user.getCreateTime())
                || !Objects.equals(old.getSourceModifyTime(), user.getModifyTime());
    }

    private String organizationPath(
            String id, String name, Map<String, PropertyOrganizationEntity> tree) {
        var path = new java.util.LinkedList<String>();
        var visited = new HashSet<String>();
        var node = tree.get(id);
        if (node == null) return name;
        while (node != null && visited.add(node.getId())) {
            path.addFirst(node.getName());
            node = tree.get(node.getParentId());
        }
        return String.join("/", path);
    }

    public record Result(int added, int updated, int deleted, long completedTime) {}
}
