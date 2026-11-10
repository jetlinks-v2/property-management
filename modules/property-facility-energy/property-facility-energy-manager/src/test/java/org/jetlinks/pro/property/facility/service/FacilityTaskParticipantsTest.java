package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import org.jetlinks.pro.property.facility.entity.TaskEntity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FacilityTaskParticipantsTest {
  final FacilityDirectory directory = spy(new FacilityDirectory());

  TaskEntity task(String legacyMode) {
    var task = new TaskEntity(); task.setProjectId("project"); task.setState("open");
    task.setMode(legacyMode); task.setExternalOrganizationId("vendor"); return task;
  }

  SimpleAuthentication actor() {
    var user = new SimpleUser(); user.setId("worker");
    var auth = new SimpleAuthentication(); auth.setUser(user); return auth;
  }

  void employee(String id, String... organizations) {
    doReturn(Mono.just(new PropertyUserInfo())).when(directory).employee(id);
    doReturn(Mono.just(new PropertyEmployeeInfo(id, id, null, List.of(organizations))))
      .when(directory).employeeInfo(id);
  }

  @Test void everyLegacyModeUsesOnlyProjectMembersForClaimAndDispatch() {
    employee("worker", "project"); employee("vendor-worker", "vendor"); employee("other", "other-project");
    for (String mode : new String[] { null, "single", "double" }) {
      StepVerifier.create(directory.taskAssignee(task(mode), "worker")).verifyComplete();
      for (String outsider : List.of("vendor-worker", "other")) {
        StepVerifier.create(directory.taskAssignee(task(mode), outsider))
          .expectErrorMatches(e -> e instanceof BusinessException && e.getMessage().contains("project_employee_required")).verify();
      }
    }
    verify(directory, never()).organizations();
  }

  @Test void disabledOrRemovedEmployeeAndMissingIdentityCannotBeAssigned() {
    employee("worker", "project");
    doReturn(Mono.error(new BusinessException("disabled"))).when(directory).employee("worker");
    StepVerifier.create(directory.taskAssignee(task(null), "worker")).expectError(BusinessException.class).verify();
    employee("removed");
    doReturn(Mono.empty()).when(directory).employeeInfo("removed");
    StepVerifier.create(directory.taskAssignee(task(null), "removed")).expectError(BusinessException.class).verify();
  }

  @Test void claimCapabilityUsesLiveSelfMembershipAndRequiresOpenState() {
    for (String organization : List.of("project", "vendor", "other-project")) {
      employee("worker", organization);
      StepVerifier.create(directory.canClaim(task("double")).contextWrite(c -> c.put(Authentication.class, actor())))
        .expectNext(organization.equals("project")).verifyComplete();
    }
    employee("worker", "project");
    var task = task(null); task.setState("claimed");
    StepVerifier.create(directory.canClaim(task)).expectNext(false).verifyComplete();
    doReturn(Mono.empty()).when(directory).employeeInfo("worker");
    StepVerifier.create(directory.canClaim(task(null)).contextWrite(c -> c.put(Authentication.class, actor())))
      .expectNext(false).verifyComplete();
  }

  @Test void candidatesComeOnlyFromAuthorizedProjectDirectoryAndPropagateFailure() {
    doReturn(Flux.just(new PropertyEmployeeInfo("worker", "员工", null, List.of("project"))))
      .when(directory).projectEmployees("project");
    StepVerifier.create(directory.taskAssignees(task("double"))).expectNextMatches(p -> p.id().equals("worker")).verifyComplete();
    doReturn(Flux.error(new AccessDenyException())).when(directory).projectEmployees("project");
    StepVerifier.create(directory.taskAssignees(task(null))).expectError(AccessDenyException.class).verify();
    verify(directory, never()).employees();
  }
}
