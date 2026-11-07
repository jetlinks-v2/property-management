package org.jetlinks.pro.property.facility.web;

import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.*;
import org.jetlinks.pro.property.facility.service.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.*;

/** 设施台账、Cloud同步和扩展资料入口；禁止创建脱离Cloud的设备。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/facility-energy/devices")
@Resource(id = "facility-device", name = "设施设备")
public class FacilityDeviceController {

  private final FacilityDevices devices;
  private final FacilitySync sync;
  private final FacilityCloud cloud;
  private final FacilityBindings bindings;
  private final FacilityReadings readings;
  private final FacilityMappings mappings;
  private final FacilityQueries queries;
  private final JobStore jobs;
  private final ProcessStore processes;

  public record ProjectChoice(String projectId, long version) {}

  public record Version(long version, String reason) {}

  public record SyncConfiguration(String projectId, boolean enabled) {}

  @PostMapping("/_query")
  @QueryAction
  public Mono<PagerResult<DeviceEntity>> query(
    @RequestBody QueryParamEntity q
  ) {
    return devices.query(q);
  }

  @GetMapping("/{id}")
  @QueryAction
  public Mono<DeviceEntity> read(@PathVariable String id) {
    return devices.read(id);
  }

  @PostMapping("/{id}")
  @SaveAction
  public Mono<DeviceEntity> update(
    @PathVariable String id,
    @RequestBody DeviceEntity row
  ) {
    return devices.update(id, row);
  }

  @PostMapping("/{id}/project")
  @SaveAction
  public Mono<DeviceEntity> choose(
    @PathVariable String id,
    @RequestBody ProjectChoice choice
  ) {
    return devices.confirmProject(id, choice.projectId(), choice.version());
  }

  @PostMapping("/sync/{projectId}")
  @SaveAction
  public Mono<JobEntity> synchronize(@PathVariable String projectId) {
    return sync.synchronize(projectId);
  }

  @PostMapping("/sync")
  @SaveAction
  public Mono<JobEntity> configure(@RequestBody SyncConfiguration row) {
    return sync.configure(row.projectId(), row.enabled());
  }

  @PostMapping("/sync/_query")
  @QueryAction
  public Mono<PagerResult<JobEntity>> jobs(@RequestBody QueryParamEntity q) {
    return queries.projects(jobs, q);
  }

  @GetMapping("/{id}/metadata")
  @QueryAction
  public Mono<org.jetlinks.core.things.ThingMetadata> metadata(
    @PathVariable String id
  ) {
    return devices.read(id).flatMap(d -> cloud.metadata(d.getCloudDeviceId()));
  }

  @GetMapping("/{id}/mapping")
  @QueryAction
  public Mono<MappingEntity> mapping(@PathVariable String id) {
    return mappings.forDevice(id);
  }

  @GetMapping("/{id}/bindings")
  @QueryAction
  public Flux<BindingEntity> bindings(@PathVariable String id) {
    return bindings.history(id);
  }

  @PostMapping("/{id}/bindings")
  @SaveAction
  public Mono<BindingEntity> bind(
    @PathVariable String id,
    @Valid @RequestBody BindingInput row
  ) {
    return bindings.bind(id, row);
  }

  @PostMapping("/{id}/bindings/end")
  @SaveAction
  public Mono<Void> end(@PathVariable String id, @RequestBody Version row) {
    return bindings.end(id, row.version(), row.reason());
  }

  @GetMapping("/{id}/history")
  @QueryAction
  public Mono<PagerResult<ProcessEntity>> history(@PathVariable String id) {
    return devices
      .read(id)
      .flatMap(device ->
        queries.page(
          processes,
          QueryParamEntity.newQuery()
            .is("targetId", id)
            .and("projectId", device.getProjectId())
            .getParam(),
          FacilityAssetType.facilityDevice,
          "targetId"
        )
      );
  }

  @PostMapping("/extensions/import")
  @SaveAction
  public Mono<List<DeviceEntity>> importRows(
    @RequestBody List<FacilityDevices.ExtensionImport> rows
  ) {
    return devices.importExtensions(rows);
  }
}
