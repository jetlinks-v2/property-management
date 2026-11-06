package org.jetlinks.pro.property.facility.service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.management.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.plugin.context.ClusterPluginScheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.*;

/** 集群单执行轮询、持久化任务CAS、真实身份恢复；逐项失败隔离且保留来源游标。 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
  name = "facility.jobs.enabled",
  havingValue = "true",
  matchIfMissing = true
)
public class FacilityJobs implements SmartLifecycle, FacilityJobsMBean {

  private final JobStore jobs;
  private final PlanStore plans;
  private final MappingStore mappings;
  private final RuleStore rules;
  private final FacilityExecution execution;
  private final FacilitySync sync;
  private final FacilityPlans planManager;
  private final FacilityTelemetry telemetry;
  private final FacilityAlerts alerts;
  private final FacilityNotifications notifications;
  private final ClusterPluginScheduler scheduler = new ClusterPluginScheduler(
    "property-facility-energy"
  );
  private final AtomicBoolean polling = new AtomicBoolean();
  private final AtomicLong failures = new AtomicLong(),
    synced = new AtomicLong(),
    collected = new AtomicLong();
  private volatile boolean running;
  private volatile long completed;
  private Disposable task;
  private ObjectName objectName;

  public Mono<Void> poll() {
    return Mono.defer(() -> {
      if (!polling.compareAndSet(false, true)) return Mono.empty();
      long now = System.currentTimeMillis();
      return jobs
        .createQuery()
        .where("kind", "sync")
        .and("enabled", true)
        .lte("nextRunTime", now)
        .lte("leaseUntil", now)
        .fetch()
        .take(20)
        .concatMap(row ->
          execution
            .asOwner(row, () -> sync.run(row))
            .doOnNext(v -> synced.incrementAndGet())
            .onErrorResume(e -> failure("sync", row.getId(), e))
        )
        .thenMany(
          plans
            .createQuery()
            .where("enabled", true)
            .and("deleted", false)
            .lte("nextRunTime", now)
            .fetch()
            .take(100)
        )
        .concatMap(row ->
          execution
            .asOwner(row, () -> planManager.generate(row.getId(), false))
            .onErrorResume(e -> failure("plan", row.getId(), e))
        )
        .thenMany(
          mappings
            .createQuery()
            .where("enabled", true)
            .and("deleted", false)
            // Stream all applications with bounded demand; a fixed prefix starves later mappings.
            .fetch()
            .limitRate(32)
        )
        .concatMap(row ->
          execution
            .asOwner(row, () -> telemetry.collect(row))
            .thenReturn(row.getId())
            .doOnNext(v -> collected.incrementAndGet())
            .onErrorResume(e -> failure("telemetry", row.getId(), e))
        )
        .collect(java.util.stream.Collectors.toSet())
        .doOnNext(telemetry::reconcile)
        .thenMany(
          rules
            .createQuery()
            .where("enabled", true)
            .and("deleted", false)
            // Rule evaluation must cover the same complete configuration set on every poll.
            .fetch()
            .limitRate(32)
        )
        .concatMap(row ->
          execution
            .asOwner(row, () -> alerts.evaluate(row))
            .onErrorResume(e -> failure("rule", row.getId(), e))
        )
        .then(notifications.poll())
        .doOnSuccess(v -> completed = System.currentTimeMillis())
        .doFinally(s -> polling.set(false))
        .as(MonoTracer.create("/property/facility-energy/jobs"));
    });
  }

  private <T> Mono<T> failure(String kind, String id, Throwable e) {
    failures.incrementAndGet();
    log.warn(
      "Facility job failed: kind={}, id={}, type={}",
      kind,
      id,
      e.getClass().getSimpleName()
    );
    return Mono.empty();
  }

  public synchronized void start() {
    if (running) return;
    task = scheduler.interval(
      "facility-jobs",
      Mono.defer(this::poll),
      Duration.ofSeconds(30),
      true
    );
    running = true;
    try {
      objectName = new ObjectName(
        "org.jetlinks:type=PropertyFacilityEnergy,name=Jobs"
      );
      ManagementFactory.getPlatformMBeanServer().registerMBean(
        new StandardMBean(this, FacilityJobsMBean.class),
        objectName
      );
    } catch (Exception e) {
      log.warn("Facility MBean unavailable", e);
    }
  }

  public synchronized void stop() {
    running = false;
    if (task != null) task.dispose();
    telemetry.stop();
    if (objectName != null) try {
      ManagementFactory.getPlatformMBeanServer().unregisterMBean(objectName);
    } catch (Exception e) {
      log.warn("Facility MBean unregister failed", e);
    }
    objectName = null;
  }

  public boolean isRunning() {
    return running;
  }

  public boolean isPolling() {
    return polling.get();
  }

  public long getLastCompletedTime() {
    return completed;
  }

  public long getFailureCount() {
    return failures.get();
  }

  public long getSyncCount() {
    return synced.get();
  }

  public long getCollectionCount() {
    return collected.get();
  }

  public int getActiveSubscriptions() {
    return telemetry.activeSubscriptions();
  }
}
