package org.jetlinks.pro.property.facility.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetlinks.pro.property.facility.entity.PlanEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FacilityPlanJsonTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void completedScheduleCanBeSentBackByBrowserWithoutOverflowingTheSaveRequest() throws Exception {
    // JavaScript rounds Long.MAX_VALUE to this number when a table row is submitted again.
    var plan = mapper.readValue("{\"name\":\"联调任务\",\"enabled\":false,\"nextRunTime\":9223372036854776000}", PlanEntity.class);
    assertFalse(plan.getEnabled());
    assertEquals("联调任务", plan.getName());
    assertNull(plan.getNextRunTime());
  }

  @Test
  void computedScheduleIsReadableButCannotBeOverriddenByTheClient() throws Exception {
    var plan = new PlanEntity();
    plan.setNextRunTime(123L);
    assertEquals(123L, mapper.readTree(mapper.writeValueAsString(plan)).get("nextRunTime").asLong());
    assertNull(mapper.readValue("{\"nextRunTime\":1}", PlanEntity.class).getNextRunTime());
  }
}
