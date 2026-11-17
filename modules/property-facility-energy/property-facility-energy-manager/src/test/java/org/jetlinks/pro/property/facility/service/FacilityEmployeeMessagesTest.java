package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.function.Supplier;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.jetlinks.pro.property.facility.entity.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class FacilityEmployeeMessagesTest {
  final NotificationStore store = mock(NotificationStore.class);
  final FacilityDirectory directory = mock(FacilityDirectory.class);
  final FacilityExecution execution = mock(FacilityExecution.class);
  final FacilityRecords records = mock(FacilityRecords.class);
  final FaultStore faults = mock(FaultStore.class);
  final FacilityNotificationPolicies policies = mock(FacilityNotificationPolicies.class);
  final FacilityEmployeeMessages service = new FacilityEmployeeMessages(store, directory, execution, records, faults, mock(TaskStore.class), policies);
  final FaultEntity fault = new FaultEntity();
  @BeforeEach void setup() {
    when(policies.enabled(any(), anyString())).thenReturn(Mono.just(true));
    fault.setId("fault"); fault.setTenantId("tenant"); fault.setApplicationId("app"); fault.setProjectId("project");
    fault.setCreatorId("creator"); fault.setAssigneeId("worker"); fault.setReviewerId("reviewer"); fault.setState("review"); fault.setName("供水泵漏水");
    when(store.findById(anyString())).thenReturn(Mono.empty());
    when(store.insert(any(NotificationEntity.class))).thenReturn(Mono.just(1));
  }
  @Test void revokedRecipientProducesVisibleFailureWithoutRollingBackBusinessAction() {
    when(execution.asUser(eq(fault), eq("reviewer"), any())).thenReturn(Mono.error(new AccessDenyException()));
    StepVerifier.create(service.fault(fault, "resolve")).verifyComplete();
    var captured = ArgumentCaptor.forClass(NotificationEntity.class); verify(store).insert(captured.capture());
    assertEquals("failed", captured.getValue().getState()); assertEquals("recipient_permission_denied", captured.getValue().getFailureReason());
    assertEquals("reviewer", captured.getValue().getUserId()); assertEquals("fault", captured.getValue().getFaultId());
  }
  @Test void missingRecipientIsVisibleAndDatabaseFailureIsNotSwallowed() {
    fault.setReviewerId(null);
    StepVerifier.create(service.fault(fault, "resolve")).verifyComplete();
    var captured = ArgumentCaptor.forClass(NotificationEntity.class); verify(store).insert(captured.capture());
    assertEquals("employee_not_configured", captured.getValue().getFailureReason()); verifyNoInteractions(execution);
    when(store.insert(any(NotificationEntity.class))).thenReturn(Mono.error(new IllegalStateException("database unavailable")));
    StepVerifier.create(service.fault(fault, "resolve")).expectError(IllegalStateException.class).verify();
  }
  @Test @SuppressWarnings("unchecked") void missingBusinessPermissionCannotFallThroughAnEmptyPublisher() {
    var auth = mock(Authentication.class); when(auth.hasPermission(anyString(), anyString())).thenReturn(false);
    when(execution.asUser(eq(fault), eq("worker"), any())).thenAnswer(call -> {
      Supplier<Mono<Boolean>> work = call.getArgument(2); return Mono.defer(work).contextWrite(c -> c.put(Authentication.class, auth));
    });
    StepVerifier.create(service.fault(fault, "reject")).verifyComplete();
    verifyNoInteractions(directory, records);
    var captured = ArgumentCaptor.forClass(NotificationEntity.class); verify(store).insert(captured.capture());
    assertEquals("failed", captured.getValue().getState());
  }
  @Test @SuppressWarnings("unchecked") void acceptanceRequiresReviewPermissionAndCurrentProjectMembership() {
    var auth = mock(Authentication.class); when(auth.hasPermission(anyString(), anyString())).thenReturn(true);
    when(execution.asUser(eq(fault), eq("reviewer"), any())).thenAnswer(call -> {
      Supplier<Mono<Boolean>> work = call.getArgument(2); return Mono.defer(work).contextWrite(c -> c.put(Authentication.class, auth));
    });
    when(directory.projectEmployee("project", "reviewer")).thenReturn(Mono.just(new PropertyEmployeeInfo("reviewer", "杜甫", null, List.of("project"))));
    when(records.read(eq(faults), eq("fault"), any(), any())).thenReturn(Mono.just(fault));
    StepVerifier.create(service.fault(fault, "resolve")).verifyComplete();
    verify(auth).hasPermission("facility-fault", "review"); verify(directory).projectEmployee("project", "reviewer");
    var captured = ArgumentCaptor.forClass(NotificationEntity.class); verify(store).insert(captured.capture());
    assertEquals("generated", captured.getValue().getState()); assertEquals("维修待验收", captured.getValue().getTitle());
  }
  @Test void closingAnAssignedFaultNotifiesTheWorkerInsteadOfOnlyTheReviewer() {
    fault.setState("cancelled");
    when(execution.asUser(eq(fault), anyString(), any())).thenReturn(Mono.just(true));
    StepVerifier.create(service.fault(fault, "close")).verifyComplete();
    var captured = ArgumentCaptor.forClass(NotificationEntity.class); verify(store).insert(captured.capture());
    assertEquals("worker", captured.getValue().getUserId());
    assertEquals("fault:closed", captured.getValue().getMessageType());
    assertEquals("generated", captured.getValue().getState());
  }
  @Test void closingAnUnassignedFaultNotifiesItsReviewer() {
    fault.setAssigneeId(null); fault.setState("cancelled");
    when(execution.asUser(eq(fault), eq("reviewer"), any())).thenReturn(Mono.just(true));
    StepVerifier.create(service.fault(fault, "close")).verifyComplete();
    var captured = ArgumentCaptor.forClass(NotificationEntity.class); verify(store).insert(captured.capture());
    assertEquals("reviewer", captured.getValue().getUserId());
  }

  DeviceEntity device() {
    var device = new DeviceEntity(); device.setId("water-12-201"); device.setName("12-201水表");
    device.setTenantId("tenant"); device.setApplicationId("app"); device.setProjectId("project"); return device;
  }
  AlertEntity alert() {
    var alert = new AlertEntity(); alert.setId("saas-alarm"); alert.setTenantId("tenant"); alert.setApplicationId("app");
    alert.setProjectId("project"); alert.setName("持续用水提醒"); alert.setNotificationSequence(1); return alert;
  }
  @Test void deviceOwnerOnlyIsNotifiedWithoutEnumeratingTheProject() {
    var device = device(); device.setResponsibleUserId("worker");
    when(execution.asUser(any(), eq("worker"), any())).thenReturn(Mono.just(true));
    StepVerifier.create(service.platformAlert(alert(), device)).verifyComplete();
    var captured = ArgumentCaptor.forClass(NotificationEntity.class); verify(store).insert(captured.capture());
    assertEquals("worker", captured.getValue().getUserId()); verify(directory, never()).projectEmployees(anyString());
  }
  @Test void noOwnerBroadcastsOncePerCurrentProjectMemberAndKeepsDeniedAudit() {
    when(directory.projectEmployees("project")).thenReturn(Flux.just(
      new PropertyEmployeeInfo("worker", "李秀辉", null, List.of("project")),
      new PropertyEmployeeInfo("manager", "项目主管", null, List.of("project")),
      new PropertyEmployeeInfo("worker", "李秀辉", null, List.of("project")),
      new PropertyEmployeeInfo("revoked", "已撤权成员", null, List.of("project"))));
    when(execution.asUser(any(), anyString(), any())).thenAnswer(call -> "revoked".equals(call.getArgument(1))
      ? Mono.error(new AccessDenyException()) : Mono.just(true));
    StepVerifier.create(service.platformAlert(alert(), device())).verifyComplete();
    var captured = ArgumentCaptor.forClass(NotificationEntity.class); verify(store, times(3)).insert(captured.capture());
    assertEquals(2, captured.getAllValues().stream().filter(row -> "generated".equals(row.getState())).count());
    assertEquals(1, captured.getAllValues().stream().filter(row -> "failed".equals(row.getState())).count());
    assertEquals(3, captured.getAllValues().stream().map(NotificationEntity::getId).distinct().count());
  }
  @Test void invalidOwnerFallsBackToProjectButDatabaseFailureDoesNotTriggerBroadcast() {
    var device = device(); device.setResponsibleUserId("revoked");
    when(execution.asUser(any(), eq("revoked"), any())).thenReturn(Mono.error(new AccessDenyException()));
    when(directory.projectEmployees("project")).thenReturn(Flux.just(new PropertyEmployeeInfo("manager", "项目主管", null, List.of("project"))));
    when(execution.asUser(any(), eq("manager"), any())).thenReturn(Mono.just(true));
    StepVerifier.create(service.platformAlert(alert(), device)).verifyComplete();
    verify(store, times(2)).insert(any(NotificationEntity.class));
    reset(directory); when(store.insert(any(NotificationEntity.class))).thenReturn(Mono.error(new IllegalStateException("database unavailable")));
    StepVerifier.create(service.platformAlert(alert(), device)).expectError(IllegalStateException.class).verify();
    verifyNoInteractions(directory);
  }
  @Test void disabledPolicyDoesNotCreateOrSendAlertMessages() {
    when(policies.enabled(any(), eq("alert"))).thenReturn(Mono.just(false));
    StepVerifier.create(service.platformAlert(alert(), device())).verifyComplete();
    verifyNoInteractions(directory, execution); verify(store, never()).insert(any(NotificationEntity.class));
  }
  @Test void emptyProjectRecipientDirectoryProducesVisibleFailure() {
    var alert = new AlertEntity(); alert.setId("alarm"); alert.setProjectId("project");
    alert.setTenantId("tenant"); alert.setApplicationId("app"); alert.setCreatorId("creator");
    var device = new DeviceEntity(); device.setId("meter"); device.setProjectId("project");
    when(directory.projectEmployees("project")).thenReturn(Flux.empty());
    StepVerifier.create(service.platformAlert(alert, device)).verifyComplete();
    var captured = ArgumentCaptor.forClass(NotificationEntity.class); verify(store).insert(captured.capture());
    assertEquals("employee_not_configured", captured.getValue().getFailureReason());
    assertEquals("alarm", captured.getValue().getAlertId()); verifyNoInteractions(execution);
  }

}
