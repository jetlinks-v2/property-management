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

/** 映射、采集、补录和审核，不提供远程断供或支付命令。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/facility-energy/energy")
@Resource(id = "facility-mapping", name = "能源计量")
public class FacilityEnergyController {

  private final MappingStore store;
  private final ReplacementStore replacements;
  private final FacilityMappings mappings;
  private final FacilityReadings manager;
  private final FacilityReplacements replacementManager;
  private final FacilityQueries queries;
  private final FacilityTelemetry telemetry;

  public record Review(long version, boolean approve) {}

  @PostMapping("/mappings/_query")
  @QueryAction
  public Mono<PagerResult<MappingEntity>> mappings(
    @RequestBody QueryParamEntity q
  ) {
    return queries.page(store, q, FacilityAssetType.facilityMapping, "id");
  }

  @PostMapping("/mappings")
  @SaveAction
  public Mono<MappingEntity> save(@RequestBody MappingEntity row) {
    return mappings.save(row);
  }

  @PostMapping("/preview/{deviceId}")
  @QueryAction
  public Mono<List<Map<String, Object>>> preview(
    @PathVariable String deviceId,
    @RequestBody List<MetricMapping> metrics
  ) {
    return mappings.preview(deviceId, metrics);
  }

  @PostMapping("/collect/{deviceId}")
  @SaveAction
  public Mono<Void> collect(@PathVariable String deviceId) {
    return mappings.forDevice(deviceId).flatMap(telemetry::collect);
  }

  @GetMapping("/series/{deviceId}")
  @QueryAction
  public Mono<List<ReadingEntity>> series(
    @PathVariable String deviceId,
    @RequestParam String property,
    @RequestParam long from,
    @RequestParam long to
  ) {
    return manager.series(deviceId, property, from, to);
  }

  @GetMapping("/usage/{deviceId}")
  @QueryAction
  public Mono<EnergyMath.Usage> usage(
    @PathVariable String deviceId,
    @RequestParam String property,
    @RequestParam long from,
    @RequestParam long to
  ) {
    return manager.usage(deviceId, property, from, to);
  }

  @PostMapping("/readings/_query")
  @QueryAction
  public Mono<PagerResult<ReadingEntity>> readings(
    @RequestBody QueryParamEntity q,
    @RequestParam(defaultValue = "false") boolean mine
  ) {
    return mine ? manager.mine(q) : manager.page(q);
  }

  @PostMapping("/readings/{deviceId}")
  @SaveAction
  public Mono<ReadingEntity> manual(
    @PathVariable String deviceId,
    @Valid @RequestBody ReadingInput row
  ) {
    return manager.manual(deviceId, row);
  }

  @PostMapping("/readings/{id}/resubmit")
  @SaveAction
  public Mono<ReadingEntity> resubmit(
    @PathVariable String id,
    @RequestParam long version,
    @Valid @RequestBody ReadingInput input
  ) {
    return manager.resubmit(id, version, input);
  }

  @PostMapping("/readings/{id}/review")
  @SaveAction
  public Mono<ReadingEntity> review(
    @PathVariable String id,
    @RequestBody Review review
  ) {
    return manager.review(id, review.version(), review.approve());
  }

  @PostMapping("/replacements/_query")
  @QueryAction
  public Mono<PagerResult<ReplacementEntity>> replacements(
    @RequestBody QueryParamEntity q
  ) {
    return queries.page(
      replacements,
      q,
      FacilityAssetType.facilityDevice,
      "oldDeviceId"
    );
  }

  @PostMapping("/replacements")
  @SaveAction
  public Mono<ReplacementEntity> replace(@RequestBody ReplacementEntity row) {
    return replacementManager.create(row);
  }

  @PostMapping("/replacements/{id}/review")
  @SaveAction
  public Mono<ReplacementEntity> reviewReplacement(
    @PathVariable String id,
    @RequestBody Review review
  ) {
    return replacementManager.review(id, review.version(), review.approve());
  }
}
