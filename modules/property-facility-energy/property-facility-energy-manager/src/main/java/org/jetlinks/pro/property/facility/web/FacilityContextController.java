package org.jetlinks.pro.property.facility.web;

import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.*;
import org.jetlinks.pro.property.facility.service.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.*;

/** 共享档案公开命令的只读投影；系统管理菜单直接复用公共/property页面和权限。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/facility-energy/context")
@Resource(id = "facility-device", name = "设施设备")
public class FacilityContextController {

  private final FacilityDirectory directory;
  private final FacilityScope scope;

  @GetMapping("/me")
  @QueryAction
  public Mono<FacilityContext> me() {
    return scope.current();
  }

  @GetMapping("/projects")
  @QueryAction
  public Flux<org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo> projects() {
    return directory.projects();
  }

  @GetMapping("/organizations")
  @QueryAction
  public Flux<org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo> organizations() {
    return directory.externalOrganizationTree();
  }

  @GetMapping("/employees")
  @QueryAction
  public Flux<org.jetlinks.pro.property.base.user.PropertyUserInfo> employees() {
    return directory.employees();
  }

  @GetMapping("/employees/{projectId}")
  @QueryAction
  public Flux<org.jetlinks.pro.property.base.user.PropertyEmployeeInfo> projectEmployees(
    @PathVariable String projectId
  ) {
    return directory.projectEmployees(projectId);
  }

  @GetMapping("/spaces/{projectId}")
  @QueryAction
  public Flux<org.jetlinks.pro.property.base.space.PropertySpaceInfo> spaces(
    @PathVariable String projectId
  ) {
    return directory.spaces(projectId);
  }

  @GetMapping("/customers/{projectId}")
  @QueryAction
  public Flux<org.jetlinks.pro.property.base.customer.PropertyCustomerInfo> customers(
    @PathVariable String projectId,
    @RequestParam String spaceId
  ) {
    return directory.customers(projectId, spaceId);
  }
}
