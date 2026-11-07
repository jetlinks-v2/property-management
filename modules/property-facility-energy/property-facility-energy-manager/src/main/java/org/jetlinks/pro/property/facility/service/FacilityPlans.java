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
        Set.of("inspection", "maintenance").contains(input.getKind()) &&
        Set.of("single", "double").contains(input.getMode()),
      "invalid_plan"
    );
    require(
      input.getStartTime() != null &&
        input.getEndTime() != null &&
        input.getEndTime() >= input.getStartTime() &&
        input.getDeadlineHours() != null &&
        input.getDeadlineHours() > 0 &&
        input.getDeadlineHours() <= 8760,
      "invalid_plan"
    );
    FacilityRules.next(
      input.getStartTime(),
      input.getIntervalValue(),
      input.getIntervalUnit()
    );
    require(
      input.getReviewerId() != null && !input.getReviewerId().isBlank(),
      "reviewer_required"
    );
    if ("double".equals(input.getMode())) require(
      input.getFollowUserId() != null &&
        input.getExternalOrganizationId() != null &&
        !input.getExternalOrganizationId().isBlank(),
      "invalid_plan"
    );
    Mono<Void> refs = directory
      .project(input.getProjectId())
      .then(
        records.assertProject(input.getProjectId(), CrudAssetPermission.save)
      )
      .then(directory.employee(input.getReviewerId()))
      .then();
    if (
      input.getAssigneeId() != null && !input.getAssigneeId().isBlank()
    ) refs = refs.then(directory.employee(input.getAssigneeId()).then());
    if ("double".equals(input.getMode())) {
      require(
        !Objects.equals(input.getAssigneeId(), input.getFollowUserId()),
        "external_role_mismatch"
      );
      refs = refs
        .then(directory.externalOrganization(input.getExternalOrganizationId()))
        .then(
          directory.employeeRole(
            input.getFollowUserId(),
            input.getExternalOrganizationId(),
            false
          )
        )
        .then(
          directory.employeeRole(
            input.getReviewerId(),
            input.getExternalOrganizationId(),
            false
          )
        );
      if (
        input.getAssigneeId() != null && !input.getAssigneeId().isBlank()
      ) refs = refs.then(
        directory.employeeRole(
          input.getAssigneeId(),
          input.getExternalOrganizationId(),
          true
        )
      );
    }
    return refs
      .then(
        records.read(
          standards,
          input.getStandardId(),
          FacilityAssetType.facilityStandard,
          CrudAssetPermission.read
        )
      )
      .doOnNext(s ->
        require(
          input.getProjectId().equals(s.getProjectId()) &&
            input.getKind().equals(s.getKind()) &&
            Boolean.TRUE.equals(s.getEnabled()),
          "invalid_standard"
        )
      )
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
          row.setKind(input.getKind());
          row.setStandardId(input.getStandardId());
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
          row.setEnabled(input.getEnabled());
          if (create) row.setNextRunTime(input.getStartTime());
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
      plan.getDeviceIds().size() + plan.getGroupIds().size() <= 1000,
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
          plan.getProjectId().equals(g.getProjectId()),
          "project_mismatch"
        )
      )
      .flatMapIterable(GroupEntity::getDeviceIds)
      .mergeWith(Flux.fromIterable(plan.getDeviceIds()))
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
    return records
      .read(plans, id, FacilityAssetType.facilityPlan, CrudAssetPermission.save)
      .flatMap(plan -> {
        long scheduled = generationTime(plan, temporary, System.currentTimeMillis());
        return records
          .read(
            standards,
            plan.getStandardId(),
            FacilityAssetType.facilityStandard,
            CrudAssetPermission.read
          )
          .doOnNext(standard -> require(
            Boolean.TRUE.equals(standard.getEnabled()) &&
              plan.getProjectId().equals(standard.getProjectId()) &&
              plan.getKind().equals(standard.getKind()),
            "invalid_standard"
          ))
          .flatMap(standard ->
            resolveDevices(plan)
              .flatMapMany(Flux::fromIterable)
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
                    scheduled + plan.getDeadlineHours() * 3600000L
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
              .collectList()
          )
          .flatMap(generated -> {
            if (temporary) return Mono.just(generated);
            long v = plan.getVersion();
            plan.setNextRunTime(
              FacilityRules.next(
                scheduled,
                plan.getIntervalValue(),
                plan.getIntervalUnit()
              )
            );
            if (plan.getNextRunTime() > plan.getEndTime()) plan.setEnabled(
              false
            );
            return records.update(plans, plan, v).thenReturn(generated);
          });
      });
  }

  /** 临时任务不推进周期，但仍须处于计划有效期；到期判断由服务端再次校验，防止列表过期。 */
  static long generationTime(PlanEntity plan, boolean temporary, long now) {
    require(Boolean.TRUE.equals(plan.getEnabled()), "plan_disabled");
    require(plan.getStartTime() != null && plan.getEndTime() != null &&
      plan.getNextRunTime() != null, "invalid_plan");
    require(now >= plan.getStartTime(), "plan_not_started");
    long scheduled = temporary ? now : plan.getNextRunTime();
    require(scheduled <= plan.getEndTime(), "plan_finished");
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
