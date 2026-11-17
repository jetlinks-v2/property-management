package org.jetlinks.pro.property.facility.service;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

/** 任务消息唯一的到期/超期策略入口；重复匹配的规则不会生成第二套消息。 */
@Service
@RequiredArgsConstructor
public class FacilityTaskReminders {
  private final RuleStore rules;
  private final PlanStore plans;
  private final TaskStore tasks;
  private final FacilityRecords records;
  private final FacilityScope scope;
  private final FacilityReminderMigration migration;

  public Mono<String> category(TaskEntity task, long now) {
    if (!active(task, now)) return Mono.just("");
    return cycle(task).flatMap(cycle -> {
      Mono<?> migrated = cycle.isBlank() ? Mono.empty() : migration.migrate(cycle);
      var q = QueryParamEntity.newQuery().is("projectId", task.getProjectId()).and("enabled", true)
        .in("ruleType", List.of("dueSoon", "overdue")).noPaging().getParam();
      return migrated.then(scope.query(q, FacilityAssetType.facilityRule, "id"))
        .flatMapMany(param -> rules.createQuery().setParam(param).fetch()).take(20001).collectList()
        .doOnNext(rows -> FacilityScope.require(rows.size() <= 20000, "too_many"))
        .flatMapMany(Flux::fromIterable).filter(rule -> matches(rule, task, cycle, now))
        .concatMap(rule -> deviceMatches(rule, task).filter(Boolean::booleanValue).map(ignore -> rule))
        .next().map(rule -> now >= task.getDeadline() ? "overdue" : "warning")
        .defaultIfEmpty("open".equals(task.getState()) ? "new" : "");
    });
  }

  private Mono<String> cycle(TaskEntity task) {
    if (task.getCycleId() != null) return Mono.just(task.getCycleId());
    if (task.getPlanId() == null) return Mono.just("");
    // 旧任务没有周期快照；仅从原任务配置补取，不能读到其他项目或应用的引用。
    return records.read(plans, task.getPlanId(), FacilityAssetType.facilityPlan, CrudAssetPermission.read)
      .flatMap(parent -> records.child(task, parent).thenReturn(Objects.toString(parent.getCycleId(), "")));
  }

  private Mono<Boolean> deviceMatches(RuleEntity rule, TaskEntity task) {
    if (rule.getGroupId() != null && (task.getGroupIds() == null || !task.getGroupIds().contains(rule.getGroupId()))) return Mono.just(false);
    if (rule.getDeviceId() == null) return Mono.just(true);
    if (Objects.equals(rule.getDeviceId(), task.getDeviceId())) return Mono.just(true);
    var q = QueryParamEntity.newQuery().is("parentTaskId", task.getId()).and("deviceId", rule.getDeviceId()).getParam();
    return scope.query(q, FacilityAssetType.facilityTask, "id")
      .flatMap(param -> tasks.createQuery().setParam(param).count()).map(count -> count > 0);
  }

  static boolean active(TaskEntity task, long now) {
    return task.getParentTaskId() == null && task.getScheduledTime() != null && task.getDeadline() != null
      && now >= task.getScheduledTime() && Set.of("open", "claimed", "executing", "rectify").contains(task.getState());
  }

  static boolean matches(RuleEntity rule, TaskEntity task, String cycle, long now) {
    if (!active(task, now) || !Objects.equals(rule.getTenantId(), task.getTenantId()) || !Objects.equals(rule.getApplicationId(), task.getApplicationId())
      || !Objects.equals(rule.getProjectId(), task.getProjectId()) || !Boolean.TRUE.equals(rule.getEnabled())
      || Boolean.TRUE.equals(rule.getDeleted()) || rule.getTaskKind() != null && !rule.getTaskKind().equals(task.getKind())
      || rule.getCycleId() != null && !rule.getCycleId().equals(cycle)) return false;
    if (now >= task.getDeadline()) return "overdue".equals(rule.getRuleType());
    if (!"dueSoon".equals(rule.getRuleType())) return false;
    if (rule.getLegacySchedule() != null) {
      if (task.getWarningTime() != null) return now >= task.getWarningTime();
      var schedule = new CycleEntity(); schedule.setRule(rule.getLegacySchedule());
      var slot = FacilityCycleRules.next(schedule, task.getScheduledTime(), task.getScheduledTime(), task.getDeadline());
      return slot != null && slot.start() == task.getScheduledTime() && slot.warningTime() != null && now >= slot.warningTime();
    }
    return rule.getStaleAfterSeconds() != null && rule.getStaleAfterSeconds() > 0
      && (task.getDeadline() - now) / 1000d <= rule.getStaleAfterSeconds();
  }
}
