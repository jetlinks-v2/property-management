package org.jetlinks.pro.property.facility.service;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

/** 按历史来源的采样时间增量采集，全部指标成功后才推进游标。 */
@Service
@RequiredArgsConstructor
public class FacilityTelemetry {

  private static final long LATE_ARRIVAL_WINDOW = Duration.ofMinutes(10).toMillis();
  private static final long RECOVERY_WINDOW = Duration.ofHours(1).toMillis();
  private final FacilityCloud cloud;
  private final FacilityReadings readings;
  private final FacilityDevices devices;
  private final MappingStore mappings;

  public Mono<Void> collect(MappingEntity mapping) {
    return collectCurrent(mapping).filter(Boolean.TRUE::equals)
      .switchIfEmpty(Mono.error(new org.hswebframework.web.authorization.exception.AccessDenyException())).then();
  }

  /** 补采单个缺失时段时不跨过常规采集尚未完成的时间段。 */
  public Mono<Void> collect(MappingEntity mapping, long from, long until) {
    return Mono.defer(() -> {
      FacilityScope.require(from >= mapping.getCreateTime() && from < until
        && until <= System.currentTimeMillis() && until - from <= RECOVERY_WINDOW, "invalid_period");
      return devices.read(mapping.getDeviceId()).flatMap(devices::requireBound).flatMap(device ->
        recordResult(mapping, collectHistory(mapping, device, from, until)));
    });
  }

  /** 解绑是正常暂停，不推进游标。 */
  public Mono<Boolean> collectCurrent(MappingEntity mapping) {
    return devices.read(mapping.getDeviceId()).flatMap(device ->
      devices.isBound(device).flatMap(bound -> bound
        ? recordResult(mapping, collectIncrement(mapping, device)).thenReturn(true)
        : Mono.just(false)));
  }

  private Mono<Void> collectIncrement(MappingEntity mapping, DeviceEntity device) {
    var previous = mapping.getLastCollectedTime();
    long cursor = previous == null ? mapping.getCreateTime() : previous;
    long from = Math.max(mapping.getCreateTime(), cursor - LATE_ARRIVAL_WINDOW);
    long until = Math.min(System.currentTimeMillis(), cursor + RECOVERY_WINDOW);
    if (from >= until) return Mono.empty();
    return collectHistory(mapping, device, from, until).then(Mono.defer(() -> {
      if (until <= cursor) return Mono.empty();
      var update = mappings.createUpdate().set("lastCollectedTime", until)
        .where("id", mapping.getId()).and("version", mapping.getVersion())
        .and("enabled", true).and("deleted", false);
      // Manual and scheduled runs can overlap. An older result cannot overwrite a newer cursor.
      if (previous == null) update.isNull("lastCollectedTime"); else update.and("lastCollectedTime", previous);
      return update.execute();
    })).then();
  }

  private Mono<Void> recordResult(MappingEntity mapping, Mono<Void> collection) {
    return collection.then(recordCollection(mapping, null))
      .onErrorResume(error ->
        error instanceof org.hswebframework.web.authorization.exception.AccessDenyException
          ? Mono.error(error) : recordCollection(mapping, error).then(Mono.error(error))
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

  private Mono<Void> collectHistory(MappingEntity mapping, DeviceEntity device, long from, long until) {
    return Mono.defer(() -> {
      FacilityDevices.requireActive(device);
      FacilityScope.require(
        Boolean.TRUE.equals(mapping.getEnabled()) &&
          Boolean.TRUE.equals(device.getEnergyEnabled()),
        "device_unavailable"
      );
      return Flux.fromIterable(mapping.getMetrics()).concatMap(metric ->
        cloud.history(device.getCloudDeviceId(), metric.getPropertyId(), from, until)
          .buffer(200).concatMap(batch -> readings.ingestBatch(mapping, metric, batch))).then();
    });
  }
}
