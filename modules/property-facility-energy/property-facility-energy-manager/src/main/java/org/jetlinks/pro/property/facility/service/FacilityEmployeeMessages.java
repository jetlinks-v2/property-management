package org.jetlinks.pro.property.facility.service;

import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

/** 员工通知复核接收人的当前项目和业务权限；配置失败保留记录，不撤销已完成的业务动作。 */
@Service
@RequiredArgsConstructor
public class FacilityEmployeeMessages {
  private final NotificationStore store;
  private final FacilityDirectory directory;
  private final FacilityExecution execution;
  private final FacilityRecords records;
  private final FaultStore faults;
  private final TaskStore tasks;
  private final FacilityNotificationPolicies policies;

  public Mono<Void> fault(FaultEntity fault, String action) {
    if (Set.of("start", "claim").contains(action)) return Mono.empty();
    String userId = Set.of("resolve", "create").contains(action) ? fault.getReviewerId() : fault.getAssigneeId();
    if ("close".equals(action) && (userId == null || userId.isBlank())) userId = fault.getReviewerId();
    if ("create".equals(action) && fault.getAssigneeId() != null) userId = fault.getAssigneeId();
    String type = switch (action) { case "resolve" -> "acceptance"; case "reject" -> "rejected"; case "approve" -> "accepted"; case "close" -> "closed"; default -> "assigned"; };
    var row = initialize(fault, userId, "fault:" + type, fault.getId() + ":" + fault.getVersion());
    row.setFaultId(fault.getId()); row.setDeviceId(fault.getDeviceId()); row.setTaskName(fault.getName()); row.setDeadline(fault.getDeadline());
    row.setTitle(switch (type) { case "acceptance" -> "维修待验收"; case "rejected" -> "维修需重新处理"; case "accepted" -> "维修已验收通过"; case "closed" -> "故障已关闭"; default -> "open".equals(fault.getState()) ? "故障待派单" : "有新的维修任务"; });
    row.setMessage("rejected".equals(type) ? "验收未通过，请查看处理记录并重新维修" : fault.getName());
    String permission = "resolve".equals(action) ? "review" : "open".equals(fault.getState()) ? "dispatch" : "save";
    return deliver(fault, row, "facility-fault", permission, () -> records.read(faults, fault.getId(), FacilityAssetType.facilityFault, CrudAssetPermission.read).then());
  }

  /** 整项任务状态消息与已有new/warning/overdue提醒分工，子任务只在最后一台提交时通知审批人。 */
  public Mono<Void> task(TaskEntity task, String action) {
    if (task.getParentTaskId() != null || !Set.of("transfer", "review", "reject", "approve", "close").contains(action)) return Mono.empty();
    boolean review = "review".equals(action);
    String user = review ? task.getReviewerId() : task.getAssigneeId();
    var row = initialize(task, user, "task:" + action, task.getId() + ":" + task.getVersion());
    row.setTaskId(task.getId()); row.setTaskName(task.getName()); row.setTaskKind(task.getKind());
    row.setDeviceCount(task.getDeviceCount()); row.setSubmittedDeviceCount(task.getSubmittedDeviceCount()); row.setScheduledTime(task.getScheduledTime()); row.setDeadline(task.getDeadline());
    row.setTitle(switch (action) { case "review" -> "作业任务待审批"; case "reject" -> "任务需重新处理"; case "approve" -> "任务审批通过"; case "close" -> "任务已关闭"; default -> "有新的派单任务"; });
    row.setMessage("reject".equals(action) ? task.getReviewComment() : task.getName());
    return deliver(task, row, "facility-task", review ? "review" : "save", () -> records.read(tasks, task.getId(), FacilityAssetType.facilityTask, CrudAssetPermission.read).then());
  }

  public Mono<Void> alert(AlertEntity alert, RuleEntity rule, DeviceEntity device) {
    String recipient = device.getResponsibleUserId() == null ? rule.getNotifyUserId() : device.getResponsibleUserId();
    var row = alertMessage(alert, rule, recipient);
    row.setDeviceId(device.getId()); row.setTaskName(device.getName());
    return deliver(alert, row, "facility-alert", "query", () -> records.child(alert, device)
      .flatMap(parent -> org.jetlinks.pro.assets.AssetsHolder.assertPermission(FacilityAssetType.facilityDevice, device.getId(), CrudAssetPermission.read)).then());
  }

  public Mono<Void> target(AlertEntity alert, RuleEntity rule, EnergyTargetEntity target) {
    var row = alertMessage(alert, rule, rule.getNotifyUserId()); row.setTargetId(target.getId()); row.setTaskName(target.getProjectName());
    return deliver(alert, row, "facility-alert", "query", () -> records.child(alert, target)
      .flatMap(parent -> org.jetlinks.pro.assets.AssetsHolder.assertPermission(FacilityAssetType.facilityTarget, target.getId(), CrudAssetPermission.read)).then());
  }

  private NotificationEntity alertMessage(AlertEntity alert, RuleEntity rule, String user) {
    var row = initialize(alert, user, "alert", alert.getId() + ":" + alert.getNotificationSequence());
    row.setAlertId(alert.getId()); row.setTitle("critical".equals(alert.getLevel()) ? "设备严重预警" : "设备异常提醒");
    row.setMessage(rule.getName() + "，当前" + alert.getValue() + Objects.toString(alert.getUnit(), "")); return row;
  }

  private Mono<Void> deliver(FacilityEntity parent, NotificationEntity row, String permission, String action, Supplier<Mono<Void>> authorize) {
    return policies.enabled(parent, row.getMessageType().startsWith("task:") ? "task" : row.getMessageType().startsWith("fault:") ? "fault" : "alert")
      .filter(Boolean::booleanValue).flatMap(enabled -> deliverEnabled(parent, row, permission, action, authorize));
  }

  private Mono<Void> deliverEnabled(FacilityEntity parent, NotificationEntity row, String permission, String action, Supplier<Mono<Void>> authorize) {
    if (row.getUserId() == null || row.getUserId().isBlank()) return failed(row, "employee_not_configured");
    return execution.asUser(parent, row.getUserId(), () -> Authentication.currentReactive()
      .filter(auth -> auth.hasPermission(permission, "query") && auth.hasPermission(permission, action))
      .switchIfEmpty(Mono.error(new AccessDenyException()))
      .flatMap(auth -> directory.projectEmployee(parent.getProjectId(), row.getUserId()))
      .flatMap(person -> authorize.get().thenReturn(true)))
      .switchIfEmpty(Mono.error(new AccessDenyException()))
      .onErrorResume(AccessDenyException.class, error -> failed(row, "recipient_permission_denied").thenReturn(false))
      .onErrorResume(BusinessException.class, error -> failed(row, "recipient_not_project_employee").thenReturn(false))
      .flatMap(allowed -> allowed ? insert(row) : Mono.empty());
  }

  private Mono<Void> failed(NotificationEntity row, String reason) {
    row.setState("failed"); row.setFailureReason(reason); return insert(row);
  }

  private NotificationEntity initialize(FacilityEntity parent, String user, String type, String event) {
    var row = FacilityScope.initialize(new NotificationEntity(), new FacilityContext(parent.getTenantId(), parent.getApplicationId(), parent.getCreatorId(), parent.getCreatorName()));
    FacilityScope.inherit(row, parent); row.setId(FacilityRecords.stableId(parent.getTenantId(), parent.getApplicationId(), event, Objects.toString(user, "unassigned"), type));
    row.setEventId(event); row.setMessageType(type); row.setUserId(user); row.setRecipientKey(Objects.toString(user, "unassigned")); row.setChannel("employee"); row.setState("generated"); row.setSequence(0); return row;
  }

  private Mono<Void> insert(NotificationEntity row) {
    // 唯一键竞争不可继续使用已失败的数据库事务；正常事件重放先读同一稳定ID。
    return store.findById(row.getId()).switchIfEmpty(Mono.defer(() -> store.insert(row).thenReturn(row))).then();
  }
}
