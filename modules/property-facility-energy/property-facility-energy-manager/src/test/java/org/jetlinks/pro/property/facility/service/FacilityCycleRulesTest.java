package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.jetlinks.pro.property.facility.entity.CycleEntity;
import org.jetlinks.pro.property.facility.model.CycleRule;
import org.hswebframework.web.exception.BusinessException;
import org.junit.jupiter.api.Test;

class FacilityCycleRulesTest {
  static long time(String value) { return LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(); }
  static CycleEntity cycle(CycleRule.Window w) {
    var cycle = new CycleEntity();
    cycle.setRule(new CycleRule(Map.of(1, List.of(w)), Map.of()));
    return cycle;
  }
  static CycleRule.Window window(int start, int end, int interval, int times, int gap) {
    return new CycleRule.Window(start, end, interval, times, gap, null, null);
  }
  static FacilityCycleRules.Slot next(CycleEntity cycle, long from) {
    return FacilityCycleRules.next(cycle, from, time("2026-11-09T00:00"), time("2027-11-09T00:00"));
  }
  @Test void fourHoursProduceFourIndependentWorkWindowsAndSkipIncompleteTail() {
    var cycle = cycle(window(480, 725, 60, 1, 60));
    long cursor = time("2026-11-09T00:00");
    for (int hour = 8; hour < 12; hour++) {
      var slot = next(cycle, cursor);
      assertEquals(time("2026-11-09T" + String.format("%02d:00", hour)), slot.start());
      assertEquals(slot.start() + 3600000, slot.end());
      cursor = slot.start() + 1;
    }
    assertEquals(time("2026-11-16T08:00"), next(cycle, cursor).start());
  }
  @Test void twoExecutionsPerHourUseSeparateHalfHourSlotsAndValidateMinimumGap() {
    var cycle = cycle(window(480, 600, 60, 2, 30));
    assertEquals(time("2026-11-09T08:30"), next(cycle, time("2026-11-09T08:01")).start());
    cycle.setRule(new CycleRule(Map.of(1, List.of(window(480, 600, 60, 2, 31))), Map.of()));
    assertThrows(BusinessException.class, () -> FacilityCycleRules.validate(cycle));
  }
  @Test void overnightSlotsKeepPreviousLogicalDayAndRejectCrossDayOverlap() {
    var cycle = cycle(window(1380, 120, 60, 1, 60));
    assertEquals(time("2026-11-10T01:00"), next(cycle, time("2026-11-10T00:01")).start());
    cycle.setRule(new CycleRule(Map.of(1, List.of(window(1380, 120, 60, 1, 60)),
      2, List.of(window(60, 180, 60, 1, 60))), Map.of()));
    assertThrows(BusinessException.class, () -> FacilityCycleRules.validate(cycle));
  }
  @Test void dateOverrideCanRestAndPlanValidityDoesNotBackfillEarlierSlots() {
    var cycle = cycle(window(480, 720, 60, 1, 60));
    cycle.setRule(new CycleRule(cycle.getRule().weekly(), Map.of("2026-11-09", List.of())));
    assertEquals(time("2026-11-16T08:00"), next(cycle, time("2026-11-09T00:00")).start());
    assertNull(FacilityCycleRules.next(cycle, time("2026-11-09T00:00"), time("2026-11-09T00:00"), time("2026-11-15T00:00")));
  }
  @Test void leapDayAndCalendarYearBoundaryUseRealCalendar() {
    var cycle = cycle(window(480, 540, 60, 1, 60));
    cycle.setRule(new CycleRule(Map.of(), Map.of("2028-02-29", List.of(window(480, 540, 60, 1, 60)),
      "2029-01-01", List.of(window(480, 540, 60, 1, 60)))));
    assertEquals(time("2028-02-29T08:00"), FacilityCycleRules.next(cycle, time("2028-02-28T09:00"), time("2028-02-28T09:00"), time("2029-01-02T00:00")).start());
    assertEquals(time("2029-01-01T08:00"), FacilityCycleRules.next(cycle, time("2028-03-01T00:00"), time("2028-02-28T09:00"), time("2029-01-02T00:00")).start());
  }

  @Test void dateOnlyRuleGeneratesOneSlotAtSpecifiedMinuteWithoutWeeklyRecurrence() {
    var cycle = cycle(window(480, 540, 60, 1, 60));
    cycle.setRule(new CycleRule(Map.of(), Map.of("2026-11-10", List.of(window(1095, 1155, 60, 1, 60)))));
    var slot = next(cycle, time("2026-11-10T00:00"));
    assertEquals(time("2026-11-10T18:15"), slot.start());
    assertEquals(time("2026-11-10T19:15"), slot.end());
    assertNull(next(cycle, slot.start() + 1));
  }
}
