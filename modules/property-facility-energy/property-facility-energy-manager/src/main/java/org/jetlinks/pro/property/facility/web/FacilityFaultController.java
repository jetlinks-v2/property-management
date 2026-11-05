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

/** 故障来源、处理和独立复核入口。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/facility-energy/faults")
@Resource(id = "facility-fault", name = "设备故障")
public class FacilityFaultController {

  private final FaultStore store;
  private final FacilityFaults manager;
  private final FacilityRecords records;
  private final FacilityQueries queries;

  public record Action(long version, String comment) {}

  @PostMapping("/_query")
  @QueryAction
  public Mono<PagerResult<FaultEntity>> query(@RequestBody QueryParamEntity q) {
    return queries.page(store, q, FacilityAssetType.facilityFault, "id");
  }

  @GetMapping("/{id}")
  @QueryAction
  public Mono<FaultEntity> read(@PathVariable String id) {
    return records.read(
      store,
      id,
      FacilityAssetType.facilityFault,
      CrudAssetPermission.read
    );
  }

  @PostMapping
  @SaveAction
  public Mono<FaultEntity> create(@RequestBody FaultEntity row) {
    return manager.create(row);
  }

  @PostMapping("/{id}/{action:start|resolve|approve|reject}")
  @SaveAction
  public Mono<FaultEntity> action(
    @PathVariable String id,
    @PathVariable String action,
    @RequestBody Action row
  ) {
    return manager.action(id, action, row.version(), row.comment());
  }
}
