package org.jetlinks.pro.property.facility.service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
import org.jetlinks.pro.property.facility.entity.AlertEntity;
import org.jetlinks.pro.property.facility.model.CloudAlarmRecord;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.*;
import reactor.util.retry.Retry;

/**
 * 按当前应用身份维持跨服务告警 Topic，落库及通知幂等由 writer 负责。
 * 集群任务维护长连接并在停机/失去调度时释放；低频核对补齐启动、断线或事件投递前后的状态缺口。
 * @see FacilityAlarmTopics#watch(Set)
 * @see FacilitySaasAlarmWriter
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FacilitySaasAlarms implements SmartLifecycle, FacilitySaasAlarmsMBean {
  private static final long LOOKBACK = Duration.ofMinutes(10).toMillis();
  private static final long RECOVERY_WINDOW = Duration.ofHours(1).toMillis();
  private final DeviceStore store;
  private final FacilityDevices devices;
  private final FacilityExecution execution;
  private final FacilityCloudAlarms cloud;
  private final FacilitySaasAlarmWriter writer;
  private final FacilityAlarmTopics topics;
  private final AlertStore alerts;
  private final AtomicBoolean polling = new AtomicBoolean();
  private final AtomicLong failures = new AtomicLong(), processed = new AtomicLong();
  private final ClusterPluginScheduler scheduler = new ClusterPluginScheduler("property-facility-energy");
  private final Map<List<String>, Disposable.Swap> subscriptions = new HashMap<>();
  private final Map<List<String>, Set<String>> subscribedDevices = new HashMap<>();
  private final Set<String> reconciling = ConcurrentHashMap.newKeySet();
  private record Selection(DeviceEntity owner, Set<String> devices) {}
  private volatile long maintained;
  private volatile boolean running;
  private volatile long completed;
  private volatile String lastError;
  private Disposable task;
  private ObjectName objectName;
  @org.springframework.beans.factory.annotation.Value("${facility.jobs.enabled:true}")
  private boolean jobsEnabled = true;

  public Mono<Integer> refresh() {
    return reconcile(null);
  }

  Mono<Integer> reconcile(DeviceEntity owner) {
    var query = QueryParamEntity.newQuery().is("syncState", "active").notNull("projectId").noPaging();
    if (owner != null) query.and("creatorId", owner.getCreatorId());
    return devices.currentQuery(query.getParam(), FacilityAssetType.facilityDevice, "id")
      .flatMapMany(this::selectedDevices)
      .concatMap(device -> {
        var refresh = reconcileDevice(device, owner != null);
        return owner == null ? refresh : refresh.onErrorResume(error -> {
          failure(device, error);
          return Mono.empty();
        });
      })
      .reduce(0, Integer::sum).doOnNext(processed::addAndGet)
      .as(MonoTracer.create("/property/facility-energy/alerts/sync"));
  }

  Mono<Integer> reconcileDevice(DeviceEntity device, boolean advance) {
    return Mono.defer(() -> {
      if (!reconciling.add(device.getId())) return Mono.just(0);
      long now = System.currentTimeMillis();
      Long cursor = device.getSaasAlarmSyncedThrough();
      boolean initial = cursor == null;
      long from = initial || !advance ? now - RECOVERY_WINDOW : cursor - LOOKBACK;
      long until = initial || !advance ? now : Math.min(now, cursor + RECOVERY_WINDOW);
      var changed = Mono.defer(() -> cloud.changes(device.getCloudDeviceId(), from, until, initial))
        .flatMapMany(Flux::fromIterable)
        .concatMap(source -> writer.accept(device, source).thenReturn(1))
        .reduce(0, Integer::sum);
      if (initial && advance) changed = changed.flatMap(count -> reconcileOpenRecords(device).map(recovered -> count + recovered));
      // Only the scheduled owner can advance this cursor. A user's manual refresh can see fewer records.
      return changed.flatMap(count -> advance && (initial || until > cursor)
        ? store.advanceAlarmCursor(device, until).thenReturn(count) : Mono.just(count))
        .doFinally(signal -> reconciling.remove(device.getId()));
    });
  }

  private Mono<Integer> reconcileOpenRecords(DeviceEntity device) {
    // 首次补齐窗口外的解除，只查本地仍在告警的来源记录；缺失记录不能推断为已解除。
    return alerts.createQuery().where("tenantId", device.getTenantId()).and("applicationId", device.getApplicationId())
      .and("deviceId", device.getId()).and("projectId", device.getProjectId()).and("deleted", false)
      .and("ruleType", "saas").and("state", "open").notNull("sourceRecordId")
      .fetch().map(AlertEntity::getSourceRecordId).filter(id -> !id.isBlank()).distinct()
      .take(20001).collectList().doOnNext(ids -> FacilityScope.require(ids.size() <= 20000, "too_many"))
      .flatMapMany(Flux::fromIterable).buffer(100)
      .concatMap(ids -> cloud.records(device.getCloudDeviceId(), ids).doOnNext(records -> {
        if (records.size() < ids.size()) log.warn("SaaS alarm record unavailable during initial sync: device={}, missing={}",
          device.getId(), ids.size() - records.size());
      }))
      .concatMapIterable(records -> records)
      .filter(record -> "normal".equals(record.stateValue()))
      .concatMap(source -> writer.accept(device, source).thenReturn(1))
      .reduce(0, Integer::sum);
  }

  Mono<Integer> refresh(CloudAlarmRecord signal) {
    String id = signal.getAlarmRecordId() == null ? signal.getId() : signal.getAlarmRecordId();
    if (id == null || id.isBlank()) return Mono.just(0);
    var ids = new HashSet<String>();
    if ("device".equals(signal.getSourceType()) && signal.getSourceId() != null) ids.add(signal.getSourceId());
    if ("device".equals(signal.getTargetType()) && signal.getTargetId() != null) ids.add(signal.getTargetId());
    if (ids.isEmpty()) return Mono.just(0);
    var query = QueryParamEntity.newQuery().is("syncState", "active").notNull("projectId")
      .in("cloudDeviceId", ids).noPaging().getParam();
    return devices.currentDeviceQuery(query, ids)
      .flatMapMany(this::selectedDevices)
      .concatMap(device -> cloud.record(device.getCloudDeviceId(), id)
        .flatMap(source -> writer.accept(device, source).thenReturn(1)))
      .reduce(0, Integer::sum).doOnNext(processed::addAndGet)
      .as(MonoTracer.create("/property/facility-energy/alerts/event"));
  }

  private Flux<DeviceEntity> selectedDevices(QueryParamEntity query) {
    return store.createQuery().setParam(query).fetch().take(20001).collectList()
      .doOnNext(rows -> FacilityScope.require(rows.size() <= 20000, "too_many"))
      .flatMapMany(Flux::fromIterable);
  }

  private Flux<DeviceEntity> identities() {
    // 扫描只恢复真实身份；告警查询及每次消息落库仍经过当前应用绑定与 AssetsHolder。
    return store.createQuery().where("deleted", false).and("syncState", "active").notNull("projectId")
      .fetch().take(20001).collectList()
      .doOnNext(rows -> FacilityScope.require(rows.size() <= 20000, "too_many"))
      .flatMapMany(rows -> Flux.fromIterable(rows.stream().collect(java.util.stream.Collectors.toMap(
        FacilitySaasAlarms::identity, row -> row, (a, b) -> a)).values()));
  }

  private static List<String> identity(DeviceEntity row) {
    return List.of(row.getTenantId(), row.getApplicationId(), row.getCreatorId());
  }

  Mono<Set<String>> subscriptionDevices() {
    var query = QueryParamEntity.newQuery().is("syncState", "active").notNull("projectId").noPaging().getParam();
    return devices.currentQuery(query, FacilityAssetType.facilityDevice, "id")
      .flatMapMany(q -> store.createQuery().setParam(q).fetch()).take(20001).collectList()
      .doOnNext(rows -> FacilityScope.require(rows.size() <= 20000, "too_many"))
      .map(rows -> rows.stream().map(DeviceEntity::getCloudDeviceId).filter(Objects::nonNull)
        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
  }

  Mono<Void> maintain() {
    return identities().concatMap(row -> execution.asOwner(row, this::subscriptionDevices)
        .map(ids -> new Selection(row, ids)).onErrorResume(error -> {
          failure(row, error); return Mono.empty();
        }))
      .collectList().doOnNext(this::maintain).then().doOnError(error -> {
        failures.incrementAndGet(); lastError = error.getClass().getSimpleName();
        log.warn("Facility SaaS alarm subscription scan failed: type={}", lastError);
      });
  }

  private synchronized void maintain(List<Selection> rows) {
    if (!running) return;
    maintained = System.nanoTime();
    var selected = new HashMap<List<String>, Selection>();
    rows.stream().filter(row -> !row.devices().isEmpty()).forEach(row -> selected.put(identity(row.owner()), row));
    subscriptions.entrySet().removeIf(entry -> {
      var row = selected.get(entry.getKey());
      if (row != null && row.devices().equals(subscribedDevices.get(entry.getKey())) && !entry.getValue().isDisposed()) return false;
      entry.getValue().dispose(); subscribedDevices.remove(entry.getKey()); return true;
    });
    for (var row : selected.values()) {
      var key = identity(row.owner());
      if (subscriptions.containsKey(key)) continue;
      var slot = Disposables.swap(); subscriptions.put(key, slot); subscribedDevices.put(key, row.devices());
      slot.update(listen(row.owner(), row.devices()).subscribe(ignore -> {}, error -> {
        failure(row.owner(), error); slot.dispose();
      }));
    }
  }

  Flux<Integer> listen(DeviceEntity row, Set<String> ids) {
    return Flux.defer(() -> {
      // 总线传入的是变更信号，不直接信任正文；每条消息重新恢复当前身份并查应用绑定与资产权限。
      var signals = topics.watch(ids).concatWith(Flux.error(new IllegalStateException("SaaS alarm subscription closed")));
      var catchup = new CloudAlarmRecord();
      return Flux.merge(signals, Flux.just(catchup)).concatMap(signal -> execution.asOwner(row,
        () -> signal == catchup ? reconcile(row) : refresh(signal)).onErrorResume(error -> {
          failure(row, error); return Mono.empty();
        }), 1);
    }).doOnError(error -> failure(row, error))
      .retryWhen(Retry.backoff(5, Duration.ofSeconds(2)).maxBackoff(Duration.ofSeconds(30)));
  }

  private void failure(DeviceEntity row, Throwable error) {
    failures.incrementAndGet(); lastError = error.getClass().getSimpleName();
    log.warn("Facility SaaS alarm subscription/sync failed: application={}, type={}", row.getApplicationId(), lastError);
  }

  private synchronized void expireSubscriptions() {
    // 集群调度转移后旧节点不能继续保有长连接；90 秒租约到期后定时释放，重叠投递由 writer 的稳定键幂等。
    if (System.nanoTime() - maintained > Duration.ofSeconds(90).toNanos()) closeSubscriptions();
  }

  private void closeSubscriptions() {
    subscriptions.values().forEach(Disposable::dispose); subscriptions.clear(); subscribedDevices.clear();
  }

  public Mono<Void> poll() {
    return Mono.defer(() -> {
      if (!polling.compareAndSet(false, true)) return Mono.empty();
      return identities()
        .concatMap(row -> execution.asOwner(row, () -> reconcile(row)).onErrorResume(error -> {
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
    running = true;
    var jobs = Disposables.composite();
    task = jobs;
    jobs.add(scheduler.interval("facility-saas-alarms", Mono.defer(this::poll), Duration.ofMinutes(5), true));
    if (topics.isRealtimeAvailable()) {
      jobs.add(scheduler.interval("facility-saas-alarm-subscriptions", Mono.defer(this::maintain), Duration.ofSeconds(30), true));
      jobs.add(scheduler.interval("facility-saas-alarm-lease", Mono.fromRunnable(this::expireSubscriptions), Duration.ofSeconds(30), false));
    } else {
      log.warn("Facility SaaS alarm event namespace differs from the platform bus namespace; syncing alarms every 5 minutes");
    }
    try {
      objectName = new ObjectName("org.jetlinks:type=PropertyFacilityEnergy,name=SaasAlarms");
      ManagementFactory.getPlatformMBeanServer().registerMBean(new StandardMBean(this, FacilitySaasAlarmsMBean.class), objectName);
    } catch (JMException error) { log.warn("Facility SaaS alarm MBean unavailable", error); }
  }
  public synchronized void stop() {
    running = false; if (task != null) task.dispose();
    closeSubscriptions();
    if (objectName != null) try { ManagementFactory.getPlatformMBeanServer().unregisterMBean(objectName); }
    catch (JMException error) { log.warn("Facility SaaS alarm MBean unregister failed", error); }
    objectName = null;
  }
  public boolean isRunning() { return running; }
  public boolean isPolling() { return polling.get(); }
  public long getCompletedTime() { return completed; }
  public long getFailureCount() { return failures.get(); }
  public long getProcessedCount() { return processed.get(); }
  public synchronized int getSubscriptionCount() { return subscriptions.size(); }
  public String getLastError() { return lastError; }
}
