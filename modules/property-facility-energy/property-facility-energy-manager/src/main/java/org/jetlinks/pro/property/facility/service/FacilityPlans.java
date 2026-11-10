package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.*;

/** 计划保存时验证范围与人员；周期推进和任务生成同一事务，唯一键保护重复触发。 */
@Service
@RequiredArgsConstructor
public class FacilityPlans {

  private final PlanStore plans;
  private final FacilityCycles cycles;
  private final TaskStore tasks;
  private final StandardStore standards;
  private final GroupStore groups;
  private final FacilityScope scope;
  private final FacilityRecords records;
  private final FacilityDirectory directory;
  private final FacilityDevices devices;
  private final org.jetlinks.pro.assets.AssetBindManager bindings;

  public Mono<PlanEntity> read(String id) {
    return records.read(
      plans,
      id,
      FacilityAssetType.facilityPlan,
      CrudAssetPermission.read
    );
  }

  @Transactional
  public Mono<PlanEntity> save(PlanEntity input) {
    require(
      input.getName() != null &&
        !input.getName().isBlank() &&
        input.getKind() != null &&
        Set.of("inspection", "maintenance").contains(input.getKind()) &&
        input.getMode() != null &&
        Set.of("single", "double").contains(input.getMode()),
      "invalid_plan"
    );
    require(input.getGroupIds() != null && !input.getGroupIds().isEmpty(), "groups_required");
    input.setDeviceIds(List.of());
    require(input.getStartTime() != null && input.getEndTime() != null &&
      input.getStartTime() >= 0 && input.getEndTime() > input.getStartTime() && input.getEndTime() - input.getStartTime() <= 3660L * 86400000, "invalid_plan");
    require(input.getCycleId() != null && !input.getCycleId().isBlank(), "cycle_required");
    require(
      input.getReviewerId() != null && !input.getReviewerId().isBlank(),
      "reviewer_required"
    );
    boolean external = "double".equals(input.getMode());
    if (external) require(
      input.getExternalOrganizationId() != null && !input.getExternalOrganizationId().isBlank(),
      "external_organization_required"
    );
    // Plans create unassigned tasks; the responsible person handles internal follow-up and review.
    input.setAssigneeId(null);
    input.setFollowUserId(external ? input.getReviewerId() : null);
    if (!external) input.setExternalOrganizationId(null);
    Mono<Void> refs = directory
      .project(input.getProjectId())
      .doOnNext(project -> input.setProjectName(project.getName()))
      .then(records.assertProject(input.getProjectId(), CrudAssetPermission.save))
      .thenMany(Flux.defer(() -> directory.projectEmployees(input.getProjectId())))
      .filter(person -> input.getReviewerId().equals(person.id())).next()
      .switchIfEmpty(Mono.error(new org.hswebframework.web.exception.BusinessException("error.facility.reviewer_required")))
      .doOnNext(person -> input.setReviewerName(person.name()))
      .then();
    if (external) refs = refs
      .then(directory.externalOrganization(input.getExternalOrganizationId()))
      .then(directory.employeeRole(input.getReviewerId(), input.getExternalOrganizationId(), false));
    return refs.then(Mono.defer(() -> cycles.reference(input.getCycleId()))).doOnNext(cycle -> {
      require(Boolean.TRUE.equals(cycle.getEnabled()) && input.getProjectId().equals(cycle.getProjectId()) &&
        input.getKind().equals(cycle.getKind()), "invalid_cycle");
      FacilityCycleRules.validate(cycle);
      input.setCycleName(cycle.getName());
      var slot = FacilityCycleRules.next(cycle, System.currentTimeMillis(), input.getStartTime(), input.getEndTime());
      input.setNextRunTime(slot == null ? Long.MAX_VALUE : slot.start());
    })
      .then(
        records.read(
          standards,
          input.getStandardId(),
          FacilityAssetType.facilityStandard,
          CrudAssetPermission.read
        )
      )
      .doOnNext(s -> {
        input.setStandardName(s.getName());
        require(
          input.getProjectId().equals(s.getProjectId()) &&
            input.getKind().equals(s.getKind()) &&
            Boolean.TRUE.equals(s.getEnabled()),
          "invalid_standard"
        );
      })
      .then(resolveDevices(input))
      .then(scope.current())
      .flatMap(c -> {
        boolean create = input.getId() == null;
        return (
          create
            ? Mono.just(FacilityScope.initialize(new PlanEntity(), c))
            : records.read(
                plans,
                input.getId(),
                FacilityAssetType.facilityPlan,
                CrudAssetPermission.save
              )
        ).flatMap(row -> {
          require(
            create ||
              (Objects.equals(row.getVersion(), input.getVersion()) &&
                row.getProjectId().equals(input.getProjectId())),
            "conflict"
          );
          long v = row.getVersion();
          row.setProjectId(input.getProjectId());
          row.setOrganizationId(input.getProjectId());
          row.setName(input.getName());
          row.setProjectName(input.getProjectName());
          row.setCycleName(input.getCycleName());
          row.setStandardName(input.getStandardName());
          row.setReviewerName(input.getReviewerName());
          row.setKind(input.getKind());
          boolean changedCycle = !Objects.equals(row.getCycleId(), input.getCycleId()) ||
            !Objects.equals(row.getStartTime(), input.getStartTime()) || !Objects.equals(row.getEndTime(), input.getEndTime());
          row.setStandardId(input.getStandardId());
          row.setCycleId(input.getCycleId());
          row.setDeviceIds(input.getDeviceIds());
          row.setGroupIds(input.getGroupIds());
          row.setAssigneeId(input.getAssigneeId());
          row.setFollowUserId(input.getFollowUserId());
          row.setReviewerId(input.getReviewerId());
          row.setExternalOrganizationId(input.getExternalOrganizationId());
          row.setMode(input.getMode());
          row.setIntervalValue(input.getIntervalValue());
          row.setIntervalUnit(input.getIntervalUnit());
          row.setStartTime(input.getStartTime());
          row.setEndTime(input.getEndTime());
          row.setDeadlineHours(input.getDeadlineHours());
          boolean resuming = !Boolean.TRUE.equals(row.getEnabled());
          row.setEnabled(input.getEnabled());
          if (create || changedCycle || resuming) row.setNextRunTime(input.getNextRunTime());
          return create
            ? records.insert(plans, row, FacilityAssetType.facilityPlan)
            : records.update(
                plans,
                row,
                v,
                FacilityRecords.nullFields(
                  row,
                  "assigneeId",
                  "followUserId",
                  "externalOrganizationId"
                )
              );
        });
      });
  }

  private Mono<List<DeviceEntity>> resolveDevices(PlanEntity plan) {
    require(
      plan.getGroupIds() != null && !plan.getGroupIds().isEmpty() && plan.getGroupIds().size() <= 1000,
      "too_many"
    );
    return Flux.fromIterable(plan.getGroupIds())
      .concatMap(id ->
        records.read(
          groups,
          id,
          FacilityAssetType.facilityGroup,
          CrudAssetPermission.read
        )
      )
      .doOnNext(g ->
        require(
          plan.getProjectId().equals(g.getProjectId()) && g.getDeviceIds() != null && !g.getDeviceIds().isEmpty(),
          "project_mismatch"
        )
      )
      .flatMapIterable(GroupEntity::getDeviceIds)
      .distinct()
      .take(1001)
      .collectList()
      .doOnNext(ids ->
        require(!ids.isEmpty() && ids.size() <= 1000, "invalid_plan")
      )
      .flatMapMany(Flux::fromIterable)
      .concatMap(devices::read)
      .doOnNext(d -> {
        require(
          plan.getProjectId().equals(d.getProjectId()),
          "project_mismatch"
        );
        FacilityDevices.requireActive(d);
        require(
          Boolean.TRUE.equals(
            "inspection".equals(plan.getKind())
              ? d.getInspectionEnabled()
              : d.getMaintenanceEnabled()
          ),
          "device_unavailable"
        );
      })
      .collectList();
  }

  @Transactional
  public Mono<List<TaskEntity>> generate(String id, boolean temporary) {
    return records.read(plans, id, FacilityAssetType.facilityPlan, CrudAssetPermission.save)
      .flatMap(plan -> {
        require(Boolean.TRUE.equals(plan.getEnabled()), "plan_disabled");
        return cycles.reference(plan.getCycleId()).flatMap(cycle -> {
          require(plan.getProjectId().equals(cycle.getProjectId()) && plan.getKind().equals(cycle.getKind()), "invalid_cycle");
          long now = System.currentTimeMillis();
          long requested = generationTime(plan, temporary, now);
          var slot = Boolean.TRUE.equals(cycle.getEnabled())
            ? FacilityCycleRules.next(cycle, requested, plan.getStartTime(), plan.getEndTime()) : null;
          if (temporary) {
            require(now >= plan.getStartTime() && now < plan.getEndTime() && slot != null, "plan_finished");
            slot = new FacilityCycleRules.Slot(now, Math.min(now + slot.end() - slot.start(), plan.getEndTime()));
          }
          if (slot == null || slot.start() > now) {
            plan.setNextRunTime(slot == null ? Long.MAX_VALUE : slot.start());
            return records.update(plans, plan, plan.getVersion()).thenReturn(List.<TaskEntity>of());
          }
          var executionSlot = slot;
          return records.read(standards, plan.getStandardId(), FacilityAssetType.facilityStandard, CrudAssetPermission.read)
            .doOnNext(standard -> require(Boolean.TRUE.equals(standard.getEnabled()) &&
              plan.getProjectId().equals(standard.getProjectId()) && plan.getKind().equals(standard.getKind()), "invalid_standard"))
            .flatMap(standard -> createTasks(plan, standard, executionSlot))
            .flatMap(generated -> {
              if (temporary) return Mono.just(generated);
              var next = FacilityCycleRules.next(cycle, executionSlot.start() + 1, plan.getStartTime(), plan.getEndTime());
              plan.setNextRunTime(next == null ? Long.MAX_VALUE : next.start());
              return records.update(plans, plan, plan.getVersion()).thenReturn(generated);
            });
        });
      });
  }

  private Mono<List<TaskEntity>> createTasks(PlanEntity plan, StandardEntity standard, FacilityCycleRules.Slot slot) {
    long scheduled = slot.start();
    return resolveDevices(plan).flatMapMany(Flux::fromIterable)
              .concatMap(device ->
                scope.current().flatMap(c -> {
                  var task = FacilityScope.initialize(new TaskEntity(), c);
                  FacilityScope.inherit(task, plan);
                  task.setId(
                    FacilityRecords.stableId(
                      plan.getTenantId(),
                      plan.getApplicationId(),
                      plan.getId(),
                      Long.toString(scheduled),
                      device.getId()
                    )
                  );
                  task.setName(plan.getName());
                  task.setPlanId(plan.getId());
                  task.setKind(plan.getKind());
                  task.setDeviceId(device.getId());
                  task.setDeviceName(device.getName());
                  task.setStandardId(standard.getId());
                  task.setStandardSnapshot(standard.getCheckItems());
                  task.setInstructionSnapshot(standard.getInstruction());
                  task.setInstructionAttachments(standard.getAttachments());
                  task.setDeviceSnapshot(
                    new LinkedHashMap<>(
                      Map.of(
                        "cloudDeviceId",
                        device.getCloudDeviceId(),
                        "name",
                        Objects.toString(device.getName(), ""),
                        "location",
                        Objects.toString(device.getLocation(), ""),
                        "specification",
                        Objects.toString(device.getSpecification(), "")
                      )
                    )
                  );
                  task.setMode(plan.getMode());
                  task.setAssigneeId(plan.getAssigneeId());
                  task.setFollowUserId(plan.getFollowUserId());
                  task.setReviewerId(plan.getReviewerId());
                  task.setExternalOrganizationId(
                    plan.getExternalOrganizationId()
                  );
                  task.setScheduledTime(scheduled);
                  task.setDeadline(
                    slot.end()
                  );
                  task.setState(
                    plan.getAssigneeId() == null ||
                      plan.getAssigneeId().isBlank()
                      ? "open"
                      : "claimed"
                  );
                  return tasks
                    .findById(task.getId())
                    .switchIfEmpty(
                      records.insert(
                        tasks,
                        task,
                        FacilityAssetType.facilityTask
                      )
                    )
                    .flatMap(this::bindExternalTask);
                })
              )
      .collectList();
  }

  /** 临时任务不推进周期，但仍须处于计划有效期；到期判断由服务端再次校验，防止列表过期。 */
  static long generationTime(PlanEntity plan, boolean temporary, long now) {
    require(Boolean.TRUE.equals(plan.getEnabled()), "plan_disabled");
    require(plan.getStartTime() != null && plan.getEndTime() != null &&
      plan.getNextRunTime() != null, "invalid_plan");
    require(now >= plan.getStartTime(), "plan_not_started");
    long scheduled = temporary ? now : plan.getNextRunTime();
    require(scheduled < plan.getEndTime(), "plan_finished");
    require(scheduled <= now, "plan_not_due");
    return scheduled;
  }

  /** Share the task snapshot with its validated collaboration organization, never the device or plan. */
  Mono<TaskEntity> bindExternalTask(TaskEntity task) {
    if (!"double".equals(task.getMode())) return Mono.just(task);
    require(task.getExternalOrganizationId() != null && !task.getExternalOrganizationId().isBlank(), "invalid_plan");
    // Generation already authorizes the source plan. BindManager can bind the configured target;
    // a caller's tenant holder only auto-binds its own dimension and silently ignores an external org.
    var request = org.jetlinks.pro.assets.AssetBindRequest.builder()
      .targetType("org").targetId(task.getExternalOrganizationId())
      .assetType(FacilityAssetType.facilityTask.getId()).assetIdList(List.of(task.getId()))
      .permission(List.of(CrudAssetPermission.read.name(), CrudAssetPermission.save.name()))
      .build();
    return bindings.bindAssets(Flux.just(request)).thenReturn(task);
  }
}
