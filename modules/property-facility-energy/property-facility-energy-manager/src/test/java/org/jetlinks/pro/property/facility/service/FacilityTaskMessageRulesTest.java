package org.jetlinks.pro.property.facility.service;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.CycleRule;
class FacilityTaskMessageRulesTest {
  CycleEntity cycle(Double warning, int times) {
    var c = new CycleEntity();
    c.setRule(new CycleRule(Map.of(1, List.of(new CycleRule.Window(480, 720, 60, times, 60 / times, "2", "1", warning, "1"))), Map.of()));
    return c;
  }
  @Test void warningStartsAtHalfAndMustPrecedeDeadline() {
    assertDoesNotThrow(() -> FacilityCycleRules.validate(cycle(30d, 1)));
    assertDoesNotThrow(() -> FacilityCycleRules.validate(cycle(40d, 1)));
    for (double bad : new double[]{29, 60, -1, Double.NaN, Double.POSITIVE_INFINITY})
      assertThrows(RuntimeException.class, () -> FacilityCycleRules.validate(cycle(bad, 1)));
    assertDoesNotThrow(() -> FacilityCycleRules.validate(cycle(15d, 2)));
    assertThrows(RuntimeException.class, () -> FacilityCycleRules.validate(cycle(30d, 2)));
    assertDoesNotThrow(() -> FacilityCycleRules.validate(cycle(null, 1)));
    var cleared = cycle(40d, 1); cleared.setRule(FacilityCycleRules.withoutWarnings(cleared.getRule()));
    assertNull(cleared.getRule().weekly().get(1).get(0).warningMinutes());
    assertDoesNotThrow(() -> FacilityCycleRules.validate(cleared));
  }
  @Test void slotCarriesExplicitWarningOffsetAndLegacyStaysUnset() {
    long start = FacilityCycleRulesTest.time("2026-11-09T08:00");
    var slot = FacilityCycleRules.next(cycle(40d, 1), start, start, start + 3600000);
    assertEquals(start + 2400000, slot.warningTime());
    assertNull(FacilityCycleRules.next(cycle(null, 1), start, start, start + 3600000).warningTime());
  }
  TaskEntity task() {
    var task = new TaskEntity(); task.setState("open"); task.setScheduledTime(1000L);
    task.setWarningTime(31000L); task.setDeadline(61000L); task.setTenantId("t");
    task.setApplicationId("a"); task.setProjectId("p"); task.setKind("inspection"); return task;
  }
  RuleEntity rule(String type) {
    var rule = new RuleEntity(); rule.setTenantId("t"); rule.setApplicationId("a");
    rule.setProjectId("p"); rule.setEnabled(true); rule.setRuleType(type); rule.setStaleAfterSeconds(30L); return rule;
  }
  @Test void explicitRulesMatchTimeBoundariesAndRespectProjectKindCycleAndEnabled() {
    var task = task(); var rule = rule("dueSoon");
    assertFalse(FacilityTaskReminders.matches(rule, task, "cycle", 999));
    assertFalse(FacilityTaskReminders.matches(rule, task, "cycle", 30999));
    assertTrue(FacilityTaskReminders.matches(rule, task, "cycle", 31000));
    assertFalse(FacilityTaskReminders.matches(rule, task, "cycle", 61000));
    var overdue = rule("overdue");
    assertFalse(FacilityTaskReminders.matches(overdue, task, "cycle", 60999));
    assertTrue(FacilityTaskReminders.matches(overdue, task, "cycle", 61000));
    rule.setTaskKind("maintenance"); assertFalse(FacilityTaskReminders.matches(rule, task, "cycle", 31000));
    rule.setTaskKind("inspection"); rule.setCycleId("another"); assertFalse(FacilityTaskReminders.matches(rule, task, "cycle", 31000));
    rule.setCycleId("cycle"); assertTrue(FacilityTaskReminders.matches(rule, task, "cycle", 31000));
    rule.setEnabled(false); assertFalse(FacilityTaskReminders.matches(rule, task, "cycle", 31000));
    rule.setEnabled(true); rule.setProjectId("other"); assertFalse(FacilityTaskReminders.matches(rule, task, "cycle", 31000));
    rule.setProjectId("p"); rule.setApplicationId("other"); assertFalse(FacilityTaskReminders.matches(rule, task, "cycle", 31000));
    rule.setApplicationId("a"); rule.setTenantId("other"); assertFalse(FacilityTaskReminders.matches(rule, task, "cycle", 31000));
  }
  @Test void onlyActiveWholeTasksReceiveReminders() {
    var task=task(); var rule=rule("overdue");
    for (String state : List.of("review", "completed", "closed", "external-submitted", "cancelled")) {
      task.setState(state); assertFalse(FacilityTaskReminders.matches(rule, task, "", 100000));
    }
    task.setState("open"); task.setParentTaskId("root");
    assertFalse(FacilityTaskReminders.matches(rule, task, "", 100000));
  }
  @Test void migratedWindowsKeepExactOldSnapshotsAndComputeFutureTaskTiming() {
    long start=FacilityCycleRulesTest.time("2026-11-09T08:00");
    var task=task(); task.setScheduledTime(start); task.setDeadline(start+3600000);
    var rule=rule("dueSoon"); rule.setLegacySchedule(cycle(40d,1).getRule());
    task.setWarningTime(start+1800000); // Existing task snapshot wins even if its cycle was edited later.
    assertTrue(FacilityTaskReminders.matches(rule,task,"",start+1800000));
    task.setWarningTime(null);
    assertFalse(FacilityTaskReminders.matches(rule,task,"",start+2399999));
    assertTrue(FacilityTaskReminders.matches(rule,task,"",start+2400000));
  }
}
