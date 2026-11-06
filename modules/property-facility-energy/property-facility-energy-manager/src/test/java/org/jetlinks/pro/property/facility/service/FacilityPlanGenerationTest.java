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

  private void rejects(PlanEntity plan, boolean temporary, long now, String code) {
    var error = assertThrows(BusinessException.class,
      () -> FacilityPlans.generationTime(plan, temporary, now));
    assertEquals("error.facility." + code, error.getMessage());
  }

  @Test
  void periodicGenerationUsesDueSlotAndTemporaryDoesNotAdvanceIt() {
    var plan = plan();
    assertEquals(3000L, FacilityPlans.generationTime(plan, false, 3500L));
    assertEquals(3500L, FacilityPlans.generationTime(plan, true, 3500L));
    assertEquals(3000L, plan.getNextRunTime());
    rejects(plan, false, 2000L, "plan_not_due");
    assertEquals(2000L, FacilityPlans.generationTime(plan, true, 2000L));
  }

  @Test
  void disabledAndNotStartedPlansRejectBothActions() {
    var plan = plan();
    for (boolean temporary : new boolean[]{false, true}) {
      rejects(plan, temporary, 999L, "plan_not_started");
      plan.setEnabled(false);
      rejects(plan, temporary, 3500L, "plan_disabled");
      plan.setEnabled(true);
    }
  }

  @Test
  void expiredTemporaryRejectsWhileOverduePeriodicSlotCanBeRecovered() {
    var plan = plan();
    rejects(plan, true, 5001L, "plan_finished");
    assertEquals(3000L, FacilityPlans.generationTime(plan, false, 5001L));
    plan.setNextRunTime(5001L);
    rejects(plan, false, 6000L, "plan_finished");
  }
}
