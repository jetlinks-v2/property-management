package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.QueryPropertyEmployeePageCommand;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FacilityProjectEmployeesTest {
  private final FacilityDirectory directory = spy(new FacilityDirectory());
  private final CommandSupport support = mock(CommandSupport.class);

  @Test
  void queriesProjectDirectMembersBeforePagingAndReadsEveryPage() {
    var project = new PropertyOrganizationInfo();
    project.setId("project-a");
    doReturn(Mono.just(project)).when(directory).project("project-a");
    when(support.execute(any(QueryPropertyEmployeePageCommand.class))).thenAnswer(call -> {
      var query = call.<QueryPropertyEmployeePageCommand>getArgument(0).getQuery();
      assertEquals(200, query.getPageSize());
      assertEquals("id", query.getTerms().get(0).getColumn());
      assertEquals("property-user-org", query.getTerms().get(0).getTermType());
      assertEquals(Map.of("orgId$in", List.of("project-a")), query.getTerms().get(0).getValue());
      int start = query.getPageIndex() * 200;
      var rows = java.util.stream.IntStream.range(start, Math.min(start + 200, 201))
        .mapToObj(i -> new PropertyEmployeeInfo("user-" + i, "Employee " + i, null, List.of("project-a")))
        .toList();
      return Mono.just(new PagerResult<>(201, rows));
    });
    try (var commands = mockStatic(CommandSupportManagerProviders.class)) {
      commands.when(() -> CommandSupportManagerProviders.getCommandSupport(
        QueryPropertyEmployeePageCommand.SERVICE_ID)).thenReturn(Mono.just(support));
      StepVerifier.create(directory.projectEmployees("project-a").collectList())
        .assertNext(rows -> {
          assertEquals(201, rows.size());
          assertEquals("user-200", rows.get(200).id());
        }).verifyComplete();
      verify(support, times(2)).execute(any(QueryPropertyEmployeePageCommand.class));
    }
  }

  @Test
  void unauthorizedProjectNeverQueriesEmployees() {
    doReturn(Mono.error(new AccessDenyException())).when(directory).project("other-project");
    StepVerifier.create(directory.projectEmployees("other-project"))
      .expectError(AccessDenyException.class).verify();
    verifyNoInteractions(support);
  }

  @Test
  void employeePermissionFailureDoesNotFallBackToFullDirectory() {
    doReturn(Mono.just(new PropertyOrganizationInfo())).when(directory).project("project-a");
    when(support.execute(any(QueryPropertyEmployeePageCommand.class)))
      .thenReturn(Mono.error(new AccessDenyException()));
    try (var commands = mockStatic(CommandSupportManagerProviders.class)) {
      commands.when(() -> CommandSupportManagerProviders.getCommandSupport(
        QueryPropertyEmployeePageCommand.SERVICE_ID)).thenReturn(Mono.just(support));
      StepVerifier.create(directory.projectEmployees("project-a"))
        .expectError(AccessDenyException.class).verify();
      verify(directory, never()).employees();
    }
  }
}
