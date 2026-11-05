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

/** Rule受控配置入口，数据范围由统一资产授权决定。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/facility-energy/rules")
@Resource(id = "facility-rule", name = "预警规则")
public class FacilityRuleController {

  private final RuleStore store;
  private final FacilityAlerts manager;
  private final FacilityQueries queries;
  private final FacilityRecords records;

  @PostMapping("/_query")
  @QueryAction
  public Mono<PagerResult<RuleEntity>> query(@RequestBody QueryParamEntity q) {
    return queries.page(store, q, FacilityAssetType.facilityRule, "id");
  }

  @GetMapping("/{id}")
  @QueryAction
  public Mono<RuleEntity> read(@PathVariable String id) {
    return records.read(
      store,
      id,
      FacilityAssetType.facilityRule,
      CrudAssetPermission.read
    );
  }

  @PostMapping
  @SaveAction
  public Mono<RuleEntity> save(@Valid @RequestBody RuleEntity row) {
    return manager.save(row);
  }

  @DeleteMapping("/{id}")
  @DeleteAction
  public Mono<Void> remove(
    @PathVariable String id,
    @RequestParam long version
  ) {
    return queries.remove(store, id, version, FacilityAssetType.facilityRule);
  }
}
