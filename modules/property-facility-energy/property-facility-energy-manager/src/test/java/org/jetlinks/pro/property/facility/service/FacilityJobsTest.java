package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.jetlinks.pro.property.facility.entity.*;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;

/** 后台流式扫描跨应用配置；单条失败不能截断后续采集及规则评估。 */
class FacilityJobsTest {

  @Test
  void allMappingsAndRulesBeyondFirstThousandAreProcessed() {
    var jobs = mock(JobStore.class, RETURNS_DEEP_STUBS);
    var plans = mock(PlanStore.class, RETURNS_DEEP_STUBS);
    var mappings = mock(MappingStore.class, RETURNS_DEEP_STUBS);
    var rules = mock(RuleStore.class, RETURNS_DEEP_STUBS);
    var execution = mock(FacilityExecution.class);
    var telemetry = mock(FacilityTelemetry.class);
    var alerts = mock(FacilityAlerts.class);
    var notifications = mock(FacilityNotifications.class);
    ReactiveQuery<JobEntity> jobQuery = mock(ReactiveQuery.class, RETURNS_SELF);
    ReactiveQuery<PlanEntity> planQuery = mock(ReactiveQuery.class, RETURNS_SELF);
    ReactiveQuery<MappingEntity> mappingQuery = mock(ReactiveQuery.class, RETURNS_SELF);
    ReactiveQuery<RuleEntity> ruleQuery = mock(ReactiveQuery.class, RETURNS_SELF);
    when(jobs.createQuery()).thenReturn(jobQuery);
    when(plans.createQuery()).thenReturn(planQuery);
    when(mappings.createQuery()).thenReturn(mappingQuery);
    when(rules.createQuery()).thenReturn(ruleQuery);
    when(jobQuery.fetch()).thenReturn(Flux.empty());
    when(planQuery.fetch()).thenReturn(Flux.empty());
    var mappingRows = IntStream.range(0, 1004).mapToObj(i -> {
      var row = new MappingEntity();
      row.setId("mapping-" + i);
      row.setApplicationId(i < 4 ? "existing-app" : "capacity-app");
      row.setEnabled(true);
      row.setDeleted(false);
      return row;
    }).toList();
    var ruleRows = IntStream.range(0, 1004).mapToObj(i -> {
      var row = new RuleEntity();
      row.setId("rule-" + i);
      row.setEnabled(true);
      row.setDeleted(false);
      return row;
    }).toList();
    when(mappingQuery.fetch())
      .thenReturn(Flux.fromIterable(mappingRows));
    when(ruleQuery.fetch())
      .thenReturn(Flux.fromIterable(ruleRows));
    when(execution.asOwner(any(), any())).thenAnswer(call -> {
      Supplier<Mono<?>> work = call.getArgument(1);
      return work.get();
    });
    when(telemetry.collect(any())).thenReturn(Mono.empty());
    when(telemetry.collect(mappingRows.get(1))).thenReturn(Mono.error(new IllegalStateException("test failure")));
    when(alerts.evaluate(any())).thenReturn(Mono.empty());
    when(notifications.poll()).thenReturn(Mono.empty());
    var polling = new FacilityJobs(jobs, plans, mappings, rules, execution,
      mock(FacilitySync.class), mock(FacilityPlans.class), telemetry, alerts, notifications, mock(FacilityTaskMessages.class, invocation -> Mono.empty()), mock(FacilityMeterMonths.class));

    StepVerifier.create(polling.poll()).verifyComplete();
    verify(telemetry, times(1004)).collect(any());
    verify(alerts, times(1004)).evaluate(any());
    verify(execution, times(2008)).asOwner(any(), any());
    verify(telemetry).reconcile(argThat(ids -> ids.size() == 1003
      && ids.contains("mapping-1003") && !ids.contains("mapping-1")));
    assertEquals(1, polling.getFailureCount());
    assertEquals(1003, polling.getCollectionCount());
    assertFalse(polling.isPolling());
  }
  @Test
  void slowTelemetryDoesNotBlockLaterPlanPolls() {
    var jobs = mock(JobStore.class, RETURNS_DEEP_STUBS);
    var plans = mock(PlanStore.class, RETURNS_DEEP_STUBS);
    var mappings = mock(MappingStore.class, RETURNS_DEEP_STUBS);
    var rules = mock(RuleStore.class, RETURNS_DEEP_STUBS);
    var execution = mock(FacilityExecution.class);
    var manager = mock(FacilityPlans.class);
    var telemetry = mock(FacilityTelemetry.class);
    var notifications = mock(FacilityNotifications.class);
    ReactiveQuery<JobEntity> jobQuery = mock(ReactiveQuery.class, RETURNS_SELF);
    ReactiveQuery<PlanEntity> planQuery = mock(ReactiveQuery.class, RETURNS_SELF);
    ReactiveQuery<MappingEntity> mappingQuery = mock(ReactiveQuery.class, RETURNS_SELF);
    ReactiveQuery<RuleEntity> ruleQuery = mock(ReactiveQuery.class, RETURNS_SELF);
    when(rules.createQuery()).thenReturn(ruleQuery);
    when(ruleQuery.fetch()).thenReturn(Flux.empty());
    when(jobs.createQuery()).thenReturn(jobQuery);
    when(plans.createQuery()).thenReturn(planQuery);
    when(mappings.createQuery()).thenReturn(mappingQuery);
    when(jobQuery.fetch()).thenReturn(Flux.empty());
    var plan = new PlanEntity(); plan.setId("due-plan");
    when(planQuery.fetch()).thenReturn(Flux.just(plan));
    var mapping = new MappingEntity(); mapping.setId("slow-mapping");
    when(mappingQuery.fetch()).thenReturn(Flux.just(mapping));
    when(execution.asOwner(any(), any())).thenAnswer(call -> {
      Supplier<Mono<?>> work = call.getArgument(1); return work.get();
    });
    when(manager.generate("due-plan")).thenReturn(Mono.just(List.of()));
    when(telemetry.collect(any())).thenReturn(Mono.never());
    when(notifications.poll()).thenReturn(Mono.empty());
    var polling = new FacilityJobs(jobs, plans, mappings, rules, execution,
      mock(FacilitySync.class), manager, telemetry, mock(FacilityAlerts.class), notifications, mock(FacilityTaskMessages.class, invocation -> Mono.empty()), mock(FacilityMeterMonths.class));
    var running = polling.poll().subscribe();
    try {
      verify(telemetry).collect(any());
      assertTrue(polling.isPolling());
      StepVerifier.create(polling.pollPlans()).verifyComplete();
      verify(manager, times(2)).generate("due-plan");
    } finally { running.dispose(); }
    assertFalse(polling.isPolling());
  }

}
