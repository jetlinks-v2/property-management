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

/** 分页扫描只恢复数据库委托，结账事务与失败记录由独立Spring服务承载。 */
@Component
@Slf4j
@EnableConfigurationProperties(MonthClosingJobProperties.class)
public class MonthClosingJob implements SmartLifecycle, MonthClosingJobMBean {
    private static final int PAGE = 32;
    private final ReactiveRepository<MonthClosingScheduleEntity, String> plans;
    private final MonthClosingAutomaticService runner;
    private final MonthClosingJobProperties properties;
    private final BillingJobProperties billing;
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
    public MonthClosingJob(
            ReactiveRepository<MonthClosingScheduleEntity, String> plans,
            MonthClosingAutomaticService runner,
            MonthClosingJobProperties properties,
            BillingJobProperties billing) {
        this(plans, runner, properties, billing, ReactiveAuthenticationHolder::get);
    }

    MonthClosingJob(
            ReactiveRepository<MonthClosingScheduleEntity, String> plans,
            MonthClosingAutomaticService runner,
            MonthClosingJobProperties properties,
            BillingJobProperties billing,
            Function<String, Mono<Authentication>> authentications) {
        this.plans = plans;
        this.runner = runner;
        this.properties = properties;
        this.billing = billing;
        this.authentications = authentications;
        if (properties.getPollSeconds() < 5 || properties.getPollSeconds() > 3600)
            throw new IllegalArgumentException(
                    "property.finance.month-closing-job.poll-seconds must be between 5 and 3600");
        ZoneId.of(billing.getZone());
    }

    Mono<Summary> poll(Instant now) {
        return page(null, now)
                .expand(
                        rows ->
                                rows.size() < PAGE
                                        ? Mono.empty()
                                        : page(rows.get(rows.size() - 1).getId(), now),
                        1)
                .concatMapIterable(rows -> rows)
                .concatMap(plan -> process(plan, now))
                .reduce(new Summary(0, 0, 0), Summary::add)
                // 禁止HTTP触发上下文的用户、租户、ignore权限或应用选择渗入后台。
                .contextWrite(ignored -> Context.empty())
                .as(MonoTracer.create("property.finance.month-closing.automatic.poll"));
    }

    private Mono<List<MonthClosingScheduleEntity>> page(String after, Instant now) {
        var q = new QueryParamEntity();
        q.setPaging(true);
        q.setPageSize(PAGE);
        q.toQuery().orderByAsc("id");
        var query =
                plans.createQuery()
                        .setParam(q)
                        .where("enabled", true)
                        .and("deleted", false)
                        .lte("nextAttemptAt", now.toEpochMilli());
        if (after != null) query.gt("id", after);
        return query.fetch().collectList();
    }

    private Mono<Summary> process(MonthClosingScheduleEntity plan, Instant now) {
        return authority(plan)
                .flatMap(
                        context ->
                                runner.execute(plan, now, ZoneId.of(billing.getZone()))
                                        .contextWrite(ignored -> context))
                .map(run -> new Summary(1, 0, 0))
                .defaultIfEmpty(new Summary(0, 0, 1))
                .onErrorResume(
                        failure -> {
                            lastError = code(failure);
                            lastFailure = System.currentTimeMillis();
                            log.warn(
                                    "Automatic monthly closing project {} failed: {}",
                                    plan.getProjectId(),
                                    lastError);
                            // runner.execute事务已终止并回滚，再独立记录失败；持久化失败交由外层扫描监控报告。
                            return runner.failed(plan, now, lastError)
                                    .map(run -> new Summary(0, 1, 0))
                                    .defaultIfEmpty(new Summary(0, 0, 1))
                                    .onErrorResume(
                                            recordFailure -> {
                                                lastError =
                                                        "error.property_finance_month_schedule_record_failed";
                                                log.warn(
                                                        "Unable to persist automatic monthly"
                                                            + " closing result for project {}",
                                                        plan.getProjectId());
                                                return Mono.just(new Summary(0, 1, 0));
                                            });
                        })
                .doOnNext(this::record);
    }

    private Mono<Context> authority(MonthClosingScheduleEntity plan) {
        return Mono.defer(
                        () ->
                                authentications.apply(
                                        FinanceJobAuthority.executorId(
                                                plan.getExecutorSnapshot(),
                                                MonthClosingScheduleService.AUTHORITY_ERROR)))
                .switchIfEmpty(Mono.error(error(MonthClosingScheduleService.AUTHORITY_ERROR, 403)))
                .flatMap(
                        auth ->
                                FinanceJobAuthority.restore(
                                        plan.getExecutorSnapshot(),
                                        plan.getTenantId(),
                                        auth,
                                        MonthClosingScheduleService::mayExecute,
                                        MonthClosingScheduleService.AUTHORITY_ERROR))
                .contextWrite(
                        ignored ->
                                plan.getTenantId() == null || plan.getTenantId().isBlank()
                                        ? Context.empty()
                                        : TenantHolder.makeCurrent(plan.getTenantId()));
    }

    private void record(Summary result) {
        successes.addAndGet(result.succeeded());
        failures.addAndGet(result.failed());
        skipped.addAndGet(result.skipped());
        if (result.succeeded() > 0) lastSuccess = System.currentTimeMillis();
        if (result.failed() > 0) lastFailure = System.currentTimeMillis();
    }

    private static String code(Throwable failure) {
        String message = failure.getMessage();
        return message != null && message.matches("error\\.property_finance_[a-z_]+")
                ? message
                : "error.property_finance_month_schedule_failed";
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
                                                                    "Property monthly closing scan"
                                                                            + " failed: {}",
                                                                    lastError);
                                                            return Mono.empty();
                                                        }),
                                0)
                        .subscribe();
        try {
            var name = new ObjectName("org.jetlinks:type=PropertyManagement,name=MonthClosingJob");
            ManagementFactory.getPlatformMBeanServer()
                    .registerMBean(new StandardMBean(this, MonthClosingJobMBean.class), name);
            objectName = name;
        } catch (Exception failure) {
            log.warn("Unable to register monthly closing job MBean");
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
                log.warn("Unable to unregister monthly closing job MBean");
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
