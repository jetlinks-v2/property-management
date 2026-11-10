package org.jetlinks.pro.property.facility.service;

import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetlinks.core.trace.MonoTracer;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

/** 整项任务消息；稳定事件编号防重复，不改任务版本，不在生单事务内发送。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FacilityTaskMessages {
  private final TaskStore tasks;
  private final NotificationStore notifications;
  private final FacilityExecution execution;
  private final FacilityDirectory directory;
  private final FacilityRecords records;
  private final AtomicBoolean polling = new AtomicBoolean();

  public Mono<Void> poll() {
    return Mono.defer(() -> {
      if (!polling.compareAndSet(false, true)) return Mono.empty();
      long now = System.currentTimeMillis();
      return tasks.createQuery().isNull("parentTaskId").and("deleted", false)
      .in("state", List.of("open", "claimed", "executing", "rectify"))
      .lte("scheduledTime", now).fetch().limitRate(32)
      .concatMap(task -> {
        String type = category(task, now);
        if (type == null) return Mono.empty();
        return execution.asOwner(task, () -> enqueue(task, type))
          .onErrorResume(error -> {
            log.warn("Task message failed: task={}, type={}", task.getId(), type, error);
            return Mono.empty();
          });
      }).then().doFinally(signal -> polling.set(false));
    }).as(MonoTracer.create("/property/facility-energy/jobs/task-messages"));
  }

  static String category(TaskEntity task, long now) {
    if (task.getParentTaskId() != null || task.getScheduledTime() == null || task.getDeadline() == null ||
        now < task.getScheduledTime() || !Set.of("open", "claimed", "executing", "rectify").contains(task.getState())) return null;
    if (now >= task.getDeadline()) return "overdue";
    if (task.getWarningTime() != null && now >= task.getWarningTime()) return "warning";
    return "open".equals(task.getState()) ? "new" : null;
  }

  Mono<Void> enqueue(TaskEntity task, String type) {
    Flux<String> recipients = "open".equals(task.getState())
      ? directory.taskAssignees(task).map(person -> person.id())
      : Mono.justOrEmpty(task.getAssigneeId()).flux();
    if (!"new".equals(type)) recipients = recipients.concatWith(Mono.justOrEmpty(task.getReviewerId()).flux());
    return recipients.distinct().concatMap(userId -> notifications.findById(message(task, type, userId).getId()).hasElement()
      .flatMap(exists -> exists ? Mono.empty() : eligible(task, userId, type)
        .filter(Boolean::booleanValue).flatMap(allowed -> store(task, type, userId)))).then();
  }

  Mono<Boolean> eligible(TaskEntity task, String userId, String type) {
    boolean reviewer = !"new".equals(type) && Objects.equals(userId, task.getReviewerId());
    return execution.asUser(task, userId, () -> Authentication.currentReactive()
      .filter(auth -> auth.hasPermission("facility-task", "query") &&
        (reviewer || auth.hasPermission("facility-task", "save")))
      .flatMap(auth -> records.read(tasks, task.getId(), FacilityAssetType.facilityTask,
        reviewer ? CrudAssetPermission.read : CrudAssetPermission.save))
      .flatMap(current -> {
        // 与实时状态取交集，接单/关闭与轮询并发时，不向旧候选人发送可接单提示。
        if (!Objects.equals(category(current, System.currentTimeMillis()), type)) return Mono.just(false);
        if (reviewer) return directory.taskAssignee(current, userId).thenReturn(true);
        if ("open".equals(current.getState())) return directory.canClaim(current);
        return Objects.equals(current.getAssigneeId(), userId)
          ? directory.taskAssignee(current, userId).thenReturn(true) : Mono.just(false);
      }).defaultIfEmpty(false))
      .onErrorResume(AccessDenyException.class, error -> Mono.just(false))
      .onErrorResume(BusinessException.class, error -> {
        if (Set.of("error.facility.employee_required", "error.facility.project_employee_required").contains(error.getMessage())) return Mono.just(false);
        return Mono.error(error);
      });
  }

  Mono<Void> store(TaskEntity task, String type, String userId) {
    var row = message(task, type, userId);
    return notifications.findById(row.getId())
      .switchIfEmpty(Mono.defer(() -> notifications.insert(row).thenReturn(row))
        // 唯一索引处理集群重放；仅吞重复键，真实写入故障保留给下一轮重试。
        .onErrorResume(error -> duplicate(error)
          ? notifications.findById(row.getId()).switchIfEmpty(Mono.error(error)) : Mono.error(error)))
      .then();
  }

  private static boolean duplicate(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof org.springframework.dao.DuplicateKeyException ||
          cause instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException) return true;
    }
    return false;
  }

  static NotificationEntity message(TaskEntity task, String type, String userId) {
    var row = FacilityScope.initialize(new NotificationEntity(), new FacilityContext(
      task.getTenantId(), task.getApplicationId(), task.getCreatorId(), task.getCreatorName()));
    FacilityScope.inherit(row, task);
    row.setId(FacilityRecords.stableId(task.getTenantId(), task.getApplicationId(), task.getId(), type, userId));
    row.setEventId(task.getId() + ":" + type); row.setTaskId(task.getId()); row.setMessageType(type);
    row.setTaskName(task.getName()); row.setTaskKind(task.getKind()); row.setUserId(userId); row.setRecipientKey(userId);
    row.setDeviceCount(task.getDeviceCount() == null ? 1 : task.getDeviceCount());
    row.setSubmittedDeviceCount(task.getSubmittedDeviceCount() == null ? 0 : task.getSubmittedDeviceCount());
    row.setScheduledTime(task.getScheduledTime()); row.setDeadline(task.getDeadline());
    String kind = "maintenance".equals(task.getKind()) ? "维保" : "巡检";
    row.setTitle(switch (type) { case "warning" -> kind + "任务即将超期"; case "overdue" -> kind + "任务已超期"; default -> "有新的" + kind + "任务"; });
    row.setMessage(task.getName()); row.setChannel("h5-task"); row.setState("generated"); row.setSequence(0);
    return row;
  }
}
