package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.facility.entity.PlanEntity;
import org.junit.jupiter.api.Test;

class FacilityPlanGenerationTest {
  private PlanEntity plan() {
    var plan = new PlanEntity();
    plan.setEnabled(true);
    plan.setStartTime(1000L);
    plan.setEndTime(5000L);
    plan.setNextRunTime(3000L);
    return plan;
  }

  private void rejects(PlanEntity plan, long now, String code) {
    var error = assertThrows(BusinessException.class,
      () -> FacilityPlans.generationTime(plan, now));
    assertEquals("error.facility." + code, error.getMessage());
  }

  @Test
  void periodicGenerationUsesDueSlotAndCannotGenerateEarly() {
    var plan = plan();
    assertEquals(3000L, FacilityPlans.generationTime(plan, 3500L));
    assertEquals(3000L, plan.getNextRunTime());
    rejects(plan, 2000L, "plan_not_due");
  }

  @Test
  void disabledAndNotStartedPlansRejectGeneration() {
    var plan = plan();
    rejects(plan, 999L, "plan_not_started");
    plan.setEnabled(false);
    rejects(plan, 3500L, "plan_disabled");
  }

  @Test
  void overduePeriodicSlotCanBeRecoveredButSlotsOutsideValidityAreRejected() {
    var plan = plan();
    assertEquals(3000L, FacilityPlans.generationTime(plan, 5001L));
    plan.setNextRunTime(5001L);
    rejects(plan, 6000L, "plan_finished");
  }

  @Test
  void manualGenerationHttpEndpointAndPermissionAreNotExposed() {
    for (var method : org.jetlinks.pro.property.facility.web.FacilityTaskController.class.getDeclaredMethods()) {
      var mapping = method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
      if (mapping != null) for (var path : mapping.value()) assertFalse(path.contains("/generate"));
      var permission = method.getAnnotation(org.hswebframework.web.authorization.annotation.ResourceAction.class);
      if (permission != null) assertNotEquals("generate", permission.id());
    }
  }
}
