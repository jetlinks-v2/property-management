package org.jetlinks.pro.property.web;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.crud.CorrelatesAssetsHolderQueryController;
import org.hswebframework.web.crud.service.ReactiveCrudService;
import org.jetlinks.pro.property.entity.PropertyOrganizationEntity;
import org.jetlinks.pro.property.service.PropertyOrganizationService;
import java.util.function.Function;
/** 共享目录查询沿用 Cloud 原始organization资产的 ID 与数据权限。 */
@RestController("repairPropertyOrganizationController") @AllArgsConstructor
@RequestMapping("/property-data/organizations")
@Resource(id="property-data",name="物业共享基础数据")
public class PropertyOrganizationController implements CorrelatesAssetsHolderQueryController<PropertyOrganizationEntity,String> {
 private final org.jetlinks.pro.property.service.TenantAssetScope tenantScope;
 private final PropertyOrganizationService service;
 private final org.jetlinks.pro.property.service.PropertyExtensionService extensions;
 @PatchMapping("/{id}/extensions")
 @org.hswebframework.web.authorization.annotation.SaveAction
 public reactor.core.publisher.Mono<PropertyOrganizationEntity> updateExtensions(@PathVariable String id,
   @jakarta.validation.Valid @RequestBody org.jetlinks.pro.property.api.PropertyExtensionRequest request) {
  return extensions.organization(id,request);
 }

 @Override public reactor.core.publisher.Mono<org.hswebframework.web.api.crud.entity.QueryParamEntity> injectQueryParam(org.hswebframework.web.api.crud.entity.QueryParamEntity query) {
  return CorrelatesAssetsHolderQueryController.super.injectQueryParam(query)
   .flatMap(scoped -> tenantScope.injectQuery(scoped,getAssetType(),getAssetProperty()));
 }

 @Override @GetMapping("/{id}") @org.hswebframework.web.authorization.annotation.QueryAction
 public reactor.core.publisher.Mono<PropertyOrganizationEntity> getById(@PathVariable String id) {
  return tenantScope.assertOwned(getAssetType(),id)
   .then(CorrelatesAssetsHolderQueryController.super.getById(id));
 }

 @Override public AssetType getAssetType() {return AssetType.of("organization");}
 @Override public Function<PropertyOrganizationEntity,?> getAssetIdMapper() {return PropertyOrganizationEntity::getId;}
 @Override public String getAssetProperty() {return "id";}
 @Override public ReactiveCrudService<PropertyOrganizationEntity,String> getService() {return service;}
}
