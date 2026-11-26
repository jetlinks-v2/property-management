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
  @Test void reliefUsesOfficialCommandAndRuleAlarmServiceWithCallerContext() {
    var support = mock(CommandSupport.class); var source = new FacilityCloudAlarms();
    var record = new CloudAlarmRecord(); record.setId("record"); record.setAlarmConfigId("rule");
    record.setTargetType("device"); record.setTargetId("meter"); record.setSourceType("device"); record.setSourceId("meter");
    record.setAlarmTime(1000L);
    var result = new org.jetlinks.pro.command.rule.data.RelieveResult(); result.setRecordId("record"); result.setRelieved(true);
    var auth = mock(org.hswebframework.web.authorization.Authentication.class);
    when(support.execute(any(org.jetlinks.pro.command.rule.RelievedAlarmCommand.class))).thenAnswer(call ->
      Mono.deferContextual(context -> {
        assertSame(auth, context.get(org.hswebframework.web.authorization.Authentication.class));
        var command = (org.jetlinks.pro.command.rule.RelievedAlarmCommand) call.getArgument(0);
        var info = command.getRelieveInfo();
        assertEquals("rule", info.getAlarmConfigId()); assertEquals("device", info.getTargetType());
        assertEquals("meter", info.getTargetId()); assertEquals("meter", info.getSourceId());
        assertEquals(1000L, info.getAlarmTime()); assertEquals("user", info.getAlarmRelieveType());
        assertEquals("checked", info.getDescribe()); assertEquals("checked", info.getRelieveReason());
        assertNull(info.getBindings()); assertNull(info.getSourceCreatorId());
        return Mono.just(result);
      }));
    try (var providers = mockStatic(org.jetlinks.pro.command.CommandSupportManagerProviders.class)) {
      providers.when(() -> org.jetlinks.pro.command.CommandSupportManagerProviders.getCommandSupport(
        org.jetlinks.pro.command.InternalSdkServices.ruleService, org.jetlinks.pro.command.rule.RuleCommandServices.alarm))
        .thenReturn(Mono.just(support));
      StepVerifier.create(source.relieve(record, "checked").contextWrite(c -> c.put(org.hswebframework.web.authorization.Authentication.class, auth)))
        .verifyComplete();
    }
  }

  @Test void reliefRejectsFalseEmptyMismatchedAndFailedCommandResponses() {
    var support = mock(CommandSupport.class); var source = spy(new FacilityCloudAlarms());
    doReturn(Mono.just(support)).when(source).reliefSupport();
    var record = new CloudAlarmRecord(); record.setId("record");
    var result = new org.jetlinks.pro.command.rule.data.RelieveResult(); result.setRecordId("record");
    when(support.execute(any(org.jetlinks.pro.command.rule.RelievedAlarmCommand.class))).thenReturn(Mono.just(result));
    StepVerifier.create(source.relieve(record, "checked")).expectError().verify();
    result.setRelieved(true); result.setRecordId("different");
    StepVerifier.create(source.relieve(record, "checked")).expectError().verify();
    when(support.execute(any(org.jetlinks.pro.command.rule.RelievedAlarmCommand.class))).thenReturn(Mono.empty());
    StepVerifier.create(source.relieve(record, "checked")).expectError().verify();
    when(support.execute(any(org.jetlinks.pro.command.rule.RelievedAlarmCommand.class))).thenReturn(Mono.error(new IllegalStateException("RPC down")));
    StepVerifier.create(source.relieve(record, "checked")).expectError(IllegalStateException.class).verify();
  }

  @Test void levelEndpointRequiresAnApplicationContextBeforeReadingPlatformConfiguration() {
    var cloud = mock(FacilityCloudAlarms.class); var scope = mock(FacilityScope.class);
    var controller = new org.jetlinks.pro.property.facility.web.FacilityAlertController(
      null, null, null, null, null, null, null, null, null, cloud, scope, null);
    when(scope.current()).thenReturn(Mono.error(new IllegalStateException("missing application")));
    StepVerifier.create(controller.levels()).expectError(IllegalStateException.class).verify();
    verifyNoInteractions(cloud);
    when(scope.current()).thenReturn(Mono.just(new FacilityContext("tenant", "app", "user", "User")));
    when(cloud.levels()).thenReturn(Mono.just(List.of()));
    StepVerifier.create(controller.levels()).assertNext(levels -> assertTrue(levels.isEmpty())).verifyComplete();
    verify(cloud).levels();
  }

  @Test void platformLevelNamesAreReadThroughTheExistingReadOnlyCommand() {
    var support = mock(CommandSupport.class);
    var source = spy(new FacilityCloudAlarms()); doReturn(Mono.just(support)).when(source).levelSupport();
    when(support.executeToMono("QueryAlarmLevel", Map.of())).thenReturn(Mono.just(Map.of("levels", List.of(
      Map.of("level", 3, "title", "严重", "i18nMessages", Map.of("zh", "严重", "en", "Severity")),
      Map.of("level", 8, "title", "园区提醒")))));
    StepVerifier.create(source.levels()).assertNext(levels -> {
      assertEquals(2, levels.size()); assertEquals(3, levels.get(0).getLevel());
      assertEquals("严重", levels.get(0).getTitle()); assertEquals("Severity", levels.get(0).getI18nMessages().get("en"));
      assertEquals(8, levels.get(1).getLevel()); assertEquals("园区提醒", levels.get(1).getTitle());
    }).verifyComplete();
    verify(support).executeToMono("QueryAlarmLevel", Map.of());
  }

  @Test void unavailablePlatformLevelsFailVisiblyWithoutInventingNames() {
    var support = mock(CommandSupport.class);
    var source = spy(new FacilityCloudAlarms()); doReturn(Mono.just(support)).when(source).levelSupport();
    when(support.executeToMono("QueryAlarmLevel", Map.of())).thenReturn(Mono.empty());
    StepVerifier.create(source.levels()).expectError(org.hswebframework.web.exception.BusinessException.class).verify();
    when(support.executeToMono("QueryAlarmLevel", Map.of())).thenReturn(Mono.error(new IllegalStateException("unavailable")));
    StepVerifier.create(source.levels()).expectError(IllegalStateException.class).verify();
  }

  @Test void queryIsScopedToDeviceSourceAndTargetAndRetainsBothStates() {
    var query = FacilityCloudAlarms.changesQuery("meter-12-201", 1000L, 2000L, false, 2);
    assertEquals(2, query.getPageIndex()); assertEquals(200, query.getPageSize());
    var terms = new HashMap<String, Object>();
    var pending = new ArrayDeque<>(query.getTerms());
    while (!pending.isEmpty()) {
      var term = pending.remove(); if (term.getColumn() != null) terms.put(term.getColumn(), term.getValue());
      if (term.getTerms() != null) pending.addAll(term.getTerms());
    }
    assertEquals("device", terms.get("sourceType")); assertEquals("meter-12-201", terms.get("sourceId"));
    assertEquals("device", terms.get("targetType")); assertEquals("meter-12-201", terms.get("targetId"));
    assertEquals(2000L, terms.get("alarmTime"));
    assertEquals(2000L, terms.get("lastAlarmTime"));
    assertEquals(2000L, terms.get("handleTime"));
    assertFalse(terms.containsKey("state"));
  }

  @Test @SuppressWarnings("unchecked") void missingPagesFailVisiblyInsteadOfReturningPartialSnapshot() {
    var support = mock(CommandSupport.class);
    var source = spy(new FacilityCloudAlarms()); doReturn(Mono.just(support)).when(source).support();
    var alarm = new CloudAlarmRecord(); alarm.setId("alarm");
    var first = new PagerResult<CloudAlarmRecord>(); first.setTotal(201); first.setData(List.of(alarm));
    when(support.execute(any(QueryPagerCommand.class))).thenReturn(Mono.just(first));
    StepVerifier.create(source.changes("meter", 1, 2, false)).expectError().verify();
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
    StepVerifier.create(source.changes("meter", 1, 2, false))
      .expectError(org.hswebframework.web.exception.BusinessException.class).verify();
  }
}
