package org.jetlinks.pro.property.facility.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.scope.AssetScope;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.assets.scope.AssetScopeResolverManager;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.QueryPropertyProjectsCommand;
import org.jetlinks.pro.property.base.command.GetCurrentPropertyEmployeeCommand;
import org.jetlinks.pro.property.facility.entity.TaskEntity;
import org.jetlinks.pro.property.base.command.QueryPropertyEmployeePageCommand;
import org.jetlinks.pro.property.base.command.QueryPropertySpacesCommand;
import org.jetlinks.pro.property.base.command.QueryPropertyUsersCommand;
import org.jetlinks.pro.property.base.organization.OrganizationType;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.base.space.PropertySpaceInfo;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 设施只依赖基础模块公共契约，标注及空间绑定每次从同一个服务读取，不保存第二份镜像。 */
@Service
@RequiredArgsConstructor
public class FacilityDirectory {

  public Flux<PropertyOrganizationInfo> projects() {
    return support().flatMapMany(s ->
      s.execute(new QueryPropertyProjectsCommand())
    );
  }

  public Mono<PropertyOrganizationInfo> project(String id) {
    return projects()
      .filter(p -> p.getId().equals(id))
      .next()
      .switchIfEmpty(
        Mono.error(
          new BusinessException("error.facility.project_not_enabled", 400)
        )
      );
  }

  public Flux<PropertyUserInfo> employees() {
    var command = new QueryPropertyUsersCommand();
    command.setEmployeesOnly(true);
    return support().flatMapMany(s -> s.execute(command));
  }

  /** 项目本身即基础组织；业务成员筛选与基础命令的 user 资产权限取交集，不扩大组织范围。 */
  public Flux<PropertyEmployeeInfo> projectEmployees(String projectId) {
    return project(projectId).thenMany(
      organizationEmployeePage(List.of(projectId), 0)
        .expand(page -> (long) (page.getPageIndex() + 1) * 200 < page.getTotal()
          ? organizationEmployeePage(List.of(projectId), page.getPageIndex() + 1) : Mono.empty())
        .concatMapIterable(PagerResult::getData)
    );
  }

  private Mono<PagerResult<PropertyEmployeeInfo>> organizationEmployeePage(List<String> organizationIds, int pageIndex) {
    return Mono.defer(() -> {
      var query = QueryParamEntity.newQuery()
        .where("id", "property-user-org", Map.of("orgId$in", organizationIds))
        .orderByAsc("id").getParam();
      query.setPageIndex(pageIndex);
      query.setPageSize(200);
      var command = new QueryPropertyEmployeePageCommand();
      command.setQuery(query);
      return CommandSupportManagerProviders.getCommandSupport(QueryPropertyEmployeePageCommand.SERVICE_ID)
        .switchIfEmpty(Mono.error(new BusinessException("error.facility.directory_unavailable", 503)))
        .flatMap(s -> s.execute(command))
        .doOnNext(page -> page.setPageIndex(pageIndex));
    });
  }

  public Mono<PropertyUserInfo> user(String id) {
    var command = new QueryPropertyUsersCommand();
    command.setIds(List.of(id));
    return support()
      .flatMapMany(s -> s.execute(command))
      .next();
  }

  public Mono<PropertyUserInfo> employee(String id) {
    return Authentication.currentReactive()
      .flatMap(auth -> {
        if (!id.equals(auth.getUser().getId())) return user(id);
        // An employee executing their own task needs their effective identity,
        // not permission to browse the shared user directory.
        return CommandSupportManagerProviders.getCommandSupport(
            GetCurrentPropertyEmployeeCommand.SERVICE_ID)
          .flatMap(s -> s.execute(
            new GetCurrentPropertyEmployeeCommand()))
          .map(employee -> {
            var info = new PropertyUserInfo();
            info.setId(employee.id());
            info.setName(employee.name());
            info.setTelephone(employee.telephone());
            info.setStatus((byte) 1);
            info.setIdentities(List.of("employee"));
            return info;
          });
      })
      .filter(
        p -> p.isEmployee() && p.getStatus() != null && p.getStatus() == 1
      )
      .switchIfEmpty(
        Mono.error(
          new BusinessException("error.facility.employee_required", 400)
        )
      );
  }

  public Flux<PropertyOrganizationInfo> organizations() {
    return organizationTree().expand(org ->
      Flux.fromIterable(org.getChildren() == null ? List.of() : org.getChildren())
    );
  }

  /** 外协选项只保留明确标注的单位及其授权祖先，不修改用于人员归属校验的完整目录。 */
  public Flux<PropertyOrganizationInfo> externalOrganizationTree() {
    return organizationTree().mapNotNull(this::externalBranch);
  }

  private PropertyOrganizationInfo externalBranch(PropertyOrganizationInfo node) {
    var children = (node.getChildren() == null ? List.<PropertyOrganizationInfo>of() : node.getChildren())
      .stream().map(this::externalBranch).filter(Objects::nonNull).toList();
    if (node.getOrganizationType() != OrganizationType.external && children.isEmpty()) return null;
    var result = new PropertyOrganizationInfo();
    result.setId(node.getId());
    result.setName(node.getName());
    result.setCode(node.getCode());
    result.setParentId(node.getParentId());
    result.setSortIndex(node.getSortIndex());
    result.setOrganizationType(node.getOrganizationType());
    result.setChildren(children);
    return result;
  }

  private Flux<PropertyOrganizationInfo> organizationTree() {
    var command =
      new org.jetlinks.pro.property.base.command.QueryPropertyOrganizationTreeCommand();
    return CommandSupportManagerProviders.getCommandSupport(command.SERVICE_ID)
      .switchIfEmpty(
        Mono.error(
          new BusinessException("error.facility.directory_unavailable", 503)
        )
      )
      .flatMapMany(s -> s.execute(command));
  }

  public Mono<Void> externalOrganization(String id) {
    return organizations()
      .filter(org -> Objects.equals(id, org.getId()) && org.getOrganizationType() == OrganizationType.external)
      .next()
      .switchIfEmpty(
        Mono.error(
          new BusinessException(
            "error.facility.external_organization_required",
            400
          )
        )
      )
      .then();
  }

  private static boolean belongs(PropertyEmployeeInfo employee, List<String> organizations) {
    return employee.organizationIds() != null && employee.organizationIds().stream().anyMatch(organizations::contains);
  }

  /** Self-service claims use effective employee identity; assigning another person uses the authorized directory. */
  Mono<PropertyEmployeeInfo> employeeInfo(String userId) {
    return Authentication.currentReactive().flatMap(auth -> CommandSupportManagerProviders
      .getCommandSupport(QueryPropertyEmployeePageCommand.SERVICE_ID)
      .switchIfEmpty(Mono.error(new BusinessException("error.facility.directory_unavailable", 503)))
      .flatMap(s -> {
      if (userId.equals(auth.getUser().getId())) return s.execute(
        new GetCurrentPropertyEmployeeCommand());
      var command = new QueryPropertyEmployeePageCommand();
      command.setQuery(QueryParamEntity.newQuery().where("id", userId).getParam());
      return s.execute(command).flatMapMany(page -> Flux.fromIterable(page.getData()))
        .filter(person -> userId.equals(person.id())).next();
    }));
  }

  /** Business participant rules supplement task asset permissions; broad read/save access does not confer claim eligibility. */
  public Mono<Void> taskAssignee(TaskEntity task, String userId) {
    return projectEmployee(task.getProjectId(), userId).then();
  }

  /** 维修与作业任务共用项目直接成员约束，资产可写不代表可以参与其他项目的维修。 */
  public Mono<PropertyEmployeeInfo> projectEmployee(String projectId, String userId) {
    if (userId == null || userId.isBlank()) return Mono.error(new BusinessException("error.facility.employee_required", 400));
    return employee(userId).then(employeeInfo(userId)
      .switchIfEmpty(Mono.error(new BusinessException("error.facility.employee_required", 400))))
      .doOnNext(person -> FacilityScope.require(
        projectId != null && belongs(person, List.of(projectId)), "project_employee_required"));
  }

  public Flux<PropertyEmployeeInfo> taskAssignees(TaskEntity task) {
    return projectEmployees(task.getProjectId());
  }

  public Mono<Boolean> canClaim(TaskEntity task) {
    if (!"open".equals(task.getState())) return Mono.just(false);
    return Authentication.currentReactive().flatMap(auth -> employeeInfo(auth.getUser().getId()))
      .map(person -> task.getProjectId() != null && belongs(person, List.of(task.getProjectId())))
      .defaultIfEmpty(false);
  }

  /** Eligibility is applied before pagination and remains intersected with task AssetsHolder permissions. */
  public Mono<QueryParamEntity> claimPool(QueryParamEntity query) {
    return Authentication.currentReactive().flatMap(auth -> employeeInfo(auth.getUser().getId()))
      .map(person -> {
        var ids = person.organizationIds();
        // An unassigned employee has no eligible projects; never drop an empty membership condition.
        return query.clone().toNestQuery().and("state", "open")
          .in("projectId", ids == null || ids.isEmpty() ? List.of("") : ids).getParam();
      })
      .switchIfEmpty(Mono.fromSupplier(() -> query.clone().toNestQuery().and("id", "").getParam()));
  }

  public Flux<PropertySpaceInfo> spaces(String projectId) {
    var command = new QueryPropertySpacesCommand();
    command.setProjectId(projectId);
    return support().flatMapMany(s -> s.execute(command));
  }

  public Mono<PropertySpaceInfo> space(String projectId, String id) {
    return spaces(projectId)
      .filter(p -> p.getId().equals(id))
      .next()
      .switchIfEmpty(
        Mono.error(
          new BusinessException("error.facility.space_project_mismatch", 400)
        )
      );
  }

  public Flux<org.jetlinks.pro.property.base.customer.PropertyCustomerInfo> customers(
    String projectId,
    String spaceId
  ) {
    var command =
      new org.jetlinks.pro.property.base.command.QueryPropertyCustomersCommand();
    command.setProjectId(projectId);
    command.setSpaceId(spaceId);
    return CommandSupportManagerProviders.getCommandSupport(command.SERVICE_ID)
      .switchIfEmpty(
        Mono.error(
          new BusinessException("error.facility.directory_unavailable", 503)
        )
      )
      .flatMapMany(s -> s.execute(command));
  }

  private Mono<org.jetlinks.core.command.CommandSupport> support() {
    return CommandSupportManagerProviders.getCommandSupport(
      QueryPropertyUsersCommand.SERVICE_ID
    ).switchIfEmpty(
      Mono.error(
        new BusinessException("error.facility.directory_unavailable", 503)
      )
    );
  }
}
