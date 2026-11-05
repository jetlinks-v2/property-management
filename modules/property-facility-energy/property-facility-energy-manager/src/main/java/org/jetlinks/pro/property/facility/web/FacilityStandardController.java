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

/** Standard受控配置入口，数据范围由统一资产授权决定。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/facility-energy/standards")
@Resource(id = "facility-standard", name = "作业标准与指导书")
public class FacilityStandardController {

  private final StandardStore store;
  private final FacilityConfiguration manager;
  private final FacilityQueries queries;
  private final FacilityRecords records;

  @PostMapping("/_query")
  @QueryAction
  public Mono<PagerResult<StandardEntity>> query(
    @RequestBody QueryParamEntity q
  ) {
    return queries.page(store, q, FacilityAssetType.facilityStandard, "id");
  }

  @GetMapping("/{id}")
  @QueryAction
  public Mono<StandardEntity> read(@PathVariable String id) {
    return records.read(
      store,
      id,
      FacilityAssetType.facilityStandard,
      CrudAssetPermission.read
    );
  }

  @PostMapping
  @SaveAction
  public Mono<StandardEntity> save(@Valid @RequestBody StandardEntity row) {
    return manager.standard(row);
  }

  @DeleteMapping("/{id}")
  @DeleteAction
  public Mono<Void> remove(
    @PathVariable String id,
    @RequestParam long version
  ) {
    return queries.remove(
      store,
      id,
      version,
      FacilityAssetType.facilityStandard
    );
  }
}
