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
  private final FacilityDirectory directory;
  private final FacilityScope scope;
  private final ProcessStore processes;

  @PostMapping("/_query")
  @QueryAction
  public Mono<PagerResult<FaultEntity>> query(@RequestBody QueryParamEntity q,
    @RequestParam(defaultValue = "false") boolean mine) {
    return scope.current().flatMap(context -> queries.page(store, mine ? q.clone().toNestQuery()
      .nest().is("assigneeId", context.userId()).or("reviewerId", context.userId()).end().getParam() : q,
      FacilityAssetType.facilityFault, "id"));
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

  @GetMapping("/{id}/assignees")
  @ResourceAction(id = "dispatch", name = "故障派单转单")
  public Flux<org.jetlinks.pro.property.base.user.PropertyEmployeeInfo> assignees(@PathVariable String id) {
    return records.read(store, id, FacilityAssetType.facilityFault, CrudAssetPermission.save)
      .flatMapMany(row -> directory.projectEmployees(row.getProjectId()));
  }

  @GetMapping("/{id}/history") @QueryAction
  public Mono<PagerResult<ProcessEntity>> history(@PathVariable String id, @RequestParam(defaultValue = "0") int pageIndex) {
    var query = QueryParamEntity.newQuery().is("targetId", id).and("targetType", "fault").orderByDesc("createTime").getParam();
    query.setPageIndex(Math.max(0, pageIndex)); query.setPageSize(20);
    return read(id).then(queries.page(processes, query, FacilityAssetType.facilityFault, "targetId"));
  }

  @PostMapping("/{id}/{action:claim|start|resolve}")
  @SaveAction
  public Mono<FaultEntity> action(
    @PathVariable String id,
    @PathVariable String action,
    @Valid @RequestBody FaultCommand row
  ) {
    return manager.action(id, action, row);
  }

  @PostMapping("/{id}/{action:dispatch|transfer|reviewer}")
  @ResourceAction(id = "dispatch", name = "故障派单转单")
  public Mono<FaultEntity> dispatch(@PathVariable String id, @PathVariable String action, @Valid @RequestBody FaultCommand row) {
    return manager.action(id, action, row);
  }

  @PostMapping("/{id}/{action:approve|reject}")
  @ResourceAction(id = "review", name = "故障验收")
  public Mono<FaultEntity> review(@PathVariable String id, @PathVariable String action, @Valid @RequestBody FaultCommand row) {
    return manager.action(id, action, row);
  }

  @PostMapping("/{id}/close")
  @ResourceAction(id = "close", name = "关闭故障")
  public Mono<FaultEntity> close(@PathVariable String id, @Valid @RequestBody FaultCommand row) {
    return manager.action(id, "close", row);
  }
}
