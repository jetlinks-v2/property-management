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

/** PC和员工H5共用任务状态机，离线重放仍校验版本和当前执行人。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/facility-energy/tasks")
@Resource(id = "facility-task", name = "巡检维保任务")
public class FacilityTaskController {

  private final FacilityTasks tasks;
  private final FacilityScope scope;
  private final FacilityQueries queries;
  private final ProcessStore processes;

  @PostMapping("/_query")
  @QueryAction
  public Mono<PagerResult<TaskEntity>> query(
    @RequestBody QueryParamEntity q,
    @RequestParam(defaultValue = "false") boolean mine,
    @RequestParam(defaultValue = "false") boolean pool
  ) {
    return tasks.query(q, mine, pool);
  }

  @PostMapping("/review/_query")
  @ResourceAction(id = "review", name = "任务审批")
  public Mono<PagerResult<TaskEntity>> reviews(@RequestBody QueryParamEntity query,
    @RequestParam(defaultValue = "pending") String status) {
    return tasks.reviews(query, status);
  }

  @GetMapping("/{id}")
  @QueryAction
  public Mono<TaskEntity> read(@PathVariable String id) {
    return tasks.read(id);
  }

  @GetMapping("/{id}/assignees")
  @ResourceAction(id = "dispatch", name = "派单与转单")
  public Flux<org.jetlinks.pro.property.base.user.PropertyEmployeeInfo> assignees(@PathVariable String id) {
    return tasks.assignees(id);
  }

  @GetMapping("/{id}/claimable")
  @QueryAction
  public Mono<Boolean> canClaim(@PathVariable String id) {
    return tasks.canClaim(id);
  }

  @PostMapping("/{id}/{action:claim|start|submit}")
  @SaveAction
  public Mono<TaskEntity> action(
    @PathVariable String id,
    @PathVariable String action,
    @Valid @RequestBody TaskCommand command
  ) {
    return tasks.action(id, action, command);
  }

  @PostMapping("/{id}/{action:transfer|cancel}")
  @ResourceAction(id = "dispatch", name = "派单与转单")
  public Mono<TaskEntity> dispatch(
    @PathVariable String id,
    @PathVariable String action,
    @Valid @RequestBody TaskCommand command
  ) {
    return tasks.action(id, action, command);
  }

  @PostMapping("/{id}/close")
  @ResourceAction(id = "close", name = "关闭任务")
  public Mono<TaskEntity> close(@PathVariable String id, @Valid @RequestBody TaskCommand command) {
    return tasks.action(id, "close", command);
  }

  @PostMapping("/{id}/{action:approve|reject}")
  @ResourceAction(id = "review", name = "任务审批")
  public Mono<TaskEntity> review(
    @PathVariable String id,
    @PathVariable String action,
    @Valid @RequestBody TaskCommand command
  ) {
    return tasks.action(id, action, command);
  }

  @GetMapping("/{id}/offline")
  @QueryAction
  public Mono<TaskEntity> download(@PathVariable String id) {
    return tasks.read(id).flatMap(task ->
      scope.current().map(c -> {
        FacilityScope.require(
          c.userId().equals(task.getAssigneeId()),
          "not_assignee"
        );
        FacilityScope.require(
          Set.of(
            "claimed",
            "executing",
            "rectify"
          ).contains(task.getState()),
          "invalid_state"
        );
        return task;
      })
    );
  }

  @GetMapping("/{id}/history")
  @QueryAction
  public Mono<PagerResult<ProcessEntity>> history(@PathVariable String id,
    @RequestParam(defaultValue = "0") int pageIndex) {
    var query = QueryParamEntity.newQuery().is("targetId", id).orderByDesc("createTime").orderByDesc("id").getParam();
    query.setPageIndex(Math.max(0, pageIndex));
    query.setPageSize(20);
    return tasks
      .read(id)
      .then(
        queries.page(
          processes,
          query,
          FacilityAssetType.facilityTask,
          "targetId"
        )
      );
  }
}
