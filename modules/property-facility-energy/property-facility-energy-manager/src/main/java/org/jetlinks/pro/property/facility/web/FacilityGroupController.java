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

/** Group受控配置入口，数据范围由统一资产授权决定。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/facility-energy/groups")
@Resource(id = "facility-group", name = "机房与设备组")
public class FacilityGroupController {

  private final GroupStore store;
  private final FacilityConfiguration manager;
  private final FacilityQueries queries;
  private final FacilityRecords records;

  @PostMapping("/_query")
  @QueryAction
  public Mono<PagerResult<GroupEntity>> query(@RequestBody QueryParamEntity q) {
    return queries.page(store, q, FacilityAssetType.facilityGroup, "id");
  }

  @GetMapping("/{id}")
  @QueryAction
  public Mono<GroupEntity> read(@PathVariable String id) {
    return records.read(
      store,
      id,
      FacilityAssetType.facilityGroup,
      CrudAssetPermission.read
    );
  }

  @PostMapping
  @SaveAction
  public Mono<GroupEntity> save(@Valid @RequestBody GroupEntity row) {
    return manager.group(row);
  }

  @DeleteMapping("/{id}")
  @DeleteAction
  public Mono<Void> remove(
    @PathVariable String id,
    @RequestParam long version
  ) {
    return queries.remove(store, id, version, FacilityAssetType.facilityGroup);
  }
}
