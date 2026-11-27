package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;

class FacilityOverviewTest {
  @Test
  void countsTaskStagesWithinAssetsAndRestrictsReviewsToCurrentReviewer() {
    var devices = mock(DeviceStore.class);
    var tasks = mock(TaskStore.class);
    var faults = mock(FaultStore.class);
    var alerts = mock(AlertStore.class);
    var notifications = mock(NotificationStore.class);
    var scope = mock(FacilityScope.class);
    when(scope.current()).thenReturn(Mono.just(new FacilityContext("tenant", "app", "reviewer", "Reviewer")));
    when(scope.query(any(), any(), anyString())).thenAnswer(call -> Mono.just(call.getArgument(0)));
    var deviceManager = mock(FacilityDevices.class);
    when(deviceManager.currentIds(any())).thenReturn(Mono.just(Set.of("current-0", "current-1", "current-2")));
    ReactiveQuery<DeviceEntity> deviceQuery = mock(ReactiveQuery.class, RETURNS_SELF);
    when(devices.createQuery()).thenReturn(deviceQuery);
    when(deviceQuery.fetch()).thenReturn(Flux.range(0, 10).map(index -> {
      var device = new DeviceEntity(); device.setId("current-" + index);
      device.setSourceState(index == 0 ? "online" : "offline"); device.setSyncState("active"); return device;
    }));
    bindEmpty(faults);
    bindEmpty(alerts);
    bindEmpty(notifications);
    @SuppressWarnings("unchecked")
    ReactiveQuery<TaskEntity> query = mock(ReactiveQuery.class, RETURNS_SELF);
    when(tasks.createQuery()).thenReturn(query);
    var source = List.of(
      task("open", "reviewer"), task("claimed", "reviewer"), task("executing", "reviewer"),
      task("rectify", "reviewer"), task("review", "reviewer"), task("external-submitted", "reviewer"),
      task("review", "someone-else"), task("completed", "reviewer"), task("closed", "reviewer"), task("cancelled", "reviewer")
    );
    when(query.fetch()).thenReturn(Flux.fromIterable(source));
    var directory = mock(FacilityDirectory.class);
    var project = new org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo(); project.setId("project");
    when(directory.projects()).thenReturn(Flux.just(project));
    var reports = new FacilityReports(devices, tasks, faults, alerts, notifications, null, null, scope, null, null, directory, deviceManager);
    var result = reports.overview("project").block();
    assertNotNull(result);
    assertEquals(3, result.get("devices")); assertEquals(1L, result.get("online"));
    assertEquals(10, result.get("tasks"));
    assertEquals(1L, result.get("pendingClaim"));
    assertEquals(3L, result.get("inProgress"));
    assertEquals(2L, result.get("pendingReview"));
    assertEquals(7L, result.get("overdue"));
    verify(deviceManager, times(1)).currentIds(argThat(param -> contains(param, "projectId", List.of("project"))));
    // 主任务筛选先于资产查询，项目条件也不能在统计时丢失。
    verify(scope).query(argThat(param -> contains(param, "projectId", "project") && contains(param, "parentTaskId", null)), eq(FacilityAssetType.facilityTask), eq("id"));
  }

  private static TaskEntity task(String state, String reviewer) {
    var task = new TaskEntity();
    task.setKind("inspection"); task.setState(state); task.setReviewerId(reviewer);
    task.setDeadline(System.currentTimeMillis() - 60000); task.setScheduledTime(System.currentTimeMillis());
    return task;
  }

  private static boolean contains(QueryParamEntity param, String column, Object value) {
    return contains(param.getTerms(), column, value);
  }

  private static boolean contains(List<org.hswebframework.ezorm.core.param.Term> terms, String column, Object value) {
    return terms.stream().anyMatch(term ->
      (column.equals(term.getColumn()) && (value == null ? "isnull".equals(term.getTermType()) : Objects.equals(value, term.getValue()))) ||
        (term.getTerms() != null && contains(term.getTerms(), column, value))
    );
  }

  @SuppressWarnings("unchecked")
  private static <T extends FacilityEntity> void bindEmpty(org.hswebframework.web.crud.service.GenericReactiveCrudService<T, String> store) {
    ReactiveQuery<T> query = mock(ReactiveQuery.class, RETURNS_SELF);
    when(store.createQuery()).thenReturn(query);
    when(query.fetch()).thenReturn(Flux.empty());
  }
}
