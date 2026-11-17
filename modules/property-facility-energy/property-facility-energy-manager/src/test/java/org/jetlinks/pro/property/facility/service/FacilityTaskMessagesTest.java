package org.jetlinks.pro.property.facility.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;
class FacilityTaskMessagesTest {
  final NotificationStore store = mock(NotificationStore.class);
  final FacilityDirectory directory = mock(FacilityDirectory.class);
  final FacilityExecution execution = mock(FacilityExecution.class);
  final FacilityNotificationPolicies policies = mock(FacilityNotificationPolicies.class);
  final FacilityTaskMessages service = spy(new FacilityTaskMessages(mock(TaskStore.class), store, execution, directory, mock(FacilityRecords.class), policies, mock(FacilityTaskReminders.class)));
  @org.junit.jupiter.api.BeforeEach void setupPolicies() { when(policies.enabled(any(), anyString())).thenReturn(Mono.just(true)); }
  TaskEntity task() {
    var task = new TaskEntity(); task.setId("parent"); task.setTenantId("tenant"); task.setApplicationId("app");
    task.setCreatorId("creator"); task.setProjectId("project"); task.setName("水泵巡检"); task.setKind("inspection");
    task.setState("open"); task.setReviewerId("reviewer"); task.setDeviceCount(3); task.setSubmittedDeviceCount(0);
    return task;
  }
  PropertyEmployeeInfo person(String id) { return new PropertyEmployeeInfo(id, id, null, List.of("project")); }
  @Test void newTasksNotifyOnlyEligibleProjectCandidatesAndNotEveryDevice() {
    var task=task();
    when(directory.taskAssignees(task)).thenReturn(Flux.just(person("worker"), person("no-permission"), person("worker")));
    when(store.findById(anyString())).thenReturn(Mono.empty());
    doReturn(Mono.just(true)).when(service).eligible(task,"worker","new");
    doReturn(Mono.just(false)).when(service).eligible(task,"no-permission","new");
    doReturn(Mono.empty()).when(service).store(any(), anyString(), anyString());
    StepVerifier.create(service.enqueue(task,"new")).verifyComplete();
    verify(service).store(task,"new","worker"); verify(service,never()).store(task,"new","reviewer");
    verify(service,never()).store(task,"new","no-permission");
  }
  @Test void claimedWarningsGoToCurrentAssigneeAndResponsiblePersonWithNoPoolBroadcast() {
    var task=task(); task.setState("claimed"); task.setAssigneeId("worker");
    when(store.findById(anyString())).thenReturn(Mono.empty());
    doReturn(Mono.just(true)).when(service).eligible(any(), anyString(), anyString());
    doReturn(Mono.empty()).when(service).store(any(), anyString(), anyString());
    StepVerifier.create(service.enqueue(task,"warning")).verifyComplete();
    verify(service).store(task,"warning","worker"); verify(service).store(task,"warning","reviewer");
    verify(directory,never()).taskAssignees(any());
  }
  @Test void persistedMessagesAreNotSentAgainAndWriteFailureIsNotSwallowed() {
    var task=task(); var row=FacilityTaskMessages.message(task,"new","worker");
    when(store.findById(row.getId())).thenReturn(Mono.just(row));
    StepVerifier.create(service.store(task,"new","worker")).verifyComplete();
    verify(store,never()).insert(any(NotificationEntity.class));
    when(store.findById(row.getId())).thenReturn(Mono.empty());
    when(store.insert(any(NotificationEntity.class))).thenReturn(Mono.error(new IllegalStateException("database unavailable")));
    StepVerifier.create(service.store(task,"new","worker")).expectError(IllegalStateException.class).verify();
  }
  @Test void missingApplicationOrAssetPermissionFailsClosed() {
    var task=task();
    when(execution.asUser(eq(task),eq("worker"),any())).thenReturn(Mono.error(new AccessDenyException()));
    StepVerifier.create(service.eligible(task,"worker","new")).expectNext(false).verifyComplete();
  }
  @Test void reminderRecipientsNeedTaskPermissionsButNotRuleManagementPermissions() {
    var tasks=mock(TaskStore.class); var records=mock(FacilityRecords.class); var reminders=mock(FacilityTaskReminders.class);
    var current=task(); current.setState("claimed"); current.setAssigneeId("worker");
    var auth=mock(org.hswebframework.web.authorization.Authentication.class);
    when(auth.hasPermission("facility-task","query")).thenReturn(true);
    when(auth.hasPermission("facility-task","save")).thenReturn(true);
    when(execution.asUser(eq(current),eq("worker"),any())).thenAnswer(call -> {
      java.util.function.Supplier<Mono<Boolean>> work=call.getArgument(2);
      return Mono.defer(work).contextWrite(c -> c.put(org.hswebframework.web.authorization.Authentication.class,auth));
    });
    when(execution.asOwner(eq(current),any())).thenAnswer(call -> {
      java.util.function.Supplier<Mono<String>> work=call.getArgument(1);
      return Mono.defer(work).contextWrite(c -> c.put("rule-owner",true));
    });
    when(records.read(eq(tasks),eq(current.getId()),any(),any())).thenReturn(Mono.just(current));
    when(reminders.category(eq(current),anyLong())).thenReturn(Mono.deferContextual(c -> {
      assertTrue(c.getOrDefault("rule-owner",false)); return Mono.just("warning");
    }));
    when(directory.taskAssignee(current,"worker")).thenReturn(Mono.empty());
    var manager=new FacilityTaskMessages(tasks,store,execution,directory,records,policies,reminders);
    StepVerifier.create(manager.eligible(current,"worker","warning")).expectNext(true).verifyComplete();
    when(reminders.category(eq(current),anyLong())).thenReturn(Mono.just(""));
    StepVerifier.create(manager.eligible(current,"worker","warning")).expectNext(false).verifyComplete();
  }
  @Test void eventsAreStableAndIsolatedByUserApplicationAndCategory() {
    var task=task(); var first=FacilityTaskMessages.message(task,"new","worker");
    assertEquals(first.getId(),FacilityTaskMessages.message(task,"new","worker").getId());
    assertNotEquals(first.getId(),FacilityTaskMessages.message(task,"warning","worker").getId());
    assertNotEquals(first.getId(),FacilityTaskMessages.message(task,"new","other").getId());
    task.setApplicationId("other-app"); assertNotEquals(first.getId(),FacilityTaskMessages.message(task,"new","worker").getId());
    assertEquals(3,first.getDeviceCount()); assertEquals("parent",first.getTaskId());
  }
}
