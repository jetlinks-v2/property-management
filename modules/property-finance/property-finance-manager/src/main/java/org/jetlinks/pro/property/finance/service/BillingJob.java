package org.jetlinks.pro.property.finance.service;

import lombok.extern.slf4j.Slf4j;

import org.jetlinks.pro.property.finance.setting.BillingTaskKind;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.ObjectName;
import javax.management.StandardMBean;

/** 轻量定时触发，持久任务租约负责多实例互斥；重启后补跑当天已经到达的计划。 */
@Component
@Slf4j
@EnableConfigurationProperties(BillingJobProperties.class)
public class BillingJob implements SmartLifecycle, BillingJobMBean {
    private final BillingTaskService tasks;
    private final BillingJobProperties properties;
    private final Map<BillingTaskKind, LocalTime> schedules = new EnumMap<>(BillingTaskKind.class);
    private final Map<BillingTaskKind, LocalDate> completed = new EnumMap<>(BillingTaskKind.class);
    private final ZoneId zone;
    private final AtomicLong successes = new AtomicLong(),
            failures = new AtomicLong(),
            skipped = new AtomicLong();
    private volatile long lastSuccess, lastFailure;
    private volatile String lastError = "";
    private volatile boolean running;
    private Disposable subscription;
    private ObjectName objectName;

    public BillingJob(BillingTaskService tasks, BillingJobProperties properties) {
        this.tasks = tasks;
        this.properties = properties;
        zone = ZoneId.of(properties.getZone());
        schedules.put(BillingTaskKind.GENERATE, properties.getGenerateAt());
        schedules.put(BillingTaskKind.ACCRUE, properties.getAccrueAt());
        if (properties.isCloseEnabled())
            schedules.put(BillingTaskKind.CLOSE, properties.getCloseAt());
        schedules.put(BillingTaskKind.LATE_FEE, properties.getLateFeeAt());
    }

    Mono<Void> poll(ZonedDateTime now) {
        return tasks.resume()
                .doOnNext(this::record)
                .thenMany(
                        Flux.fromIterable(schedules.entrySet())
                                .concatMap(entry -> runDue(entry.getKey(), entry.getValue(), now)))
                .then()
                .doOnError(this::recordFailure);
    }

    private Mono<Void> runDue(BillingTaskKind kind, LocalTime at, ZonedDateTime now) {
        var date = now.toLocalDate();
        if (date.equals(completed.get(kind)) || at.isAfter(now.toLocalTime())) return Mono.empty();
        return tasks.scan(kind, date)
                .doOnNext(this::record)
                .doOnSuccess(ignored -> completed.put(kind, date))
                .then();
    }

    private void record(BillingTaskService.RunSummary result) {
        successes.addAndGet(result.succeeded());
        failures.addAndGet(result.failed());
        skipped.addAndGet(result.skipped());
        if (result.succeeded() > 0) lastSuccess = System.currentTimeMillis();
        if (result.failed() > 0) {
            lastFailure = System.currentTimeMillis();
            lastError = "error.property_finance_task_failed";
        }
    }

    private void recordFailure(Throwable failure) {
        failures.incrementAndGet();
        lastFailure = System.currentTimeMillis();
        lastError = "error.property_finance_task_scan_failed";
        log.warn("Property billing task scan failed: {}", failure.getClass().getSimpleName());
    }

    @Override
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (running || !properties.isEnabled()) return;
        running = true;
        subscription =
                Flux.interval(Duration.ofSeconds(5), Duration.ofSeconds(30))
                        .onBackpressureDrop()
                        .concatMap(
                                ignored ->
                                        poll(ZonedDateTime.now(zone))
                                                .onErrorResume(failure -> Mono.empty()))
                        .subscribe();
        try {
            var name = new ObjectName("org.jetlinks:type=PropertyManagement,name=BillingJob");
            ManagementFactory.getPlatformMBeanServer()
                    .registerMBean(new StandardMBean(this, BillingJobMBean.class), name);
            objectName = name;
        } catch (Exception failure) {
            log.warn("Unable to register billing job MBean");
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
        if (subscription != null) subscription.dispose();
        if (objectName != null)
            try {
                ManagementFactory.getPlatformMBeanServer().unregisterMBean(objectName);
                objectName = null;
            } catch (Exception failure) {
                log.warn("Unable to unregister billing job MBean");
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
}
