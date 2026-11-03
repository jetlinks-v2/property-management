package org.jetlinks.pro.property.base.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.base.entity.OrganizationSyncStateEntity;
import org.jetlinks.pro.property.base.organization.OrganizationSyncResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.UUID;

/** 启动、定时、手动同步共用的唯一入口，数据库 CAS 租约隔离多个应用实例。 */
@Service
@RequiredArgsConstructor
public class OrganizationSyncService {
    static final String STATE_ID = "saas-organization";
    private final ReactiveRepository<OrganizationSyncStateEntity, String> states;
    private final SaasOrganizationSource source;
    private final OrganizationSyncWriter writer;

    public Mono<OrganizationSyncResult> synchronize() {
        return Mono.usingWhen(
                        acquire(),
                        owner ->
                                source.fetchAll()
                                        .map(OrganizationSnapshot::from)
                                        .flatMap(snapshot -> writer.apply(owner, snapshot))
                                        .timeout(Duration.ofSeconds(45)),
                        this::release,
                        (owner, error) ->
                                states.createUpdate()
                                        .set(
                                                OrganizationSyncStateEntity::getLastFailureTime,
                                                System.currentTimeMillis())
                                        .set(
                                                OrganizationSyncStateEntity::getLastError,
                                                "error.property_org_sync_failed")
                                        .where(OrganizationSyncStateEntity::getId, STATE_ID)
                                        .and(OrganizationSyncStateEntity::getLeaseOwner, owner)
                                        .execute()
                                        .then(release(owner)),
                        this::release)
                .as(MonoTracer.create("/property/organization/synchronize"))
                .contextWrite(ignored -> Context.empty());
    }

    private Mono<String> acquire() {
        return Mono.defer(
                () ->
                        states.findById(STATE_ID)
                                .switchIfEmpty(
                                        Mono.defer(
                                                () -> {
                                                    var state = new OrganizationSyncStateEntity();
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
                                                    .set(
                                                            OrganizationSyncStateEntity
                                                                    ::getLeaseOwner,
                                                            owner)
                                                    .set(
                                                            OrganizationSyncStateEntity
                                                                    ::getLeaseUntil,
                                                            now + 60_000)
                                                    .where(
                                                            OrganizationSyncStateEntity::getId,
                                                            STATE_ID)
                                                    .lte(
                                                            OrganizationSyncStateEntity
                                                                    ::getLeaseUntil,
                                                            now)
                                                    .execute()
                                                    .flatMap(
                                                            count ->
                                                                    count == 1
                                                                            ? Mono.just(owner)
                                                                            : Mono.error(
                                                                                    new BusinessException
                                                                                            .NoStackTrace(
                                                                                            "error.property_org_sync_busy",
                                                                                            409)));
                                        }));
    }

    private Mono<Void> release(String owner) {
        return states.createUpdate()
                .set(OrganizationSyncStateEntity::getLeaseUntil, 0L)
                .setNull(OrganizationSyncStateEntity::getLeaseOwner)
                .where(OrganizationSyncStateEntity::getId, STATE_ID)
                .and(OrganizationSyncStateEntity::getLeaseOwner, owner)
                .execute()
                .then();
    }

    public Mono<Status> status() {
        return states.findById(STATE_ID)
                .map(
                        state ->
                                new Status(
                                        state.getLeaseUntil() != null
                                                && state.getLeaseUntil()
                                                        > System.currentTimeMillis(),
                                        state.getLastSuccessTime(),
                                        state.getLastFailureTime(),
                                        state.getLastError()))
                .defaultIfEmpty(new Status(false, null, null, null));
    }

    public record Status(
            boolean running, Long lastSuccessTime, Long lastFailureTime, String lastError) {}
}
