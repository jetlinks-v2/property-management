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

/** Plan受控配置入口，数据范围由统一资产授权决定。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/facility-energy/plans")
@Resource(id = "facility-plan", name = "巡检维保计划")
public class FacilityPlanController {

  private final PlanStore store;
  private final FacilityPlans manager;
  private final FacilityQueries queries;
  private final FacilityRecords records;

  @PostMapping("/_query")
  @QueryAction
  public Mono<PagerResult<PlanEntity>> query(@RequestBody QueryParamEntity q) {
    return queries.page(store, q, FacilityAssetType.facilityPlan, "id");
  }

  @GetMapping("/{id}")
  @QueryAction
  public Mono<PlanEntity> read(@PathVariable String id) {
    return records.read(
      store,
      id,
      FacilityAssetType.facilityPlan,
      CrudAssetPermission.read
    );
  }

  @PostMapping
  @SaveAction
  public Mono<PlanEntity> save(@Valid @RequestBody PlanEntity row) {
    return manager.save(row);
  }

  @DeleteMapping("/{id}")
  @DeleteAction
  public Mono<Void> remove(
    @PathVariable String id,
    @RequestParam long version
  ) {
    return queries.remove(store, id, version, FacilityAssetType.facilityPlan);
  }
}
