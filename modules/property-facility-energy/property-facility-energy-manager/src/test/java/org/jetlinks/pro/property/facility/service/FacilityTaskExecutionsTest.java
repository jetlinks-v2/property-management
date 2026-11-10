package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.hswebframework.ezorm.rdb.mapping.ReactiveUpdate;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.TaskEntity;
import org.junit.jupiter.api.*;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;

class FacilityTaskExecutionsTest {
  final TaskStore store = mock(TaskStore.class);
  final FacilityRecords records = mock(FacilityRecords.class);
  final FacilityTaskExecutions executions = spy(new FacilityTaskExecutions(store, records, mock(FacilityScope.class)));
  final TaskEntity parent = row("parent", null, "claimed"), a = row("a", "parent", "review"), b = row("b", "parent", "claimed");
  static TaskEntity row(String id, String parent, String state) {
    var row = new TaskEntity(); row.setId(id); row.setParentTaskId(parent); row.setState(state);
    row.setProjectId("project"); row.setTenantId("tenant"); row.setApplicationId("app"); row.setAssigneeId("worker"); return row;
  }
  @BeforeEach void setup() {
    parent.setDeviceCount(2); parent.setSubmittedDeviceCount(0);
    when(records.read(store, "parent", FacilityAssetType.facilityTask, CrudAssetPermission.save)).thenReturn(Mono.just(parent));
    when(records.read(store, "parent", FacilityAssetType.facilityTask, CrudAssetPermission.read)).thenReturn(Mono.just(parent));
    doAnswer(call -> Flux.just(a, b)).when(executions).children(parent);
    when(records.update(eq(store), any(TaskEntity.class), anyLong())).thenAnswer(call -> {
      TaskEntity task = call.getArgument(1); task.setVersion(task.getVersion() + 1); return Mono.just(task);
    });
    when(records.log(any(), anyString(), anyString(), any(), any(), any(), anyList())).thenReturn(Mono.empty());
  }
  @Test void oneDeviceDoesNotSendTaskToReviewButLastDeviceDoes() {
    StepVerifier.create(executions.after(a, "submit")).expectNext(a).verifyComplete();
    assertEquals("executing", parent.getState()); assertEquals(1, parent.getSubmittedDeviceCount());
    b.setState("review");
    StepVerifier.create(executions.after(b, "submit")).expectNext(b).verifyComplete();
    assertEquals("review", parent.getState()); assertEquals(2, parent.getSubmittedDeviceCount());
    verify(records, times(2)).update(eq(store), same(parent), anyLong());
  }
  @Test void partialVisibilityCannotProduceFalseCompletion() {
    doReturn(Flux.just(a)).when(executions).children(parent);
    StepVerifier.create(executions.after(a, "submit")).expectError(BusinessException.class).verify();
    verify(records, never()).update(eq(store), any(), anyLong());
  }
  @Test void deviceCannotBeClaimedTransferredClosedOrReviewedSeparately() {
    for (var action : List.of("claim", "transfer", "approve", "reject", "close", "cancel"))
      assertThrows(BusinessException.class, () -> executions.before(a, action));
    assertThrows(BusinessException.class, () -> executions.before(parent, "submit"));
    StepVerifier.create(executions.before(b, "submit")).verifyComplete();
    parent.setState("closed"); StepVerifier.create(executions.before(b, "submit")).expectError(BusinessException.class).verify();
  }
  @Test void differentAssigneeOrProjectCannotWriteADeviceAndReplayCanReachReviewedParent() {
    b.setAssigneeId("another"); StepVerifier.create(executions.before(b, "submit")).expectError(BusinessException.class).verify();
    b.setAssigneeId("worker"); b.setProjectId("other");
    StepVerifier.create(executions.before(b, "submit")).expectError(BusinessException.class).verify();
    b.setProjectId("project"); parent.setState("completed");
    StepVerifier.create(executions.before(b, "submit")).verifyComplete();
  }
  @Test void groupAndDeviceScansUseTaskMembershipSnapshots() {
    a.setDeviceId("device-a"); a.setGroupIds(List.of("group-a")); b.setDeviceId("device-b"); b.setGroupIds(List.of("group-b"));
    StepVerifier.create(executions.devices("parent", "group-a", null)).assertNext(rows -> assertEquals(List.of(a), rows)).verifyComplete();
    StepVerifier.create(executions.devices("parent", null, "device-b")).assertNext(rows -> assertEquals(List.of(b), rows)).verifyComplete();
    StepVerifier.create(executions.devices("parent", "other-group", null)).assertNext(rows -> assertTrue(rows.isEmpty())).verifyComplete();
  }
  @Test @SuppressWarnings("unchecked") void lifecycleCascadesBatchByVersionAndPreservesOptimisticGuards() {
    ReactiveUpdate<TaskEntity> update = mock(ReactiveUpdate.class, RETURNS_SELF);
    when(store.createUpdate()).thenReturn(update); when(update.execute()).thenReturn(Mono.just(2));
    parent.setState("rectify");
    StepVerifier.create(executions.after(parent, "reject")).expectNext(parent).verifyComplete();
    verify(update).set("state", "rectify"); verify(update).set("version", 1L);
    verify(update).and("version", 0L); verify(update).and("parentTaskId", "parent"); verify(update).setNull("submissionId");
    when(update.execute()).thenReturn(Mono.just(1));
    StepVerifier.create(executions.after(parent, "close")).expectError(BusinessException.class).verify();
  }
  @Test void legacySingleDeviceOrdersKeepTheirLifecycle() {
    var legacy = row("legacy", null, "claimed");
    assertFalse(FacilityTaskExecutions.aggregate(legacy));
    StepVerifier.create(executions.before(legacy, "submit")).verifyComplete();
    StepVerifier.create(executions.after(legacy, "submit")).expectNext(legacy).verifyComplete();
    verifyNoInteractions(store);
  }
}
