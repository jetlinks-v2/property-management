package org.jetlinks.pro.property.base.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.base.entity.PropertyUserEntity;
import org.jetlinks.pro.property.base.entity.UserSyncStateEntity;
import org.jetlinks.sdk.server.auth.UserDetail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 全量读取、缺失复核、事务提交和失败记录使用同一数据库租约。 */
@Service
@RequiredArgsConstructor
public class UserSyncService {
    static final String STATE_ID = "saas-user";
    private final ReactiveRepository<UserSyncStateEntity, String> states;
    private final ReactiveRepository<PropertyUserEntity, String> users;
    private final SaasUserSource source;
    private final UserSyncWriter writer;

    @Value("${property.user-sync.timeout-seconds:120}")
    private long timeoutSeconds = 120;

    public Mono<String> acquire() {
        return Mono.defer(
                        () ->
                                states.findById(STATE_ID)
                                        .switchIfEmpty(
                                                Mono.defer(
                                                        () -> {
                                                            var state = new UserSyncStateEntity();
                                                            state.setId(STATE_ID);
                                                            state.setLeaseUntil(0L);
                                                            state.setStatus("idle");
                                                            return states.insert(state)
                                                                    .thenReturn(state)
                                                                    .onErrorResume(
                                                                            error ->
                                                                                    states.findById(
                                                                                                    STATE_ID)
                                                                                            .switchIfEmpty(
                                                                                                    Mono
                                                                                                            .error(
                                                                                                                    error)));
                                                        }))
                                        .flatMap(
                                                state -> {
                                                    String taskId = UUID.randomUUID().toString();
                                                    long now = System.currentTimeMillis();
                                                    return states.createUpdate()
                                                            .set("taskId", taskId)
                                                            .set("status", "running")
                                                            .set(
                                                                    "leaseUntil",
                                                                    now
                                                                            + (Math.max(
                                                                                                    10,
                                                                                                    timeoutSeconds)
                                                                                            + 30)
                                                                                    * 1000)
                                                            .where("id", STATE_ID)
                                                            .lte("leaseUntil", now)
                                                            .execute()
                                                            .flatMap(
                                                                    count ->
                                                                            count == 1
                                                                                    ? Mono.just(
                                                                                            taskId)
                                                                                    : Mono.error(
                                                                                            new BusinessException
                                                                                                    .NoStackTrace(
                                                                                                    "error.property_user_sync_busy",
                                                                                                    409)));
                                                }))
                .contextWrite(ignored -> Context.empty());
    }

    public Mono<UserSyncWriter.Result> synchronize() {
        return acquire().flatMap(this::execute);
    }

    Mono<UserSyncWriter.Result> execute(String taskId) {
        return Mono.usingWhen(
                        Mono.just(taskId),
                        ignored ->
                                source.fetchAll()
                                        .flatMap(snapshot -> reconcile(taskId, snapshot))
                                        .timeout(Duration.ofSeconds(Math.max(10, timeoutSeconds))),
                        this::release,
                        (owner, error) -> fail(owner, error),
                        owner -> fail(owner, new IllegalStateException("cancelled")))
                .as(MonoTracer.create("/property/user/synchronize"))
                .contextWrite(ignored -> Context.empty());
    }

    private Mono<UserSyncWriter.Result> reconcile(String taskId, Map<String, UserDetail> snapshot) {
        return users.createQuery()
                .where("deleted", false)
                .fetch()
                .map(PropertyUserEntity::getId)
                .filter(id -> !snapshot.containsKey(id))
                .collectList()
                .flatMap(
                        candidates -> {
                            // 空来源有旧数据时拒绝整轮；不能用逐条复核绕过批量清空保护。
                            if (snapshot.isEmpty() && !candidates.isEmpty()) {
                                return Mono.error(
                                        new BusinessException.NoStackTrace(
                                                "error.property_user_snapshot_empty", 502));
                            }
                            Set<String> removed = new HashSet<>();
                            return Flux.fromIterable(candidates)
                                    .concatMap(
                                            id ->
                                                    source.find(id)
                                                            .doOnNext(
                                                                    found -> {
                                                                        if (found.isEmpty()) {
                                                                            removed.add(id);
                                                                        } else {
                                                                            snapshot.put(
                                                                                    id,
                                                                                    found.get());
                                                                            source.validateSize(
                                                                                    snapshot
                                                                                            .size());
                                                                        }
                                                                    }))
                                    .then(
                                            Mono.defer(
                                                    () -> writer.apply(taskId, snapshot, removed)));
                        });
    }

    Mono<Void> fail(String taskId, Throwable error) {
        String code =
                error instanceof BusinessException business
                        ? business.getMessage()
                        : "error.property_user_sync_failed";
        if (code == null || !code.startsWith("error.property_user_"))
            code = "error.property_user_sync_failed";
        return states.createUpdate()
                .set("status", "failed")
                .set("lastError", code)
                .set("lastFailureTime", System.currentTimeMillis())
                .where("id", STATE_ID)
                .and("taskId", taskId)
                .execute()
                .then(release(taskId));
    }

    private Mono<Void> release(String taskId) {
        return states.createUpdate()
                .set("leaseUntil", 0L)
                .where("id", STATE_ID)
                .and("taskId", taskId)
                .execute()
                .then();
    }

    public Mono<Status> status() {
        return states.findById(STATE_ID)
                .map(
                        state -> {
                            boolean running =
                                    state.getLeaseUntil() != null
                                            && state.getLeaseUntil() > System.currentTimeMillis();
                            boolean expired = !running && "running".equals(state.getStatus());
                            return new Status(
                                    state.getTaskId(),
                                    running ? "running" : expired ? "failed" : state.getStatus(),
                                    running,
                                    state.getLastSuccessTime(),
                                    state.getLastFailureTime(),
                                    expired
                                            ? "error.property_user_sync_expired"
                                            : state.getLastError(),
                                    state.getAdded(),
                                    state.getUpdated(),
                                    state.getDeleted());
                        })
                .defaultIfEmpty(
                        new Status(null, "idle", false, null, null, null, null, null, null));
    }

    public record Status(
            String taskId,
            String status,
            boolean running,
            Long lastSuccessTime,
            Long lastFailureTime,
            String lastError,
            Integer added,
            Integer updated,
            Integer deleted) {}
}
