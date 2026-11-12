package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.FaultCommand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

/** 故障来源、项目员工、版本及独立验收在同一事务内校验，不能用资产可写代替接单资格。 */
@Service
@RequiredArgsConstructor
public class FacilityFaults {
  private final FaultStore faults;
  private final AlertStore alerts;
  private final AlertEpisodeStore alertEpisodes;
  private final FacilityDevices devices;
  private final FacilityDirectory directory;
  private final FacilityRecords records;
  private final FacilityScope scope;
  private final FacilityFiles files;
  private final TaskStore tasks;
  private final FacilityEmployeeMessages messages;
  private final FacilityExecution execution;

  private Mono<org.jetlinks.pro.property.base.user.PropertyEmployeeInfo> participant(FacilityEntity parent, String userId, String action) {
    return directory.projectEmployee(parent.getProjectId(), userId)
      .then(execution.asUser(parent, userId, () -> org.hswebframework.web.authorization.Authentication.currentReactive()
        .filter(auth -> auth.hasPermission("facility-fault", "query") && auth.hasPermission("facility-fault", action))
        .switchIfEmpty(Mono.error(new org.hswebframework.web.authorization.exception.AccessDenyException()))
        .flatMap(auth -> directory.projectEmployee(parent.getProjectId(), userId))))
      .switchIfEmpty(Mono.error(new org.hswebframework.web.authorization.exception.AccessDenyException()));
  }

  @Transactional
  public Mono<FaultEntity> create(FaultEntity input) {
    return Mono.defer(() -> {
      require(input.getDeviceId() != null && input.getDescription() != null && !input.getDescription().isBlank() &&
        input.getDescription().length() <= 4000, "invalid_fault");
      require(input.getReviewerId() != null && input.getDeadline() != null && input.getDeadline() > System.currentTimeMillis(), "invalid_fault");
      require(input.getPriority() == null || Set.of("normal", "urgent").contains(input.getPriority()), "invalid_fault");
      require(input.getCategory() == null || Set.of("mechanical", "electrical", "communication", "leakage", "other").contains(input.getCategory()), "invalid_fault");
      // 登记时直接指定维修人同样属于派单，不能借保存接口绕过独立派单权限。
      Mono<Void> dispatch = input.getAssigneeId() == null || input.getAssigneeId().isBlank() ? Mono.empty()
        : org.hswebframework.web.authorization.Authentication.currentReactive()
          .filter(auth -> auth.hasPermission("facility-fault", "dispatch"))
          .switchIfEmpty(Mono.error(new org.hswebframework.web.authorization.exception.AccessDenyException())).then();
      return dispatch.then(devices.writable(input.getDeviceId())).flatMap(device -> source(input, device)
        .then(files.validate(input.getAttachments(), device.getProjectId()))
        .then(participant(device, input.getReviewerId(), "review"))
        .flatMap(reviewer -> scope.current().flatMap(context -> {
          require(!Objects.equals(input.getAssigneeId(), reviewer.id()), "invalid_review");
          var row = FacilityScope.initialize(new FaultEntity(), context);
          FacilityScope.inherit(row, device);
          row.setDeviceId(device.getId()); row.setDeviceName(device.getName());
          row.setName(input.getName() == null || input.getName().isBlank() ? device.getName() + "故障" : input.getName().trim());
          require(row.getName().length() <= 256, "invalid_fault");
          row.setDescription(input.getDescription().trim()); row.setAttachments(input.getAttachments() == null ? List.of() : input.getAttachments());
          row.setSourceType(Objects.toString(input.getSourceType(), "manual")); row.setSourceId(input.getSourceId());
          row.setReviewerId(reviewer.id()); row.setReviewerName(reviewer.name()); row.setDeadline(input.getDeadline());
          row.setPriority(Objects.toString(input.getPriority(), "normal")); row.setCategory(Objects.toString(input.getCategory(), "other"));
          row.setState(input.getAssigneeId() == null || input.getAssigneeId().isBlank() ? "open" : "assigned");
          Mono<Void> assignee = "assigned".equals(row.getState()) ? participant(row, input.getAssigneeId(), "save")
            .doOnNext(person -> { row.setAssigneeId(person.id()); row.setAssigneeName(person.name()); }).then() : Mono.empty();
          // 一次检查异常或一轮预警只生成一张故障单；重复点击返回同一授权记录。
          if (!"manual".equals(row.getSourceType())) row.setId(FacilityRecords.stableId(context.tenantId(), context.applicationId(), "fault", row.getSourceType(), row.getSourceId()));
          return assignee.then(faults.findById(row.getId()).flatMap(existing -> records.read(faults, existing.getId(), FacilityAssetType.facilityFault, CrudAssetPermission.read))
            .switchIfEmpty(Mono.defer(() -> records.insert(faults, row, FacilityAssetType.facilityFault)
              .flatMap(saved -> records.log(saved, "fault", "create", null, saved.getState(), saved.getDescription(), saved.getAttachments())
                .then(messages.fault(saved, "create")).thenReturn(saved)))));
        })));
    }).as(MonoTracer.create("/property/facility-energy/faults/create"));
  }

  private Mono<Void> source(FaultEntity input, DeviceEntity device) {
    String type = Objects.toString(input.getSourceType(), "manual");
    if ("manual".equals(type)) { input.setSourceId(null); return Mono.empty(); }
    require(input.getSourceId() != null && !input.getSourceId().isBlank(), "invalid_fault");
    // 预警从已授权设备继承范围，不能拿预警ID去校验设备资产。
    if ("alert".equals(type)) return alerts.findById(input.getSourceId())
      .flatMap(row -> {
        input.setSourceId(FacilityRecords.stableId(row.getId(), String.valueOf(row.getFirstTime())));
        return Mono.just(row);
      })
      .switchIfEmpty(alertEpisodes.findById(input.getSourceId()).cast(AlertEntity.class))
      .switchIfEmpty(Mono.error(new org.hswebframework.web.exception.NotFoundException()))
      .flatMap(row -> records.child(row, device).doOnNext(alert -> require(Objects.equals(alert.getDeviceId(), device.getId()), "project_mismatch"))).then();
    require("task".equals(type), "invalid_fault");
    return records.read(tasks, input.getSourceId(), FacilityAssetType.facilityTask, CrudAssetPermission.read)
      .flatMap(task -> records.child(task, device).doOnNext(row -> require(Objects.equals(row.getDeviceId(), device.getId()), "project_mismatch"))).then();
  }

  @Transactional
  public Mono<FaultEntity> action(String id, String action, FaultCommand command) {
    return records.read(faults, id, FacilityAssetType.facilityFault, CrudAssetPermission.save)
      .flatMap(row -> scope.current().flatMap(context -> directory.projectEmployee(row.getProjectId(), context.userId())
        .flatMap(actor -> {
          String from = row.getState();
          // 先做状态判断再查询新接单人；错误版本或不合法动作不能产生附带修改。
          FacilityFaultRules.transition(row, action, command, actor.id(), System.currentTimeMillis());
          Mono<Void> assignment = Set.of("dispatch", "transfer").contains(action)
            ? participant(row, command.userId(), "save").doOnNext(person -> row.setAssigneeName(person.name())).then()
            : Mono.empty();
          if ("reviewer".equals(action)) assignment = participant(row, command.userId(), "review")
            .doOnNext(person -> row.setReviewerName(person.name())).then();
          if ("start".equals(action)) row.setAssigneeName(actor.name());
          return assignment.then(files.validate(command.attachments(), row.getProjectId()))
            .then(records.update(faults, row, command.version(), FacilityRecords.nullFields(row,
              "claimedTime", "startedTime", "submittedTime", "resolution")))
            .flatMap(saved -> records.log(saved, "fault", action, from, saved.getState(), command.comment(), command.attachments())
              .then("reviewer".equals(action)
                ? "review".equals(saved.getState()) ? messages.fault(saved, "resolve") : Mono.empty()
                : messages.fault(saved, action)).thenReturn(saved));
        }))).as(MonoTracer.create("/property/facility-energy/faults/action"));
  }
}
