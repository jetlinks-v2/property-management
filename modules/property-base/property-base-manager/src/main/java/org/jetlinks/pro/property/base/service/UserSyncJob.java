package org.jetlinks.pro.property.base.service;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import javax.management.ObjectName;
import javax.management.StandardMBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/** 生命周期托管的单消费者队列；HTTP 只提交任务，停止服务时取消订阅并释放执行中的租约。 */
@Component
@Slf4j
public class UserSyncJob implements SmartLifecycle, UserSyncJobMBean {
    private final UserSyncService sync;
    private final boolean enabled;
    private final Duration interval;
    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile long lastSuccess;
    private volatile boolean running;
    private Sinks.Many<String> queue;
    private Disposable.Composite tasks;
    private ObjectName objectName;

    public UserSyncJob(
            UserSyncService sync,
            @Value("${property.user-sync.enabled:true}") boolean enabled,
            @Value("${property.user-sync.interval-seconds:3600}") long seconds) {
        this.sync = sync;
        this.enabled = enabled;
        this.interval = Duration.ofSeconds(Math.max(5, seconds));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        if (enabled) start();
    }

    @Override
    public synchronized void start() {
        if (running) return;
        queue = Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(1));
        tasks = Disposables.composite();
        running = true;
        tasks.add(
                queue.asFlux()
                        .concatMap(
                                task ->
                                        sync.execute(task)
                                                .doOnNext(
                                                        result -> {
                                                            successes.incrementAndGet();
                                                            lastSuccess = result.completedTime();
                                                        })
                                                .onErrorResume(
                                                        error -> {
                                                            failures.incrementAndGet();
                                                            log.warn(
                                                                    "Property user sync failed: {}",
                                                                    error.getClass()
                                                                            .getSimpleName());
                                                            return Mono.empty();
                                                        }))
                        .subscribe());
        tasks.add(
                Flux.interval(Duration.ofSeconds(5), interval)
                        .onBackpressureDrop()
                        .concatMap(
                                tick ->
                                        submit().onErrorResume(
                                                        error -> {
                                                            if (!(error
                                                                            instanceof
                                                                            BusinessException b
                                                                    && b.getStatus() == 409))
                                                                log.warn(
                                                                        "Unable to schedule property user sync: {}",
                                                                        error.getClass()
                                                                                .getSimpleName());
                                                            return Mono.empty();
                                                        }))
                        .subscribe());
        try {
            objectName = new ObjectName("org.jetlinks:type=PropertyManagement,name=UserSync");
            ManagementFactory.getPlatformMBeanServer()
                    .registerMBean(new StandardMBean(this, UserSyncJobMBean.class), objectName);
        } catch (Exception error) {
            log.warn("Unable to register user sync MBean");
        }
    }

    public Mono<UserSyncService.Status> submit() {
        return Mono.defer(
                () -> {
                    if (!running)
                        return Mono.error(
                                new BusinessException.NoStackTrace(
                                        "error.property_user_sync_unavailable", 503));
                    return sync.acquire()
                            .flatMap(
                                    taskId -> {
                                        var result = queue.tryEmitNext(taskId);
                                        if (result.isFailure())
                                            return sync.fail(
                                                            taskId,
                                                            new IllegalStateException(
                                                                    result.name()))
                                                    .then(
                                                            Mono.error(
                                                                    new BusinessException
                                                                            .NoStackTrace(
                                                                            "error.property_user_sync_unavailable",
                                                                            503)));
                                        return Mono.just(
                                                new UserSyncService.Status(
                                                        taskId, "running", true, null, null, null,
                                                        null, null, null));
                                    });
                });
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
        if (tasks != null) tasks.dispose();
        if (queue != null) queue.tryEmitComplete();
        if (objectName != null)
            try {
                ManagementFactory.getPlatformMBeanServer().unregisterMBean(objectName);
            } catch (Exception error) {
                log.warn("Unable to unregister user sync MBean");
            }
        objectName = null;
    }
}
