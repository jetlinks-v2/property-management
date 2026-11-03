package org.jetlinks.pro.property.base.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.management.ObjectName;
import javax.management.StandardMBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** 应用准备后首次同步，此后按可配置周期重试；多实例执行权由数据库租约裁决。 */
@Component
@Slf4j
public class SpaceSyncJob implements SmartLifecycle, SpaceSyncJobMBean {
    private final SpaceSyncService sync;
    private final Duration interval;
    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile long lastSuccess;
    private volatile boolean running;
    private Disposable task;
    private ObjectName objectName;

    public SpaceSyncJob(
            SpaceSyncService sync,
            @Value("${property.space.sync-interval:1h}") Duration interval) {
        this.sync = sync;
        if (interval.compareTo(Duration.ofSeconds(5)) < 0)
            throw new IllegalArgumentException("Space sync interval must be >= 5s");
        this.interval = interval;
    }

    @Override
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (running) return;
        running = true;
        // 同步超过周期时丢弃中间 tick，不排队，也不让 interval 背压溢出终止常驻任务。
        task =
                Flux.interval(Duration.ofSeconds(5), interval)
                        .onBackpressureDrop()
                        .concatMap(
                                ignored ->
                                        sync.synchronize()
                                                .doOnNext(
                                                        result -> {
                                                            successes.incrementAndGet();
                                                            lastSuccess = result.completedAt();
                                                        })
                                                .onErrorResume(
                                                        error -> {
                                                            failures.incrementAndGet();
                                                            log.warn(
                                                                    "Property space sync failed: {}",
                                                                    error.getClass()
                                                                            .getSimpleName());
                                                            return Mono.empty();
                                                        }))
                        .subscribe();
        try {
            var name = new ObjectName("org.jetlinks:type=PropertyManagement,name=SpaceSync");
            ManagementFactory.getPlatformMBeanServer()
                    .registerMBean(new StandardMBean(this, SpaceSyncJobMBean.class), name);
            objectName = name;
        } catch (Exception error) {
            log.warn("Unable to register space sync MBean");
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
    public long getSuccessCount() {
        return successes.get();
    }

    @Override
    public long getFailureCount() {
        return failures.get();
    }

    @Override
    public long getLastSuccessTime() {
        return lastSuccess;
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (task != null) {
            task.dispose();
            task = null;
        }
        if (objectName != null) {
            try {
                ManagementFactory.getPlatformMBeanServer().unregisterMBean(objectName);
            } catch (Exception error) {
                log.warn("Unable to unregister space sync MBean");
            }
            objectName = null;
        }
    }
}
