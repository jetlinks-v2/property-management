package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FacilityPlanSaveTest {
  private final PlanStore store = mock(PlanStore.class);
  private final StandardStore standards = mock(StandardStore.class);
  private final GroupStore groups = mock(GroupStore.class);
  private final FacilityRecords records = mock(FacilityRecords.class);
  private final FacilityDirectory directory = mock(FacilityDirectory.class);
  private final FacilityDevices devices = mock(FacilityDevices.class);
  private final FacilityScope scope = mock(FacilityScope.class);
  private final FacilityCycles cycles = mock(FacilityCycles.class);
  private final FacilityPlans plans = new FacilityPlans(store, cycles, null, standards, groups, scope, records, directory, devices);

  @BeforeEach
  void setup() {
    when(directory.project("project")).thenReturn(Mono.just(new PropertyOrganizationInfo()));
    when(directory.projectEmployees("project")).thenReturn(reactor.core.publisher.Flux.just(
      new org.jetlinks.pro.property.base.user.PropertyEmployeeInfo("owner", "项目负责人", null, List.of("project"))));
    when(directory.employee("owner")).thenReturn(Mono.just(new PropertyUserInfo()));
    when(directory.externalOrganization("vendor")).thenReturn(Mono.empty());
    when(records.assertProject("project", CrudAssetPermission.save)).thenReturn(Mono.empty());
    when(scope.current()).thenReturn(Mono.just(new FacilityContext("tenant", "app", "creator", "Creator")));
    var cycle = FacilityCycleRulesTest.cycle(FacilityCycleRulesTest.window(0, 1440, 60, 1, 60));
    cycle.setProjectId("project");
    cycle.setKind("inspection");
    cycle.setEnabled(true);
    when(cycles.reference("cycle")).thenReturn(Mono.just(cycle));
    var standard = new StandardEntity();
    standard.setProjectId("project");
    standard.setKind("inspection");
    standard.setEnabled(true);
    when(records.read(standards, "standard", FacilityAssetType.facilityStandard, CrudAssetPermission.read)).thenReturn(Mono.just(standard));
    var group = new GroupEntity();
    group.setProjectId("project");
    group.setDeviceIds(List.of("device", "device"));
    when(records.read(groups, "group", FacilityAssetType.facilityGroup, CrudAssetPermission.read)).thenReturn(Mono.just(group));
    var device = new DeviceEntity();
    device.setProjectId("project");
    device.setSyncState("active");
    device.setInspectionEnabled(true);
    when(devices.read("device")).thenReturn(Mono.just(device));
    when(records.insert(eq(store), any(PlanEntity.class), eq(FacilityAssetType.facilityPlan)))
      .thenAnswer(call -> Mono.just(call.getArgument(1)));
  }

  private PlanEntity plan(String mode) {
    var plan = new PlanEntity();
    plan.setName("plan");
    plan.setProjectId("project");
    plan.setKind("inspection");
    plan.setMode(mode);
    plan.setStandardId("standard");
    plan.setCycleId("cycle");
    plan.setGroupIds(List.of("group"));
    plan.setReviewerId("owner");
    plan.setStartTime(System.currentTimeMillis());
    plan.setEndTime(System.currentTimeMillis() + 86400000);
    plan.setIntervalValue(1);
    plan.setIntervalUnit("day");
    plan.setDeadlineHours(24);
    return plan;
  }

  @Test
  void eitherLegacyFlagEnablesInspectionButDisabledDevicesCannotGenerate() {
    var device = new DeviceEntity();
    device.setProjectId("project"); device.setSyncState("active");
    device.setMaintenanceEnabled(true);
    when(devices.read("device")).thenReturn(Mono.just(device));
    StepVerifier.create(plans.save(plan("single"))).expectNextCount(1).verifyComplete();
    device.setMaintenanceEnabled(false);
    StepVerifier.create(plans.save(plan("single"))).expectErrorMatches(error -> error instanceof BusinessException &&
      error.getMessage().contains("device_work_disabled")).verify();
  }

  @Test
  void singleClearsHiddenAssignmentsAndContractorAndExpandsGroupOnce() {
    var plan = plan("single");
    plan.setAssigneeId("old-assignee");
    plan.setFollowUserId("old-follower");
    plan.setExternalOrganizationId("ordinary-org");
    StepVerifier.create(plans.save(plan)).assertNext(saved -> {
      assertNull(saved.getAssigneeId());
      assertNull(saved.getFollowUserId());
      assertNull(saved.getExternalOrganizationId());
      assertEquals("owner", saved.getReviewerId());
      assertEquals(List.of("group"), saved.getGroupIds());
    }).verifyComplete();
    verify(devices).read("device");
    verify(directory, never()).externalOrganization(any());
  }

  @Test
  void obsoleteModesAndContractorsAreClearedWithoutChangingProjectReviewer() {
    var plan = plan("double");
    plan.setExternalOrganizationId("vendor");
    plan.setFollowUserId("old-follower");
    StepVerifier.create(plans.save(plan)).assertNext(saved -> {
      assertNull(saved.getMode()); assertNull(saved.getFollowUserId());
      assertNull(saved.getExternalOrganizationId()); assertNull(saved.getAssigneeId());
      assertEquals("owner", saved.getReviewerId());
    }).verifyComplete();
    verify(directory, never()).externalOrganization(any());
    StepVerifier.create(plans.save(plan(null))).expectNextCount(1).verifyComplete();
  }

  @Test
  void missingKindOrResponsiblePersonReturnsBusinessValidation() {
    for (String field : List.of("kind", "reviewerId")) {
      var plan = plan("single");
      if (field.equals("kind")) plan.setKind(null);
      if (field.equals("mode")) plan.setMode(null);
      if (field.equals("reviewerId")) plan.setReviewerId(null);
      assertThrows(BusinessException.class, () -> plans.save(plan));
    }
    verify(records, never()).insert(any(), any(), any());
  }

  @Test
  void rejectsEmptyGroupsAndMissingOrCrossProjectCycle() {
    var empty = plan("single");
    empty.setGroupIds(List.of());
    assertThrows(BusinessException.class, () -> plans.save(empty));
    var missing = plan("single");
    missing.setCycleId(null);
    assertThrows(BusinessException.class, () -> plans.save(missing));
    var cycle = FacilityCycleRulesTest.cycle(FacilityCycleRulesTest.window(0, 1440, 60, 1, 60));
    cycle.setProjectId("another-project");
    when(cycles.reference("cycle")).thenReturn(Mono.just(cycle));
    StepVerifier.create(plans.save(plan("single"))).expectError(BusinessException.class).verify();
    verify(records, never()).insert(any(), any(), any());
  }

  @Test
  void rejectsCycleAndStandardOfDifferentWorkTypeInBothDirections() {
    for (String kind : List.of("inspection", "maintenance")) {
      String other = "inspection".equals(kind) ? "maintenance" : "inspection";
      var plan = plan("single"); plan.setKind(kind);
      var cycle = FacilityCycleRulesTest.cycle(FacilityCycleRulesTest.window(0, 1440, 60, 1, 60));
      cycle.setProjectId("project"); cycle.setKind(other); cycle.setEnabled(true);
      when(cycles.reference("cycle")).thenReturn(Mono.just(cycle));
      StepVerifier.create(plans.save(plan))
        .expectErrorMatches(e -> e instanceof BusinessException && "error.facility.invalid_cycle".equals(e.getMessage())).verify();
      cycle.setKind(kind);
      var standard = new StandardEntity(); standard.setProjectId("project"); standard.setKind(other); standard.setEnabled(true);
      when(records.read(standards, "standard", FacilityAssetType.facilityStandard, CrudAssetPermission.read)).thenReturn(Mono.just(standard));
      StepVerifier.create(plans.save(plan))
        .expectErrorMatches(e -> e instanceof BusinessException && "error.facility.invalid_standard".equals(e.getMessage())).verify();
    }
    verify(records, never()).insert(any(), any(), any());
  }

  @Test
  void responsiblePersonMustStillBeInTheProjectOrganization() {
    when(directory.projectEmployees("project")).thenReturn(reactor.core.publisher.Flux.empty());
    StepVerifier.create(plans.save(plan(null))).expectError(BusinessException.class).verify();
    verify(records, never()).insert(any(), any(), any());
  }
}
