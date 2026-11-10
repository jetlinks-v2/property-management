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
    assertThrows(RuntimeException.class, () -> FacilityCycleRules.requireWarnings(cycle(null, 1)));
  }
  @Test void slotCarriesExplicitWarningOffsetAndLegacyStaysUnset() {
    long start = FacilityCycleRulesTest.time("2026-11-09T08:00");
    var slot = FacilityCycleRules.next(cycle(40d, 1), start, start, start + 3600000);
    assertEquals(start + 2400000, slot.warningTime());
    assertNull(FacilityCycleRules.next(cycle(null, 1), start, start, start + 3600000).warningTime());
  }
  @Test void onlyActiveWholeTasksProduceOneCurrentCategory() {
    var task = new TaskEntity(); task.setState("open"); task.setScheduledTime(1000L);
    task.setWarningTime(31000L); task.setDeadline(61000L);
    assertNull(FacilityTaskMessages.category(task, 999));
    assertEquals("new", FacilityTaskMessages.category(task, 1000));
    assertEquals("warning", FacilityTaskMessages.category(task, 31000));
    assertEquals("overdue", FacilityTaskMessages.category(task, 61000));
    task.setState("claimed"); assertNull(FacilityTaskMessages.category(task, 1000));
    assertEquals("warning", FacilityTaskMessages.category(task, 31000));
    for (String state : List.of("review", "completed", "closed", "external-submitted")) {
      task.setState(state); assertNull(FacilityTaskMessages.category(task, 100000));
    }
    task.setState("open"); task.setParentTaskId("root");
    assertNull(FacilityTaskMessages.category(task, 100000));
  }
}
