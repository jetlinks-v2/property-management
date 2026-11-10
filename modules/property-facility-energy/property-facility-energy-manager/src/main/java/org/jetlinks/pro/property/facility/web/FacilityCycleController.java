package org.jetlinks.pro.property.facility.web;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.CycleEntity;
import org.jetlinks.pro.property.facility.service.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/** 周期计划独立授权入口，所有查询及引用遵循统一资产权限。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/facility-energy/cycles")
@Resource(id = "facility-cycle", name = "周期计划")
public class FacilityCycleController {
  private final CycleStore store;
  private final FacilityCycles manager;
  private final FacilityQueries queries;

  @PostMapping("/_query")
  @QueryAction
  public Mono<PagerResult<CycleEntity>> query(@RequestBody QueryParamEntity query) {
    return queries.page(store, query, FacilityAssetType.facilityCycle, "id");
  }
  @GetMapping("/{id}")
  @QueryAction
  public Mono<CycleEntity> read(@PathVariable String id) { return manager.read(id); }
  @PostMapping
  @SaveAction
  public Mono<CycleEntity> save(@RequestBody CycleEntity row) { return manager.save(row); }
  @DeleteMapping("/{id}")
  @DeleteAction
  public Mono<Void> remove(@PathVariable String id, @RequestParam long version) { return manager.remove(id, version); }
}
