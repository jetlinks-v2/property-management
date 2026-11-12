package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.TaskEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

/** 整项工单拥有人员和审批生命周期；设备明细保留独立结果、版本和提交幂等。 */
@Service
@RequiredArgsConstructor
public class FacilityTaskExecutions {
  private final TaskStore tasks;
  private final FacilityRecords records;
  private final FacilityScope scope;
  private final FacilityEmployeeMessages messages;

  public static boolean aggregate(TaskEntity task) {
    return task.getParentTaskId() == null && task.getDeviceCount() != null && task.getDeviceCount() > 0;
  }

  /** 先授权整项任务，再注入明细资产范围；扫码仅过滤生成时的设备组快照。 */
  public Mono<List<TaskEntity>> devices(String id, String groupId, String deviceId) {
    return records.read(tasks, id, FacilityAssetType.facilityTask, CrudAssetPermission.read)
      .flatMap(parent -> {
        require(aggregate(parent), "invalid_state");
        return children(parent).filter(row -> (groupId == null || row.getGroupIds().contains(groupId)) &&
          (deviceId == null || deviceId.equals(row.getDeviceId()))).collectList();
      });
  }

  Flux<TaskEntity> children(TaskEntity parent) {
    var query = QueryParamEntity.newQuery().is("parentTaskId", parent.getId()).orderByAsc("deviceName").noPaging().getParam();
    return scope.query(query, FacilityAssetType.facilityTask, "id")
      .flatMapMany(q -> tasks.createQuery().setParam(q).fetch()).take(1001)
      .index().doOnNext(row -> require(row.getT1() < 1000, "too_many")).map(row -> row.getT2());
  }

  /** 子记录不得单独认领、转派或审批，否则会拆散整项任务的归属。 */
  Mono<Void> before(TaskEntity task, String action) {
    if (task.getParentTaskId() == null) {
      require(!aggregate(task) || !"submit".equals(action), "devices_incomplete");
      return Mono.empty();
    }
    require(Set.of("start", "submit").contains(action), "task_action_on_parent");
    return records.read(tasks, task.getParentTaskId(), FacilityAssetType.facilityTask, CrudAssetPermission.save)
      .doOnNext(parent -> {
        require(aggregate(parent) && Objects.equals(parent.getProjectId(), task.getProjectId()) &&
          Objects.equals(parent.getAssigneeId(), task.getAssigneeId()) &&
          (Set.of("claimed", "executing", "rectify").contains(parent.getState()) ||
            "submit".equals(action) && Set.of("review", "completed").contains(parent.getState())), "invalid_state");
      }).then();
  }

  /** 与动作同事务；批量级联避免按设备逐条访问目录、写日志或更新。 */
  Mono<TaskEntity> after(TaskEntity saved, String action) {
    if (saved.getParentTaskId() != null) {
      return records.read(tasks, saved.getParentTaskId(), FacilityAssetType.facilityTask, CrudAssetPermission.save)
        .flatMap(parent -> children(parent).collectList().flatMap(rows -> {
          require(rows.size() == parent.getDeviceCount(), "task_devices_inaccessible");
          int submitted = submitted(rows);
          long version = parent.getVersion(); String from = parent.getState();
          parent.setSubmittedDeviceCount(submitted);
          parent.setState(submitted == rows.size() ? "review" : "executing");
          return records.update(tasks, parent, version)
            .flatMap(updated -> Objects.equals(from, updated.getState()) ? Mono.just(updated) :
              records.log(updated, "task", action, from, updated.getState(), null, List.of())
                .then(messages.task(updated, "review".equals(updated.getState()) ? "review" : action)).thenReturn(updated));
        })).thenReturn(saved);
    }
    if (!aggregate(saved)) return messages.task(saved, "review".equals(saved.getState()) ? "review" : action).thenReturn(saved);
    return children(saved).collectList().flatMap(rows -> {
      require(rows.size() == saved.getDeviceCount(), "task_devices_inaccessible");
      // Batch by original version: portable optimistic updates, with no per-device directory calls.
      var versions = rows.stream().collect(java.util.stream.Collectors.groupingBy(TaskEntity::getVersion));
      return Flux.fromIterable(versions.entrySet()).concatMap(entry -> {
        var update = tasks.createUpdate().set("state", saved.getState()).set("modifyTime", saved.getModifyTime())
          .set("version", entry.getKey() + 1);
        if (saved.getAssigneeId() == null) update.setNull("assigneeId"); else update.set("assigneeId", saved.getAssigneeId());
        if (saved.getAssigneeName() == null) update.setNull("assigneeName"); else update.set("assigneeName", saved.getAssigneeName());
        if (Set.of("transfer", "reject").contains(action)) {
          update.setNull("submissionId");
          if ("transfer".equals(action)) update.set("results", List.of());
          update.set("reviewComment", Objects.toString(saved.getReviewComment(), ""));
        }
        if (saved.getCompletedTime() != null) update.set("completedTime", saved.getCompletedTime());
        return update.in("id", entry.getValue().stream().map(TaskEntity::getId).toList())
          .and("version", entry.getKey()).and("tenantId", saved.getTenantId()).and("applicationId", saved.getApplicationId())
          .and("parentTaskId", saved.getId()).execute().doOnNext(count -> require(count == entry.getValue().size(), "conflict"));
      }).then(messages.task(saved, action)).thenReturn(saved);
    });
  }

  static int submitted(List<TaskEntity> rows) {
    return (int) rows.stream().filter(row -> Set.of("review", "completed").contains(row.getState())).count();
  }
}
