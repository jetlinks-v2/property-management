package org.jetlinks.pro.property.base.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.base.entity.SpaceSyncStateEntity;
import org.jetlinks.pro.property.base.space.SpaceSyncResult;
import org.jetlinks.pro.property.base.space.SpaceSyncStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.UUID;

/** 启动、定时、手动同步共用的唯一入口，数据库 CAS 租约隔离多个应用实例。 */
@Service
@RequiredArgsConstructor
public class SpaceSyncService {
    static final String STATE_ID = "saas-space";
    private final ReactiveRepository<SpaceSyncStateEntity, String> states;
    private final SaasSpaceSource source;
    private final SpaceSyncWriter writer;

    public Mono<SpaceSyncResult> synchronize() {
        return Mono.usingWhen(
                        acquire(),
                        owner ->
                                source.fetchAll()
                                        .map(SpaceSnapshot::from)
                                        .flatMap(snapshot -> writer.apply(owner, snapshot))
                                        .timeout(Duration.ofSeconds(95)),
                        this::release,
                        (owner, error) ->
                                states.createUpdate()
                                        .set(
                                                SpaceSyncStateEntity::getLastFailureTime,
                                                System.currentTimeMillis())
                                        .set(
                                                SpaceSyncStateEntity::getLastError,
                                                "error.property_space_sync_failed")
                                        .where(SpaceSyncStateEntity::getId, STATE_ID)
                                        .and(SpaceSyncStateEntity::getLeaseOwner, owner)
                                        .execute()
                                        .then(release(owner)),
                        this::release)
                .as(MonoTracer.create("/property/space/synchronize"))
                .contextWrite(ignored -> Context.empty());
    }

    // 租约长于整轮超时，事务 writer 再次 CAS 校验并锁住状态行，过期执行者不能提交。
    private Mono<String> acquire() {
        return Mono.defer(
                () ->
                        states.findById(STATE_ID)
                                .switchIfEmpty(
                                        Mono.defer(
                                                () -> {
                                                    var state = new SpaceSyncStateEntity();
                                                    state.setId(STATE_ID);
                                                    state.setLeaseUntil(0L);
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
                                            String owner = UUID.randomUUID().toString();
                                            long now = System.currentTimeMillis();
                                            return states.createUpdate()
                                                    .set(SpaceSyncStateEntity::getLeaseOwner, owner)
                                                    .set(
                                                            SpaceSyncStateEntity::getLeaseUntil,
                                                            now + 120_000)
                                                    .where(SpaceSyncStateEntity::getId, STATE_ID)
                                                    .lte(SpaceSyncStateEntity::getLeaseUntil, now)
                                                    .execute()
                                                    .flatMap(
                                                            count ->
                                                                    count == 1
                                                                            ? Mono.just(owner)
                                                                            : Mono.error(
                                                                                    new BusinessException
                                                                                            .NoStackTrace(
                                                                                            "error.property_space_sync_busy",
                                                                                            409)));
                                        }));
    }

    private Mono<Void> release(String owner) {
        return states.createUpdate()
                .set(SpaceSyncStateEntity::getLeaseUntil, 0L)
                .setNull(SpaceSyncStateEntity::getLeaseOwner)
                .where(SpaceSyncStateEntity::getId, STATE_ID)
                .and(SpaceSyncStateEntity::getLeaseOwner, owner)
                .execute()
                .then();
    }

    public Mono<SpaceSyncStatus> status() {
        return states.findById(STATE_ID)
                .map(
                        state ->
                                new SpaceSyncStatus(
                                        state.getLeaseUntil() != null
                                                && state.getLeaseUntil()
                                                        > System.currentTimeMillis(),
                                        state.getLastSuccessTime(),
                                        state.getLastFailureTime(),
                                        state.getLastError()))
                .defaultIfEmpty(new SpaceSyncStatus(false, null, null, null));
    }
}
