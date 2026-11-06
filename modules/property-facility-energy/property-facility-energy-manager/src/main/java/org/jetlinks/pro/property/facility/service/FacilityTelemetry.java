package org.jetlinks.pro.property.facility.service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.sdk.server.device.DeviceProperty;
import org.springframework.stereotype.Service;
import reactor.core.*;
import reactor.core.publisher.*;

/** 后台采集先补历史再订阅实时，生命周期归Job统一管理；失败不推进游标。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FacilityTelemetry {

  private final FacilityCloud cloud;
  private final FacilityReadings readings;
  private final FacilityDevices devices;
  private final MappingStore mappings;
  private final FacilityExecution execution;
  private final Map<String, Disposable> subscriptions =
    new ConcurrentHashMap<>();
  private final Map<String, Long> versions = new ConcurrentHashMap<>();

  public Mono<Void> collect(MappingEntity mapping) {
    return devices
      .read(mapping.getDeviceId())
      .doOnNext(d -> {
        FacilityDevices.requireActive(d);
        FacilityScope.require(
          Boolean.TRUE.equals(d.getEnergyEnabled()),
          "device_unavailable"
        );
      })
      .flatMap(device -> {
        return backfill(mapping).then(
          Mono.fromRunnable(() -> subscribe(mapping, device.getCloudDeviceId()))
        );
      });
  }

  private Mono<Void> backfill(MappingEntity mapping) {
    return collectHistory(mapping)
      .then(recordCollection(mapping, null))
      .onErrorResume(error ->
        recordCollection(mapping, error).then(Mono.error(error))
      )
      .as(MonoTracer.create("/property/facility-energy/energy/collection"));
  }

  /** Status is server-owned and version-scoped; it never advances the source cursor on failure. */
  private Mono<Void> recordCollection(MappingEntity mapping, Throwable error) {
    return Mono.defer(() -> {
      long now = System.currentTimeMillis();
      var update = mappings.createUpdate().set("lastCollectionTime", now);
      if (error == null) {
        update.set("lastCollectionSuccessTime", now).setNull("collectionError");
      } else {
        update.set("collectionError", error.getClass().getSimpleName());
      }
      return update.where("id", mapping.getId())
        .and("version", mapping.getVersion())
        .and("enabled", true)
        .and("deleted", false)
        .execute().then();
    });
  }

  private Mono<Void> collectHistory(MappingEntity mapping) {
    return devices.read(mapping.getDeviceId()).flatMap(device -> {
      FacilityDevices.requireActive(device);
      FacilityScope.require(
        Boolean.TRUE.equals(mapping.getEnabled()) &&
          Boolean.TRUE.equals(device.getEnergyEnabled()),
        "device_unavailable"
      );
      long until = System.currentTimeMillis(),
        from = Math.max(
          mapping.getCreateTime(),
          mapping.getLastCollectedTime() == null
            ? mapping.getCreateTime()
            : mapping.getLastCollectedTime() - 7L * 86400000
        );
      return Flux.fromIterable(mapping.getMetrics())
        .concatMap(metric ->
          cloud
            .history(
              device.getCloudDeviceId(),
              metric.getPropertyId(),
              from,
              until
            )
            .concatMap(p -> readings.ingest(mapping, metric, p))
        )
        .then(
          mappings
            .createUpdate()
            .set("lastCollectedTime", until)
            .where("id", mapping.getId())
            .and("version", mapping.getVersion())
            .execute()
        )
        .then();
    });
  }

  private void subscribe(MappingEntity mapping, String cloudId) {
    String key = mapping.getId();
    var current = subscriptions.get(key);
    if (
      current != null &&
      !current.isDisposed() &&
      Objects.equals(versions.get(key), mapping.getVersion())
    ) return;
    if (current != null) current.dispose();
    versions.put(key, mapping.getVersion());
    // Only this lifecycle boundary subscribes. Each asynchronous sample restores current application authorization.
    var disposable = execution
      .asOwnerFlux(mapping, () ->
        cloud.subscribe(
          List.of(cloudId),
          mapping
            .getMetrics()
            .stream()
            .map(m -> m.getPropertyId())
            .toList()
        )
      )
      // The SDK notification omits original timestamp/message ID. Use it as a wakeup,
      // re-read canonical historical samples; never invent receive-time increment IDs.
      .concatMap(values ->
        execution.asOwner(mapping, () ->
          mappings.findById(mapping.getId()).flatMap(this::backfill)
        )
      )
      .timeout(Duration.ofHours(2))
      .doOnError(e ->
        log.warn(
          "Facility subscription stopped: mapping={}, type={}",
          key,
          e.getClass().getSimpleName()
        )
      )
      .subscribe(v -> {}, e -> {});
    subscriptions.put(key, disposable);
  }

  public void reconcile(Set<String> active) {
    subscriptions.keySet().removeIf(id -> {
      if (active.contains(id)) return false;
      subscriptions.get(id).dispose();
      versions.remove(id);
      return true;
    });
  }

  public void stop() {
    subscriptions.values().forEach(Disposable::dispose);
    subscriptions.clear();
    versions.clear();
  }

  public int activeSubscriptions() {
    return (int) subscriptions
      .values()
      .stream()
      .filter(d -> !d.isDisposed())
      .count();
  }
}
