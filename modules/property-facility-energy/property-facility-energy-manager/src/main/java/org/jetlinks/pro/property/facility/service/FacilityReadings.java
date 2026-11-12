package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.*;
import org.jetlinks.sdk.server.device.DeviceProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.*;

/** 标准化、补录审核和换表区间的owner；原始来源保留，人工修改不回写Cloud。 */
@Service
@RequiredArgsConstructor
public class FacilityReadings {

  private final DeviceStore deviceStore;
  private final ReadingStore readings;
  private final MappingStore mappings;
  private final ReplacementStore replacements;
  private final FacilityMappings mappingManager;
  private final FacilityDevices devices;
  private final FacilityScope scope;
  private final FacilityRecords records;

  /** 抄表列表只列计量读数；先与用户条件及资产范围取交集，再计数和分页。 */
  public Mono<PagerResult<ReadingEntity>> page(QueryParamEntity query) {
    var metering = query.clone().toNestQuery()
      .in("metricKind", List.of("counter", "increment")).getParam();
    return scope.query(metering, FacilityAssetType.facilityDevice, "deviceId")
      .flatMap(readings::queryPager)
      .flatMap(page -> {
        if (page.getData().isEmpty()) return Mono.just(page);
        var ids = page.getData().stream().map(ReadingEntity::getDeviceId)
          .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Mono.just(page);
        // 仅查当前页的设备，批量补齐名称也保留租户、应用及设备资产权限。
        return scope.query(new QueryParamEntity().toQuery().in("id", ids).getParam(),
            FacilityAssetType.facilityDevice, "id")
          .flatMapMany(deviceStore::query)
          .collectMap(DeviceEntity::getId)
          .map(devices -> {
            page.getData().forEach(row -> {
              var device = devices.get(row.getDeviceId());
              if (device != null) row.setDeviceName(device.getName());
            });
            return page;
          });
      });
  }

  /** 本人记录是业务筛选，继续与设备资产权限取交集，不能由客户端指定创建人。 */
  public Mono<PagerResult<ReadingEntity>> mine(QueryParamEntity query) {
    return scope.current().flatMap(context -> page(query.clone().toNestQuery()
      .and("creatorId", context.userId()).and("source", "manual").getParam()));
  }

  /** 旧记录与新申请在同一事务提交；版本CAS阻止重复重提，原读数不被覆盖。 */
  @Transactional
  public Mono<ReadingEntity> resubmit(String id, long version, ReadingInput input) {
    return readings.findById(id)
      .switchIfEmpty(Mono.error(new org.hswebframework.web.exception.NotFoundException()))
      .flatMap(previous -> devices.writable(previous.getDeviceId()).flatMap(device ->
        records.child(previous, device).then(scope.current()).flatMap(context -> {
          require("manual".equals(previous.getSource()) && "rejected".equals(previous.getStatus())
            && context.userId().equals(previous.getCreatorId()), "invalid_reading_resubmit");
          require(previous.getVersion() == version, "conflict");
          require(Objects.equals(previous.getPropertyId(), input.getPropertyId())
            && input.getReplacesId() == null, "invalid_reading");
          previous.setStatus("resubmitted");
          return records.update(readings, previous, version)
            .then(createManual(previous.getDeviceId(), input, previous.getId()))
            .flatMap(saved -> records.log(previous, "reading", "resubmit", "rejected", "resubmitted",
              saved.getId(), List.of()).thenReturn(saved));
        })));
  }

  @Transactional
  public Mono<ReadingEntity> ingest(
    MappingEntity mapping,
    MetricMapping metric,
    DeviceProperty property
  ) {
    require(
      property.getDeviceId() != null && property.getTimestamp() > 0,
      "invalid_reading"
    );
    // A backfill can outlive a mapping edit. Lock the still-active version until
    // the reading commits, so an old multiplier/kind cannot win the source-ID dedupe.
    // Rejected snapshots leave the cursor untouched; the next valid backfill replays them.
    return mappings
      .createUpdate()
      .set("version", mapping.getVersion())
      .where("id", mapping.getId())
      .and("tenantId", mapping.getTenantId())
      .and("applicationId", mapping.getApplicationId())
      .and("version", mapping.getVersion())
      .and("enabled", true)
      .and("deleted", false)
      .execute()
      .filter(updated -> updated == 1)
      .flatMap(ignored -> ingestCurrent(mapping, metric, property));
  }

  private Mono<ReadingEntity> ingestCurrent(
    MappingEntity mapping,
    MetricMapping metric,
    DeviceProperty property
  ) {
    return devices.read(mapping.getDeviceId()).flatMap(device -> {
      FacilityDevices.requireActive(device);
      require(
        device.getCloudDeviceId().equals(property.getDeviceId()) &&
          metric.getPropertyId().equals(property.getProperty()),
        "invalid_reading"
      );
      var row = FacilityScope.initialize(
        new ReadingEntity(),
        new FacilityContext(
          mapping.getTenantId(),
          mapping.getApplicationId(),
          mapping.getCreatorId(),
          mapping.getCreatorName()
        )
      );
      FacilityScope.inherit(row, mapping);
      row.setId(
        FacilityRecords.stableId(
          mapping.getTenantId(),
          mapping.getApplicationId(),
          device.getId(),
          mapping.getProjectId(),
          metric.getPropertyId(),
          Long.toString(property.getTimestamp()),
          "cloud"
        )
      );
      row.setDeviceId(device.getId());
      row.setMappingId(mapping.getId());
      row.setMappingVersion(mapping.getVersion());
      row.setEnergyType(mapping.getEnergyType());
      row.setPropertyId(metric.getPropertyId());
      row.setMetricKind(metric.getKind());
      row.setUnit(metric.getUnit());
      row.setTextValue(Objects.toString(property.getValue(), ""));
      row.setObservedTime(property.getTimestamp());
      row.setSource("cloud");
      row.setSourceId(row.getId());
      row.setStatus("accepted");
      if (!"state".equals(metric.getKind())) try {
        row.setRawValue(
          new BigDecimal(
            Objects.toString(
              property.getNumberValue() != null
                ? property.getNumberValue()
                : property.getValue()
            )
          )
        );
        row.setValue(EnergyMath.normalize(row.getRawValue(), metric));
      } catch (NumberFormatException e) {
        row.setStatus("invalid");
        row.setReason("non_numeric");
      }
      return epoch(device.getId(), row.getObservedTime()).flatMap(epoch -> {
        row.setMeterEpoch(epoch);
        return readings
          .findById(row.getId())
          .switchIfEmpty(readings.insert(row).thenReturn(row));
      });
    });
  }

  private Mono<Long> epoch(String deviceId, long time) {
    return scope
      .current()
      .flatMap(c ->
        replacements
          .createQuery()
          .where("tenantId", c.tenantId())
          .and("applicationId", c.applicationId())
          .and("newDeviceId", deviceId)
          .and("state", "approved")
          .lte("effectiveTime", time)
          .orderBy(
            org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.desc(
              "effectiveTime"
            )
          )
          .fetchOne()
          .map(ReplacementEntity::getEffectiveTime)
          .defaultIfEmpty(0L)
      );
  }

  @Transactional
  public Mono<ReadingEntity> manual(String deviceId, ReadingInput input) {
    return createManual(deviceId, input, null);
  }

  private Mono<ReadingEntity> createManual(String deviceId, ReadingInput input, String resubmitsId) {
    return devices.writable(deviceId).flatMap(device ->
      mappingManager.forDevice(deviceId)
        .switchIfEmpty(Mono.error(new org.hswebframework.web.exception.BusinessException(
          "error.facility.property_missing", 400)))
        .flatMap(mapping -> {
        var metric = mapping
          .getMetrics()
          .stream()
          .filter(m -> m.getPropertyId().equals(input.getPropertyId()))
          .findFirst()
          .orElseThrow(() ->
            new org.hswebframework.web.exception.BusinessException(
              "error.facility.property_missing",
              400
            )
          );
        require(
          Boolean.TRUE.equals(mapping.getEnabled()) &&
            Set.of("counter", "increment").contains(metric.getKind()) &&
            input.getValue() != null && input.getValue().signum() >= 0 &&
            input.getObservedTime() != null && input.getObservedTime() > 0 &&
            input.getObservedTime() <= System.currentTimeMillis(),
          "invalid_reading"
        );
        return scope.current().flatMap(c -> {
          var row = FacilityScope.initialize(new ReadingEntity(), c);
          FacilityScope.inherit(row, device);
          row.setDeviceId(deviceId);
          row.setMappingId(mapping.getId());
          row.setMappingVersion(mapping.getVersion());
          row.setEnergyType(mapping.getEnergyType());
          row.setPropertyId(metric.getPropertyId());
          row.setMetricKind(metric.getKind());
          row.setUnit(metric.getUnit());
          row.setRawValue(input.getValue());
          row.setValue(EnergyMath.normalize(input.getValue(), metric));
          row.setObservedTime(input.getObservedTime());
          row.setSource("manual");
          row.setSourceId(row.getId());
          row.setStatus("pending");
          row.setReason(input.getReason());
          row.setReplacesId(input.getReplacesId());
          row.setResubmitsId(resubmitsId);
          return epoch(deviceId, input.getObservedTime()).flatMap(epoch -> {
            row.setMeterEpoch(epoch);
            return readings.insert(row).thenReturn(row);
          });
        });
      })
    );
  }

  /** Device CAS serializes approvals for the same timestamp, including competing corrections. */
  @Transactional
  public Mono<ReadingEntity> review(String id, long version, boolean approve) {
    return readings
      .findById(id)
      .switchIfEmpty(
        Mono.error(new org.hswebframework.web.exception.NotFoundException())
      )
      .flatMap(row ->
        devices.writable(row.getDeviceId()).flatMap(device ->
          records
            .child(row, device)
            .then(scope.current())
            .flatMap(c -> {
              require(
                "pending".equals(row.getStatus()) &&
                  row.getVersion() == version &&
                  !c.userId().equals(row.getCreatorId()),
                "invalid_review"
              );
              row.setReviewerId(c.userId());
              row.setStatus(approve ? "accepted" : "rejected");
              Mono<Void> prior = Mono.empty();
              if (approve && row.getReplacesId() != null) prior = readings
                .findById(row.getReplacesId())
                .switchIfEmpty(
                  Mono.error(
                    new org.hswebframework.web.exception.NotFoundException()
                  )
                )
                .flatMap(old -> {
                  require(
                    old.getDeviceId().equals(row.getDeviceId()) &&
                      old.getApplicationId().equals(row.getApplicationId()) &&
                      old.getTenantId().equals(row.getTenantId()) &&
                      old.getPropertyId().equals(row.getPropertyId()) &&
                      old.getObservedTime().equals(row.getObservedTime()) &&
                      "accepted".equals(old.getStatus()),
                    "invalid_reading"
                  );
                  return readings
                    .createUpdate()
                    .set("status", "superseded")
                    .where("id", old.getId())
                    .and("status", "accepted")
                    .execute()
                    .doOnNext(n -> require(n == 1, "conflict"))
                    .then();
                });
              if (approve) prior = records
                .update(deviceStore, device, device.getVersion())
                .then(prior)
                .then(
                  readings
                    .createQuery()
                    .where("tenantId", row.getTenantId())
                    .and("applicationId", row.getApplicationId())
                    .and("deviceId", row.getDeviceId())
                    .and("propertyId", row.getPropertyId())
                    .and("observedTime", row.getObservedTime())
                    .and("status", "accepted")
                    .count()
                    .doOnNext(count ->
                      require(count == 0, "correction_required")
                    )
                )
                .then();
              return prior
                .then(records.update(readings, row, version))
                .flatMap(saved ->
                  records
                    .log(
                      saved,
                      "reading",
                      approve ? "approve" : "reject",
                      "pending",
                      saved.getStatus(),
                      saved.getReason(),
                      List.of()
                    )
                    .thenReturn(saved)
                );
            })
        )
      );
  }

  public Mono<List<ReadingEntity>> series(
    String deviceId,
    String property,
    long from,
    long to
  ) {
    return mappingManager
      .forDevice(deviceId)
      .flatMap(mapping -> seriesFor(mapping, property, from, to));
  }

  public Mono<List<ReadingEntity>> seriesFor(
    MappingEntity mapping,
    String property,
    long from,
    long to
  ) {
    require(from < to && to - from <= 366L * 86400000, "invalid_period");
    String deviceId = mapping.getDeviceId();
    return records
      .read(
        mappings,
        mapping.getId(),
        org.jetlinks.pro.property.facility.asset.FacilityAssetType.facilityMapping,
        CrudAssetPermission.read
      )
      .then(scope.current())
      .flatMap(c -> {
        var query = readings
          .createQuery()
          .where("tenantId", c.tenantId())
          .and("applicationId", c.applicationId())
          .and("projectId", mapping.getProjectId())
          .and("deviceId", deviceId)
          .and("propertyId", property)
          .and("status", "accepted")
          .gte("observedTime", from)
          .lte("observedTime", to)
          .orderBy(
            org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.asc(
              "observedTime"
            )
          )
          .fetch()
          .take(50001)
          .collectList()
          .doOnNext(rows -> require(rows.size() <= 50000, "too_many"));
        var previous = readings
          .createQuery()
          .where("tenantId", c.tenantId())
          .and("applicationId", c.applicationId())
          .and("projectId", mapping.getProjectId())
          .and("deviceId", deviceId)
          .and("propertyId", property)
          .and("status", "accepted")
          .lt("observedTime", from)
          .orderBy(
            org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.desc(
              "observedTime"
            )
          )
          .fetchOne();
        return Mono.zip(
          query,
          previous.map(Optional::of).defaultIfEmpty(Optional.empty())
        ).map(t -> {
          var list = new ArrayList<ReadingEntity>();
          t.getT2().ifPresent(list::add);
          list.addAll(t.getT1());
          return list;
        });
      });
  }

  public Mono<Long> anomalies(
    String deviceId,
    String property,
    long from,
    long to
  ) {
    return mappingManager
      .forDevice(deviceId)
      .flatMap(mapping ->
        Mono.zip(
          seriesFor(mapping, property, from, to),
          readings
            .createQuery()
            .where("tenantId", mapping.getTenantId())
            .and("applicationId", mapping.getApplicationId())
            .and("projectId", mapping.getProjectId())
            .and("deviceId", deviceId)
            .and("propertyId", property)
            .and("status", "invalid")
            .gte("observedTime", from)
            .lt("observedTime", to)
            .count()
        ).map(
          data ->
            (long) EnergyMath.usage(
              data.getT1(),
              from,
              to,
              mapping.getStaleAfterSeconds()
            ).anomalies() + data.getT2()
        )
      );
  }

  public Mono<EnergyMath.Usage> usage(
    String deviceId,
    String property,
    long from,
    long to
  ) {
    return mappingManager
      .forDevice(deviceId)
      .flatMap(mapping ->
        seriesFor(mapping, property, from, to).map(rows ->
          EnergyMath.usage(rows, from, to, mapping.getStaleAfterSeconds())
        )
      );
  }

  public Mono<EnergyMath.Usage> usageFor(
    MappingEntity mapping,
    String property,
    long from,
    long to
  ) {
    return seriesFor(mapping, property, from, to).map(rows ->
      EnergyMath.usage(rows, from, to, mapping.getStaleAfterSeconds())
    );
  }
}
