package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.*;

/** 仅可绑定Cloud真实物模型属性；总分表在同项目、同能源类型内形成无环树。 */
@Service
@RequiredArgsConstructor
public class FacilityMappings {

  private final MappingStore mappings;
  private final FacilityDevices devices;
  private final FacilityCloud cloud;
  private final FacilityScope scope;
  private final FacilityRecords records;

  @Transactional
  public Mono<MappingEntity> save(MappingEntity input) {
    require(
      Set.of("water", "electricity", "gas").contains(input.getEnergyType()) &&
        Set.of("master", "submeter", "branch", "other").contains(
          input.getMeterLevel()
        ) &&
        input.getStaleAfterSeconds() != null &&
        input.getStaleAfterSeconds() > 0,
      "invalid_mapping"
    );
    EnergyMath.mapping(input.getMetrics());
    var usageMetrics = input
      .getMetrics()
      .stream()
      .filter(metric ->
        Set.of("counter", "increment").contains(metric.getKind())
      )
      .toList();
    if (
      (input.getUsagePropertyId() == null ||
        input.getUsagePropertyId().isBlank()) &&
      usageMetrics.size() == 1
    ) input.setUsagePropertyId(usageMetrics.get(0).getPropertyId());
    require(
      usageMetrics.isEmpty() ||
        usageMetrics
          .stream()
          .anyMatch(metric ->
            metric.getPropertyId().equals(input.getUsagePropertyId())
          ),
      "invalid_mapping"
    );
    return devices
      .writable(input.getDeviceId())
      .doOnNext(FacilityDevices::requireActive)
      .flatMap(device ->
        cloud
          .metadata(device.getCloudDeviceId())
          .doOnNext(metadata -> {
            var properties = metadata
              .getProperties()
              .stream()
              .map(p -> p.getId())
              .collect(java.util.stream.Collectors.toSet());
            require(
              properties.containsAll(
                input
                  .getMetrics()
                  .stream()
                  .map(m -> m.getPropertyId())
                  .toList()
              ),
              "property_missing"
            );
          })
          .then(validateParent(input, device, new HashSet<>()))
          .then(scope.current())
          .flatMap(c -> {
            String id = FacilityRecords.stableId(
              c.tenantId(),
              c.applicationId(),
              device.getId(),
              device.getProjectId(),
              "mapping"
            );
            return mappings
              .findById(id)
              .flatMap(old -> {
                require(
                  Objects.equals(old.getVersion(), input.getVersion()),
                  "conflict"
                );
                return records.read(
                  mappings,
                  id,
                  FacilityAssetType.facilityMapping,
                  CrudAssetPermission.save
                );
              })
              .map(Optional::of)
              .defaultIfEmpty(Optional.empty())
              .flatMap(existing -> {
                boolean create = existing.isEmpty();
                var row = existing.orElseGet(() ->
                  FacilityScope.initialize(new MappingEntity(), c)
                );
                long v = row.getVersion();
                row.setId(id);
                FacilityScope.inherit(row, device);
                row.setName(device.getName());
                row.setDeviceId(device.getId());
                row.setProductId(device.getProductId());
                row.setEnergyType(input.getEnergyType());
                row.setUsageType(input.getUsageType());
                row.setUsagePropertyId(input.getUsagePropertyId());
                row.setParentDeviceId(input.getParentDeviceId());
                row.setMeterLevel(input.getMeterLevel());
                row.setMetrics(input.getMetrics());
                row.setStaleAfterSeconds(input.getStaleAfterSeconds());
                row.setEnabled(input.getEnabled());
                return create
                  ? records.insert(
                      mappings,
                      row,
                      FacilityAssetType.facilityMapping
                    )
                  : records.update(
                      mappings,
                      row,
                      v,
                      FacilityRecords.nullFields(
                        row,
                        "parentDeviceId",
                        "usageType",
                        "usagePropertyId"
                      )
                    );
              });
          })
      );
  }

  private Mono<Void> validateParent(
    MappingEntity input,
    DeviceEntity device,
    Set<String> seen
  ) {
    String parent = input.getParentDeviceId();
    if (parent == null || parent.isBlank()) return Mono.empty();
    require(!parent.equals(device.getId()) && seen.add(parent), "meter_cycle");
    return devices
      .read(parent)
      .doOnNext(d ->
        require(
          d.getProjectId().equals(device.getProjectId()),
          "project_mismatch"
        )
      )
      .then(scope.current())
      .flatMap(c ->
        mappings
          .createQuery()
          .where("tenantId", c.tenantId())
          .and("applicationId", c.applicationId())
          .and("deviceId", parent)
          .and("projectId", device.getProjectId())
          .and("deleted", false)
          .fetchOne()
      )
      .switchIfEmpty(
        Mono.error(
          new org.hswebframework.web.exception.BusinessException(
            "error.facility.parent_mapping_missing",
            400
          )
        )
      )
      .flatMap(row -> {
        require(
          row.getEnergyType().equals(input.getEnergyType()),
          "energy_mismatch"
        );
        return validateParent(row, device, seen);
      });
  }

  /** Preview uses Cloud public history without creating business readings or moving a collection cursor. */
  public Mono<List<Map<String, Object>>> preview(
    String deviceId,
    List<org.jetlinks.pro.property.facility.model.MetricMapping> metrics
  ) {
    EnergyMath.mapping(metrics);
    return devices.read(deviceId).flatMap(device ->
      cloud.metadata(device.getCloudDeviceId()).flatMap(metadata -> {
        var ids = metadata
          .getProperties()
          .stream()
          .map(p -> p.getId())
          .collect(java.util.stream.Collectors.toSet());
        require(
          ids.containsAll(
            metrics
              .stream()
              .map(m -> m.getPropertyId())
              .toList()
          ),
          "property_missing"
        );
        long now = System.currentTimeMillis();
        return Flux.fromIterable(metrics)
          .concatMap(metric ->
            cloud
              .history(
                device.getCloudDeviceId(),
                metric.getPropertyId(),
                now - 86400000L,
                now + 1
              )
              .takeLast(5)
              .map(sample -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("propertyId", metric.getPropertyId());
                row.put("time", sample.getTimestamp());
                row.put("raw", Objects.toString(sample.getValue(), ""));
                row.put("unit", metric.getUnit());
                if ("state".equals(metric.getKind())) row.put(
                  "normalized",
                  Objects.toString(sample.getValue(), "")
                );
                else try {
                  row.put(
                    "normalized",
                    EnergyMath.normalize(
                      new java.math.BigDecimal(
                        Objects.toString(
                          sample.getNumberValue() != null
                            ? sample.getNumberValue()
                            : sample.getValue()
                        )
                      ),
                      metric
                    )
                  );
                } catch (NumberFormatException e) {
                  row.put("error", "non_numeric");
                }
                return row;
              })
          )
          .collectList();
      })
    );
  }

  public Mono<MappingEntity> forDevice(String deviceId) {
    return devices
      .read(deviceId)
      .flatMap(device ->
        scope
          .current()
          .flatMap(c ->
            mappings
              .createQuery()
              .where("tenantId", c.tenantId())
              .and("applicationId", c.applicationId())
              .and("projectId", device.getProjectId())
              .and("deviceId", deviceId)
              .and("deleted", false)
              .fetchOne()
          )
      );
  }
}
