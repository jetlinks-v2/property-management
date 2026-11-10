package org.jetlinks.pro.property.facility.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.GetCurrentPropertyEmployeeCommand;
import org.jetlinks.pro.property.base.command.QueryPropertyEmployeePageCommand;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FacilityDirectoryTest {
  private final FacilityDirectory directory = spy(new FacilityDirectory());
  private final CommandSupport support = mock(CommandSupport.class);

  private SimpleAuthentication authentication() {
    var user = new SimpleUser();
    user.setId("contractor");
    var auth = new SimpleAuthentication();
    auth.setUser(user);
    return auth;
  }

  private void prepare(String organization) {
    var external = new PropertyOrganizationInfo();
    external.setId("external");
    external.setOrganizationType(org.jetlinks.pro.property.base.organization.OrganizationType.external);
    external.setChildren(List.of());
    doReturn(Flux.just(external)).when(directory).organizations();
    doReturn(Mono.just(new PropertyUserInfo())).when(directory).employee("contractor");
    when(support.execute(any(GetCurrentPropertyEmployeeCommand.class))).thenReturn(
      Mono.just(new PropertyEmployeeInfo("contractor", "Contractor", null, List.of(organization)))
    );
    when(support.execute(any(QueryPropertyEmployeePageCommand.class))).thenReturn(
      Mono.error(new AssertionError("Self execution must not query the employee directory"))
    );
  }

  @Test
  void selfEmployeeDoesNotNeedDirectoryBrowsePermission() {
    when(support.execute(any(GetCurrentPropertyEmployeeCommand.class))).thenReturn(
      Mono.just(new PropertyEmployeeInfo("contractor", "Contractor", null, List.of("external"))));
    doReturn(Mono.error(new AssertionError("Do not browse directory for self")))
      .when(directory).user("contractor");
    try (var commands = mockStatic(CommandSupportManagerProviders.class)) {
      commands.when(() -> CommandSupportManagerProviders.getCommandSupport(
        GetCurrentPropertyEmployeeCommand.SERVICE_ID)).thenReturn(Mono.just(support));
      StepVerifier.create(directory.employee("contractor")
        .contextWrite(context -> context.put(Authentication.class, authentication())))
        .expectNextMatches(info -> info.isEmployee() && info.getStatus() == 1)
        .verifyComplete();
    }
  }

  @Test
  void selfWithoutEffectiveEmployeeIdentityRemainsDenied() {
    when(support.execute(any(GetCurrentPropertyEmployeeCommand.class))).thenReturn(Mono.empty());
    try (var commands = mockStatic(CommandSupportManagerProviders.class)) {
      commands.when(() -> CommandSupportManagerProviders.getCommandSupport(
        GetCurrentPropertyEmployeeCommand.SERVICE_ID)).thenReturn(Mono.just(support));
      StepVerifier.create(directory.employee("contractor")
        .contextWrite(context -> context.put(Authentication.class, authentication())))
        .expectError(BusinessException.class).verify();
      verify(directory, never()).user("contractor");
    }
  }

  @Test
  void assigningAnotherEmployeeStillUsesAuthorizedDirectory() {
    doReturn(Mono.empty()).when(directory).user("other");
    StepVerifier.create(directory.employee("other")
      .contextWrite(context -> context.put(Authentication.class, authentication())))
      .expectError(BusinessException.class).verify();
    verify(directory).user("other");
    verifyNoInteractions(support);
  }

  private org.jetlinks.pro.property.facility.entity.TaskEntity task() {
    var task = new org.jetlinks.pro.property.facility.entity.TaskEntity(); task.setProjectId("external"); return task;
  }

  @Test
  void projectActorUsesCurrentIdentityWithoutDirectoryPermission() {
    prepare("external");
    try (var commands = mockStatic(CommandSupportManagerProviders.class)) {
      commands.when(() -> CommandSupportManagerProviders.getCommandSupport(
        QueryPropertyEmployeePageCommand.SERVICE_ID)).thenReturn(Mono.just(support));
      StepVerifier.create(directory.taskAssignee(task(), "contractor")
        .contextWrite(context -> context.put(Authentication.class, authentication())))
        .verifyComplete();
      verify(support, never()).execute(any(QueryPropertyEmployeePageCommand.class));
    }
  }

  @Test
  void currentIdentityMustBelongToTheTaskProjectOrganization() {
    prepare("internal");
    try (var commands = mockStatic(CommandSupportManagerProviders.class)) {
      commands.when(() -> CommandSupportManagerProviders.getCommandSupport(
        QueryPropertyEmployeePageCommand.SERVICE_ID)).thenReturn(Mono.just(support));
      StepVerifier.create(directory.taskAssignee(task(), "contractor")
        .contextWrite(context -> context.put(Authentication.class, authentication())))
        .expectError(BusinessException.class).verify();
    }
  }
}
