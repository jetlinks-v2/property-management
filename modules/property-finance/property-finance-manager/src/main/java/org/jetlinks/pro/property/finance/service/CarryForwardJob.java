package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.extern.slf4j.Slf4j;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.*;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import reactor.core.Disposable;
import reactor.core.publisher.*;
import reactor.util.context.Context;

import java.lang.management.ManagementFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import javax.management.*;

/** 生命周期仅触发扫描，执行权及恢复点在数据库；无HTTP可传入的后台身份或特权标志。 */
@Component
@Slf4j
@EnableConfigurationProperties(CarryForwardJobProperties.class)
public class CarryForwardJob implements SmartLifecycle, CarryForwardJobMBean {
    private static final int PAGE = 32;
    private final ReactiveRepository<CarryForwardPlanEntity, String> plans;
    private final ReactiveRepository<CarryForwardRunEntity, String> runs;
    private final CarryForwardRunService runner;
    private final CarryForwardJobProperties properties;
    private final Function<String, Mono<Authentication>> authentications;
    private final AtomicLong successes = new AtomicLong(),
            failures = new AtomicLong(),
            skipped = new AtomicLong();
    private volatile long lastSuccess, lastFailure;
    private volatile String lastError = "";
    private volatile boolean running;
    private Disposable subscription;
    private ObjectName objectName;

    @Autowired
    public CarryForwardJob(
            ReactiveRepository<CarryForwardPlanEntity, String> plans,
            ReactiveRepository<CarryForwardRunEntity, String> runs,
            CarryForwardRunService runner,
            CarryForwardJobProperties properties) {
        this(plans, runs, runner, properties, ReactiveAuthenticationHolder::get);
    }

    CarryForwardJob(
            ReactiveRepository<CarryForwardPlanEntity, String> plans,
            ReactiveRepository<CarryForwardRunEntity, String> runs,
            CarryForwardRunService runner,
            CarryForwardJobProperties properties,
            Function<String, Mono<Authentication>> authentications) {
        this.plans = plans;
        this.runs = runs;
        this.runner = runner;
        this.properties = properties;
        this.authentications = authentications;
        if (properties.getPollSeconds() < 5 || properties.getPollSeconds() > 3600)
            throw new IllegalArgumentException(
                    "property.finance.carry-job.poll-seconds must be between 5 and 3600");
    }

    Mono<Summary> poll(Instant now) {
        return page(null)
                .expand(
                        rows ->
                                rows.size() < PAGE
                                        ? Mono.empty()
                                        : page(rows.get(rows.size() - 1).getId()),
                        1)
                .concatMapIterable(rows -> rows)
                .concatMap(plan -> process(plan, now))
                .reduce(new Summary(0, 0, 0), Summary::add)
                // 绝不沿用触发请求中的登录、ignore标志、租户或应用范围。
                .contextWrite(ignored -> Context.empty())
                .as(MonoTracer.create("/property/finance/carry-job/poll"));
    }

    private Mono<List<CarryForwardPlanEntity>> page(String after) {
        var q = pageQuery();
        var query = plans.createQuery().setParam(q);
        if (after != null) query.gt("id", after);
        return query.fetch().collectList();
    }

    private Mono<Summary> process(CarryForwardPlanEntity plan, Instant now) {
        return activeRun(plan)
                .flatMap(active -> executePlan(plan, active, now))
                .onErrorResume(
                        failure -> {
                            lastError = code(failure);
                            lastFailure = System.currentTimeMillis();
                            log.warn(
                                    "Property carry-forward plan {} failed: {}",
                                    plan.getId(),
                                    lastError);
                            return Mono.just(new Summary(0, 1, 0));
                        })
                .doOnNext(this::record);
    }

    private Mono<Summary> executePlan(
            CarryForwardPlanEntity plan, Optional<CarryForwardRunEntity> active, Instant now) {
        if (active.isEmpty()
                && (!Boolean.TRUE.equals(plan.getEnabled())
                        || Boolean.TRUE.equals(plan.getDeleted())))
            return Mono.just(new Summary(0, 0, 1));
        return authority(plan)
                .flatMap(
                        context ->
                                execution(plan, active, now)
                                        .map(this::summary)
                                        .defaultIfEmpty(new Summary(0, 0, 1))
                                        .contextWrite(ignored -> context));
    }

    private Mono<CarryForwardRunEntity> execution(
            CarryForwardPlanEntity plan, Optional<CarryForwardRunEntity> active, Instant now) {
        if (active.isPresent()) return runner.resume(plan.getProjectId(), active.get().getId());
        return runner.prepare(plan.getProjectId(), plan.getId(), now)
                .filter(run -> Set.of("PENDING", "RUNNING").contains(run.getStatus()))
                .flatMap(run -> runner.resume(plan.getProjectId(), run.getId()));
    }

    private Mono<Context> authority(CarryForwardPlanEntity plan) {
        return Mono.defer(() -> authentications.apply(CarryForwardAuthority.executorId(plan)))
                .switchIfEmpty(Mono.error(error("carry_authority_invalid", 403)))
                .flatMap(auth -> CarryForwardAuthority.restore(plan, auth))
                .contextWrite(
                        ignored ->
                                plan.getTenantId() == null || plan.getTenantId().isBlank()
                                        ? Context.empty()
                                        : TenantHolder.makeCurrent(plan.getTenantId()));
    }

    private Mono<Optional<CarryForwardRunEntity>> activeRun(CarryForwardPlanEntity plan) {
        var q = pageQuery();
        q.setPageSize(1);
        // 同一计划先完成旧持久批次，再创建今天槽位；不在重启时生成昨天的新槽位。
        return runs.createQuery()
                .setParam(q)
                .where("planId", plan.getId())
                .and("projectId", plan.getProjectId())
                .in("status", List.of("PENDING", "RUNNING"))
                .fetchOne()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    private Summary summary(CarryForwardRunEntity run) {
        if (Set.of("PARTIAL", "CANCELLED").contains(run.getStatus()))
            lastError =
                    run.getErrorCode() == null
                            ? "error.property_finance_carry_run_failed"
                            : run.getErrorCode();
        return switch (run.getStatus()) {
            case "SUCCEEDED" -> new Summary(1, 0, 0);
            case "PARTIAL", "CANCELLED" -> new Summary(0, 1, 0);
            default -> new Summary(0, 0, 1);
        };
    }

    private static QueryParamEntity pageQuery() {
        var q = new QueryParamEntity();
        q.setPaging(true);
        q.setPageIndex(0);
        q.setPageSize(PAGE);
        q.toQuery().orderByAsc("id");
        return q;
    }

    private void record(Summary result) {
        successes.addAndGet(result.succeeded());
        failures.addAndGet(result.failed());
        skipped.addAndGet(result.skipped());
        if (result.succeeded() > 0) lastSuccess = System.currentTimeMillis();
        if (result.failed() > 0) lastFailure = System.currentTimeMillis();
    }

    private static String code(Throwable failure) {
        var message = failure.getMessage();
        return message != null && message.matches("error\\.property_finance_[a-z_]+")
                ? message
                : "error.property_finance_carry_run_failed";
    }

    @Override
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (running || !properties.isEnabled()) return;
        running = true;
        // exhaustMap语义：上一轮仍在处理时丢弃tick，不无限积压扫描任务。
        subscription =
                Flux.interval(
                                Duration.ofSeconds(5),
                                Duration.ofSeconds(properties.getPollSeconds()))
                        .onBackpressureDrop()
                        .concatMap(
                                tick ->
                                        poll(Instant.now())
                                                .onErrorResume(
                                                        failure -> {
                                                            lastError = code(failure);
                                                            lastFailure =
                                                                    System.currentTimeMillis();
                                                            failures.incrementAndGet();
                                                            log.warn(
                                                                    "Property carry-forward scan"
                                                                            + " failed: {}",
                                                                    lastError);
                                                            return Mono.empty();
                                                        }),
                                0)
                        .subscribe();
        try {
            var name = new ObjectName("org.jetlinks:type=PropertyManagement,name=CarryForwardJob");
            ManagementFactory.getPlatformMBeanServer()
                    .registerMBean(new StandardMBean(this, CarryForwardJobMBean.class), name);
            objectName = name;
        } catch (Exception failure) {
            log.warn("Unable to register carry-forward job MBean");
        }
    }

    @Override
    public boolean isAutoStartup() {
        return false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (subscription != null) {
            subscription.dispose();
            subscription = null;
        }
        if (objectName != null)
            try {
                ManagementFactory.getPlatformMBeanServer().unregisterMBean(objectName);
                objectName = null;
            } catch (Exception failure) {
                log.warn("Unable to unregister carry-forward job MBean");
            }
    }

    @Override
    public long getSuccessCount() {
        return successes.get();
    }

    @Override
    public long getFailureCount() {
        return failures.get();
    }

    @Override
    public long getSkippedCount() {
        return skipped.get();
    }

    @Override
    public long getLastSuccessTime() {
        return lastSuccess;
    }

    @Override
    public long getLastFailureTime() {
        return lastFailure;
    }

    @Override
    public String getLastError() {
        return lastError;
    }

    record Summary(int succeeded, int failed, int skipped) {
        Summary add(Summary other) {
            return new Summary(
                    succeeded + other.succeeded, failed + other.failed, skipped + other.skipped);
        }
    }
}
