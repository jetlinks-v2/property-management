package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.QueryPropertyOrganizationTreeCommand;
import org.jetlinks.pro.property.base.organization.OrganizationType;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.facility.web.FacilityContextController;
import org.jetlinks.pro.property.facility.entity.PlanEntity;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FacilityExternalOrganizationsTest {
  private final FacilityDirectory directory = spy(new FacilityDirectory());
  private final CommandSupport support = mock(CommandSupport.class);

  private PropertyOrganizationInfo org(String id, OrganizationType type, PropertyOrganizationInfo... children) {
    var node = new PropertyOrganizationInfo();
    node.setId(id);
    node.setName(id);
    node.setOrganizationType(type);
    node.setChildren(List.of(children));
    for (var child : children) child.setParentId(id);
    return node;
  }

  private Flux<PropertyOrganizationInfo> options() {
    return new FacilityContextController(directory, null).organizations();
  }

  @Test
  void keepsExternalUnitsAndTheirAncestorsWithoutMutatingTheAuthorizedTree() {
    var vendor = org("vendor", OrganizationType.external, org("internal-child", null));
    var branch = org("branch", null, vendor);
    var root = org("root", null, org("project", OrganizationType.project), branch);
    when(support.execute(any(QueryPropertyOrganizationTreeCommand.class))).thenReturn(Flux.just(root));
    try (var commands = mockStatic(CommandSupportManagerProviders.class)) {
      commands.when(() -> CommandSupportManagerProviders.getCommandSupport(QueryPropertyOrganizationTreeCommand.SERVICE_ID))
        .thenReturn(Mono.just(support));
      StepVerifier.create(options().collectList()).assertNext(rows -> {
        assertEquals(1, rows.size());
        assertEquals("root", rows.get(0).getId());
        var children = rows.get(0).getChildren();
        assertEquals(List.of("branch"), children.stream().map(PropertyOrganizationInfo::getId).toList());
        var result = children.get(0).getChildren().get(0);
        assertEquals("vendor", result.getId());
        assertEquals("branch", result.getParentId());
        assertEquals(OrganizationType.external, result.getOrganizationType());
        assertTrue(result.getChildren().isEmpty());
        assertEquals(2, root.getChildren().size());
        assertEquals(1, vendor.getChildren().size());
      }).verifyComplete();
    }
  }

  @Test
  void noExternalUnitReturnsAnEmptyTree() {
    when(support.execute(any(QueryPropertyOrganizationTreeCommand.class)))
      .thenReturn(Flux.just(org("root", null, org("project", OrganizationType.project))));
    try (var commands = mockStatic(CommandSupportManagerProviders.class)) {
      commands.when(() -> CommandSupportManagerProviders.getCommandSupport(QueryPropertyOrganizationTreeCommand.SERVICE_ID))
        .thenReturn(Mono.just(support));
      StepVerifier.create(options()).verifyComplete();
    }
  }

  @Test
  void anAuthorizedExternalRootDoesNotLoadAnInaccessibleParent() {
    var vendor = org("vendor", OrganizationType.external);
    vendor.setParentId("outside-scope");
    vendor.setChildren(null);
    when(support.execute(any(QueryPropertyOrganizationTreeCommand.class))).thenReturn(Flux.just(vendor));
    try (var commands = mockStatic(CommandSupportManagerProviders.class)) {
      commands.when(() -> CommandSupportManagerProviders.getCommandSupport(QueryPropertyOrganizationTreeCommand.SERVICE_ID))
        .thenReturn(Mono.just(support));
      StepVerifier.create(options()).assertNext(result -> {
        assertEquals("vendor", result.getId());
        assertTrue(result.getChildren().isEmpty());
      }).verifyComplete();
    }
  }

  @Test
  void directoryFailuresArePropagatedInsteadOfReturningEmptyOptions() {
    var failure = new IllegalStateException("directory unavailable");
    when(support.execute(any(QueryPropertyOrganizationTreeCommand.class))).thenReturn(Flux.error(failure));
    try (var commands = mockStatic(CommandSupportManagerProviders.class)) {
      commands.when(() -> CommandSupportManagerProviders.getCommandSupport(QueryPropertyOrganizationTreeCommand.SERVICE_ID))
        .thenReturn(Mono.just(support));
      StepVerifier.create(options()).expectErrorMatches(error -> error == failure).verify();
    }
  }

  @Test
  void validationAcceptsOnlyExplicitlyMarkedExternalOrganizations() {
    doReturn(Flux.just(org("project", OrganizationType.project), org("unmarked", null),
      org("vendor", OrganizationType.external))).when(directory).organizations();
    StepVerifier.create(directory.externalOrganization("vendor")).verifyComplete();
    for (var id : List.of("project", "unmarked", "missing")) {
      StepVerifier.create(directory.externalOrganization(id)).expectError(BusinessException.class).verify();
    }
  }

  @Test
  void doubleWorkOrderRejectsAnOrdinaryOrganizationBeforeSaving() {
    var records = mock(FacilityRecords.class);
    var scope = mock(FacilityScope.class);
    var plans = new FacilityPlans(null, null, null, null, null, scope, records, directory, mock(FacilityDevices.class), null);
    doReturn(Mono.just(org("project", OrganizationType.project))).when(directory).project("project");
    doReturn(Mono.just(new PropertyUserInfo())).when(directory).employee(any());
    doReturn(Flux.just(new org.jetlinks.pro.property.base.user.PropertyEmployeeInfo("reviewer", "项目负责人", null, List.of("project"))))
      .when(directory).projectEmployees("project");
    doReturn(Flux.just(org("project", OrganizationType.project))).when(directory).organizations();
    doReturn(Mono.empty()).when(records).assertProject(any(), any());
    doReturn(Mono.empty()).when(records).read(any(), any(), any(), any());
    when(scope.current()).thenReturn(Mono.empty());
    for (var mode : List.of("double")) {
      var plan = new PlanEntity();
      plan.setCycleId("cycle");
      plan.setGroupIds(List.of("group"));
      plan.setName("plan");
      plan.setProjectId("project");
      plan.setKind("inspection");
      plan.setMode(mode);
      plan.setFollowUserId("follower");
      plan.setReviewerId("reviewer");
      plan.setExternalOrganizationId("project");
      plan.setStartTime(1000L);
      plan.setEndTime(5000L);
      plan.setIntervalValue(1);
      plan.setIntervalUnit("day");
      plan.setDeadlineHours(24);
      StepVerifier.create(plans.save(plan))
        .expectErrorMatches(error -> error instanceof BusinessException &&
          "error.facility.external_organization_required".equals(error.getMessage())).verify();
    }
    verify(records, never()).insert(any(), any(), any());
    verify(records, never()).update(any(), any(), anyLong(), any());
  }
}
