package org.jetlinks.pro.property.facility.service;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.Authentication;
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
import org.jetlinks.pro.property.base.command.QueryPropertySpacesCommand;
import org.jetlinks.pro.property.base.command.QueryPropertyUsersCommand;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.base.space.PropertySpaceInfo;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;
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
            org.jetlinks.pro.property.base.command.GetCurrentPropertyEmployeeCommand.SERVICE_ID)
          .flatMap(s -> s.execute(
            new org.jetlinks.pro.property.base.command.GetCurrentPropertyEmployeeCommand()))
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
    var command =
      new org.jetlinks.pro.property.base.command.QueryPropertyOrganizationTreeCommand();
    return CommandSupportManagerProviders.getCommandSupport(command.SERVICE_ID)
      .switchIfEmpty(
        Mono.error(
          new BusinessException("error.facility.directory_unavailable", 503)
        )
      )
      .flatMapMany(s -> s.execute(command))
      .expand(org ->
        Flux.fromIterable(
          org.getChildren() == null ? List.of() : org.getChildren()
        )
      );
  }

  public Mono<Void> externalOrganization(String id) {
    return organizations()
      .filter(org -> Objects.equals(id, org.getId()))
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

  /** Recheck live employee membership when assigning and executing an external work order. */
  public Mono<Void> employeeRole(
    String userId,
    String externalOrganizationId,
    boolean external
  ) {
    var command =
      new org.jetlinks.pro.property.base.command.QueryPropertyEmployeePageCommand();
    command.setQuery(
      org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
        .where("id", userId)
        .getParam()
    );
    var branch = organizations()
      .filter(org -> Objects.equals(externalOrganizationId, org.getId()))
      .next()
      .switchIfEmpty(
        Mono.error(
          new BusinessException(
            "error.facility.external_organization_required",
            400
          )
        )
      )
      .flatMapMany(org ->
        Flux.just(org).expand(child ->
          Flux.fromIterable(
            child.getChildren() == null ? List.of() : child.getChildren()
          )
        )
      )
      .map(PropertyOrganizationInfo::getId)
      .collectList();
    return employee(userId)
      .then(
        Mono.zip(
          branch,
          Authentication.currentReactive().flatMap(auth ->
            CommandSupportManagerProviders.getCommandSupport(command.SERVICE_ID)
            .switchIfEmpty(
              Mono.error(
                new BusinessException(
                  "error.facility.directory_unavailable",
                  503
                )
              )
            )
            .flatMap(s -> {
              // Executing one's own order needs current employee identity, not user-directory permission.
              // Assignment of another employee still uses the authorized directory query.
              if (userId.equals(auth.getUser().getId())) return s.execute(
                new org.jetlinks.pro.property.base.command.GetCurrentPropertyEmployeeCommand()
              );
              return s.execute(command)
                .flatMapMany(page -> Flux.fromIterable(page.getData()))
                .filter(e -> userId.equals(e.id()))
                .next();
            })
          )
            .switchIfEmpty(
              Mono.error(
                new BusinessException("error.facility.employee_required", 400)
              )
            )
        )
      )
      .doOnNext(data -> {
        boolean member =
          data.getT2().organizationIds() != null &&
          data
            .getT2()
            .organizationIds()
            .stream()
            .anyMatch(data.getT1()::contains);
        FacilityScope.require(member == external, "external_role_mismatch");
      })
      .then();
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
