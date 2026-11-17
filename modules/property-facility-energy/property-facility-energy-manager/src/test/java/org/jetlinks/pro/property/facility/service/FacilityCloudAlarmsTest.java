package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.property.facility.model.CloudAlarmRecord;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FacilityCloudAlarmsTest {
  @Test void queryIsScopedToDeviceSourceAndTargetAndRetainsBothStates() {
    var query = FacilityCloudAlarms.query("meter-12-201", 2);
    assertEquals(2, query.getPageIndex()); assertEquals(200, query.getPageSize());
    var terms = new HashMap<String, Object>();
    var pending = new ArrayDeque<>(query.getTerms());
    while (!pending.isEmpty()) {
      var term = pending.remove(); if (term.getColumn() != null) terms.put(term.getColumn(), term.getValue());
      if (term.getTerms() != null) pending.addAll(term.getTerms());
    }
    assertEquals("device", terms.get("sourceType")); assertEquals("meter-12-201", terms.get("sourceId"));
    assertEquals("device", terms.get("targetType")); assertEquals("meter-12-201", terms.get("targetId"));
    assertFalse(terms.containsKey("state"));
  }

  @Test @SuppressWarnings("unchecked") void missingPagesFailVisiblyInsteadOfReturningPartialSnapshot() {
    var support = mock(CommandSupport.class);
    var source = spy(new FacilityCloudAlarms()); doReturn(Mono.just(support)).when(source).support();
    var alarm = new CloudAlarmRecord(); alarm.setId("alarm");
    var first = new PagerResult<CloudAlarmRecord>(); first.setTotal(201); first.setData(List.of(alarm));
    when(support.execute(any(QueryPagerCommand.class))).thenReturn(Mono.just(first));
    StepVerifier.create(source.records("meter")).expectError().verify();
  }

  @Test void sourceStateAndSeverityAreNotInventedFromUsageValues() {
    var row = new CloudAlarmRecord(); row.setState(Map.of("value", "normal", "text", "正常")); row.setLevel(4);
    assertEquals("normal", row.stateValue()); assertEquals(4, row.getLevel()); assertEquals("warning", row.facilityLevel());
    row.setState("warning"); row.setLevel(1);
    assertEquals("warning", row.stateValue()); assertEquals("warning", row.facilityLevel());
  }

  @Test @SuppressWarnings("unchecked") void absentCommandResponseIsNotAnEmptySuccessfulSnapshot() {
    var support = mock(CommandSupport.class);
    var source = spy(new FacilityCloudAlarms()); doReturn(Mono.just(support)).when(source).support();
    when(support.execute(any(QueryPagerCommand.class))).thenReturn(Mono.empty());
    StepVerifier.create(source.records("meter"))
      .expectError(org.hswebframework.web.exception.BusinessException.class).verify();
  }
}
