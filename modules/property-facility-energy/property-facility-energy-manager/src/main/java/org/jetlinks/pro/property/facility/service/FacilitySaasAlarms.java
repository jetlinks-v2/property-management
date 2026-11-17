package org.jetlinks.pro.property.facility.service;

import java.util.*;
import java.util.concurrent.atomic.*;
import java.time.Duration;
import java.lang.management.ManagementFactory;
import javax.management.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.plugin.context.ClusterPluginScheduler;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.DeviceEntity;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.*;

/** 平台未提供本模块可用的告警订阅SDK，按授权设备轮询完整记录，重启可补取平台保留记录。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FacilitySaasAlarms implements SmartLifecycle, FacilitySaasAlarmsMBean {
  private final DeviceStore store;
  private final FacilityDevices devices;
  private final FacilityExecution execution;
  private final FacilityCloudAlarms cloud;
  private final FacilitySaasAlarmWriter writer;
  private final AtomicBoolean polling = new AtomicBoolean();
  private final AtomicLong failures = new AtomicLong(), processed = new AtomicLong();
  private final ClusterPluginScheduler scheduler = new ClusterPluginScheduler("property-facility-energy");
  private volatile boolean running;
  private volatile long completed;
  private volatile String lastError;
  private Disposable task;
  private ObjectName objectName;
  @org.springframework.beans.factory.annotation.Value("${facility.jobs.enabled:true}")
  private boolean jobsEnabled = true;

  public Mono<Integer> refresh() {
    var query = QueryParamEntity.newQuery().is("syncState", "active").notNull("projectId").noPaging().getParam();
    return devices.currentQuery(query, FacilityAssetType.facilityDevice, "id")
      .flatMapMany(q -> store.createQuery().setParam(q).fetch()).take(20001).collectList()
      .doOnNext(rows -> FacilityScope.require(rows.size() <= 20000, "too_many"))
      .flatMapMany(Flux::fromIterable).concatMap(device -> cloud.records(device.getCloudDeviceId())
        .flatMapMany(Flux::fromIterable).concatMap(source -> writer.accept(device, source).thenReturn(1)))
      .reduce(0, Integer::sum).doOnNext(processed::addAndGet)
      .as(MonoTracer.create("/property/facility-energy/alerts/sync"));
  }

  public Mono<Void> poll() {
    return Mono.defer(() -> {
      if (!polling.compareAndSet(false, true)) return Mono.empty();
      // 后台扫描只用于分组恢复真实身份；每组 refresh 仍经过 AssetsHolder 和当前应用绑定交集。
      return store.createQuery().where("deleted", false).and("syncState", "active").notNull("projectId")
        .fetch().take(20001).collectList()
        .doOnNext(rows -> FacilityScope.require(rows.size() <= 20000, "too_many"))
        .flatMapMany(rows -> Flux.fromIterable(rows.stream().collect(java.util.stream.Collectors.toMap(
          row -> List.of(row.getTenantId(), row.getApplicationId(), row.getCreatorId()), row -> row, (a, b) -> a)).values()))
        .concatMap(row -> execution.asOwner(row, this::refresh).onErrorResume(error -> {
          failures.incrementAndGet(); lastError = error.getClass().getSimpleName();
          log.warn("Facility SaaS alarm sync failed: application={}, type={}", row.getApplicationId(), lastError);
          return Mono.empty();
        })).then().doOnError(error -> {
          failures.incrementAndGet(); lastError = error.getClass().getSimpleName();
          log.warn("Facility SaaS alarm scan failed: type={}", lastError);
        }).doOnSuccess(ignore -> completed = System.currentTimeMillis())
        .doFinally(ignore -> polling.set(false));
    });
  }

  public synchronized void start() {
    if (running || !jobsEnabled) return;
    task = scheduler.interval("facility-saas-alarms", Mono.defer(this::poll), Duration.ofSeconds(30), true);
    running = true;
    try {
      objectName = new ObjectName("org.jetlinks:type=PropertyFacilityEnergy,name=SaasAlarms");
      ManagementFactory.getPlatformMBeanServer().registerMBean(new StandardMBean(this, FacilitySaasAlarmsMBean.class), objectName);
    } catch (JMException error) { log.warn("Facility SaaS alarm MBean unavailable", error); }
  }
  public synchronized void stop() {
    running = false; if (task != null) task.dispose();
    if (objectName != null) try { ManagementFactory.getPlatformMBeanServer().unregisterMBean(objectName); }
    catch (JMException error) { log.warn("Facility SaaS alarm MBean unregister failed", error); }
    objectName = null;
  }
  public boolean isRunning() { return running; }
  public boolean isPolling() { return polling.get(); }
  public long getCompletedTime() { return completed; }
  public long getFailureCount() { return failures.get(); }
  public long getProcessedCount() { return processed.get(); }
  public String getLastError() { return lastError; }
}
