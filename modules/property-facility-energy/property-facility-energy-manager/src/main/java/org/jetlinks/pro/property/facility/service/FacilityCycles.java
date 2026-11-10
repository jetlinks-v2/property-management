package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

/** 周期计划保存及引用保护；变更只唤醒关联配置的排程游标，不修改已生成任务。 */
@Service
@RequiredArgsConstructor
public class FacilityCycles {
  private final CycleStore cycles;
  private final PlanStore plans;
  private final FacilityRecords records;
  private final FacilityScope scope;
  private final FacilityDirectory directory;
  private final FacilityQueries queries;

  public Mono<CycleEntity> read(String id) {
    require(id != null && !id.isBlank(), "cycle_required");
    return records.read(cycles, id, FacilityAssetType.facilityCycle, CrudAssetPermission.read);
  }

  /** Reserve the referenced version in the caller transaction, serializing saves/generation with edits or deletion. */
  public Mono<CycleEntity> reference(String id) {
    return read(id).flatMap(this::lock);
  }

  private Mono<CycleEntity> lock(CycleEntity row) {
    // A no-op update obtains a transaction row lock without changing the user's configuration version.
    return cycles.createUpdate().set("modifyTime", row.getModifyTime())
      .where("id", row.getId()).and("tenantId", row.getTenantId()).and("applicationId", row.getApplicationId())
      .and("version", row.getVersion()).and("deleted", false).execute()
      .doOnNext(count -> require(count == 1, "conflict")).thenReturn(row);
  }

  @Transactional
  public Mono<CycleEntity> save(CycleEntity input) {
    require(input.getName() != null && !input.getName().isBlank() && input.getKind() != null &&
      Set.of("inspection", "maintenance").contains(input.getKind()), "invalid_cycle");
    FacilityCycleRules.requireWarnings(input);
    return directory.project(input.getProjectId()).doOnNext(project -> input.setProjectName(project.getName()))
      .then(records.assertProject(input.getProjectId(), CrudAssetPermission.save))
      .then(scope.current())
      .flatMap(context -> (input.getId() == null
        ? Mono.just(FacilityScope.initialize(new CycleEntity(), context))
        : records.read(cycles, input.getId(), FacilityAssetType.facilityCycle, CrudAssetPermission.save)))
      .flatMap(row -> {
        boolean create = input.getId() == null;
        require(create || (Objects.equals(row.getVersion(), input.getVersion()) &&
          Objects.equals(row.getProjectId(), input.getProjectId()) && Objects.equals(row.getKind(), input.getKind())), "conflict");
        long version = row.getVersion();
        row.setName(input.getName());
        row.setProjectName(input.getProjectName());
        row.setProjectId(input.getProjectId());
        row.setOrganizationId(input.getProjectId());
        row.setKind(input.getKind());
        row.setRule(input.getRule());
        row.setEnabled(Boolean.TRUE.equals(input.getEnabled()));
        return create ? records.insert(cycles, row, FacilityAssetType.facilityCycle)
          : records.update(cycles, row, version).flatMap(saved -> wakeConfigurations(saved).thenReturn(saved));
      });
  }

  private Mono<Void> wakeConfigurations(CycleEntity cycle) {
    // Updating a schedule inherently affects its references. Only the derived cursor is reset,
    // constrained to the same tenant/application; CAS makes concurrent generation roll back safely.
    return plans.createQuery().where("cycleId", cycle.getId())
      .and("tenantId", cycle.getTenantId()).and("applicationId", cycle.getApplicationId())
      .and("deleted", false).fetch().limitRate(32)
      .concatMap(plan -> {
        long version = plan.getVersion();
        var next = Boolean.TRUE.equals(cycle.getEnabled())
          ? FacilityCycleRules.next(cycle, System.currentTimeMillis(), plan.getStartTime(), plan.getEndTime()) : null;
        plan.setNextRunTime(next == null ? Long.MAX_VALUE : next.start());
        return records.update(plans, plan, version);
      }).then();
  }

  @Transactional
  public Mono<Void> remove(String id, long version) {
    return records.read(cycles, id, FacilityAssetType.facilityCycle, CrudAssetPermission.delete)
      .flatMap(this::lock)
      .flatMap(row -> plans.createQuery().where("cycleId", id)
        .and("tenantId", row.getTenantId()).and("applicationId", row.getApplicationId()).and("deleted", false).count()
        .doOnNext(count -> require(count == 0, "cycle_in_use"))
        .then(queries.remove(cycles, id, version, FacilityAssetType.facilityCycle)));
  }
}
