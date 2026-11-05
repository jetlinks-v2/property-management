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
  private final FacilityPlans plans;
  private final FacilityScope scope;
  private final FacilityQueries queries;
  private final ProcessStore processes;

  @PostMapping("/_query")
  @QueryAction
  public Mono<PagerResult<TaskEntity>> query(
    @RequestBody QueryParamEntity q,
    @RequestParam(defaultValue = "false") boolean mine
  ) {
    return tasks.query(q, mine);
  }

  @GetMapping("/{id}")
  @QueryAction
  public Mono<TaskEntity> read(@PathVariable String id) {
    return tasks.read(id);
  }

  @PostMapping("/{id}/{action:claim|start|submit|follow}")
  @SaveAction
  public Mono<TaskEntity> action(
    @PathVariable String id,
    @PathVariable String action,
    @Valid @RequestBody TaskCommand command
  ) {
    return tasks.action(id, action, command);
  }

  @PostMapping("/{id}/{action:transfer|cancel}")
  @ResourceAction(id = "dispatch", name = "分派与撤回")
  public Mono<TaskEntity> dispatch(
    @PathVariable String id,
    @PathVariable String action,
    @Valid @RequestBody TaskCommand command
  ) {
    return tasks.action(id, action, command);
  }

  @PostMapping("/{id}/{action:approve|reject}")
  @ResourceAction(id = "review", name = "完成审核与整改")
  public Mono<TaskEntity> review(
    @PathVariable String id,
    @PathVariable String action,
    @Valid @RequestBody TaskCommand command
  ) {
    return tasks.action(id, action, command);
  }

  @PostMapping("/generate/{planId}")
  @ResourceAction(id = "generate", name = "生成任务")
  public Mono<List<TaskEntity>> generate(
    @PathVariable String planId,
    @RequestParam(defaultValue = "false") boolean temporary
  ) {
    return plans.generate(planId, temporary);
  }

  @GetMapping("/{id}/offline")
  @QueryAction
  public Mono<TaskEntity> download(@PathVariable String id) {
    return tasks.read(id).flatMap(task ->
      scope.current().map(c -> {
        FacilityScope.require(
          c.userId().equals(task.getAssigneeId()) ||
            c.userId().equals(task.getFollowUserId()),
          "not_assignee"
        );
        FacilityScope.require(
          Set.of(
            "claimed",
            "executing",
            "rectify",
            "external-submitted"
          ).contains(task.getState()),
          "invalid_state"
        );
        return task;
      })
    );
  }

  @GetMapping("/{id}/history")
  @QueryAction
  public Mono<PagerResult<ProcessEntity>> history(@PathVariable String id) {
    return tasks
      .read(id)
      .then(
        queries.page(
          processes,
          QueryParamEntity.newQuery().is("targetId", id).getParam(),
          FacilityAssetType.facilityTask,
          "targetId"
        )
      );
  }
}
