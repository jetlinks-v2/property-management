package org.jetlinks.pro.property.web;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.crud.CorrelatesAssetsHolderQueryController;
import org.hswebframework.web.crud.service.ReactiveCrudService;
import org.jetlinks.pro.property.entity.PropertySpaceEntity;
import org.jetlinks.pro.property.service.PropertySpaceService;
import java.util.function.Function;
/** 共享目录查询沿用 Cloud 原始space资产的 ID 与数据权限。 */
@RestController("repairPropertySpaceController") @AllArgsConstructor
@RequestMapping("/property-data/spaces")
@Resource(id="property-data",name="物业共享基础数据")
public class PropertySpaceController implements CorrelatesAssetsHolderQueryController<PropertySpaceEntity,String> {
 private final org.jetlinks.pro.property.service.TenantAssetScope tenantScope;
 private final PropertySpaceService service;
 private final org.jetlinks.pro.property.service.PropertyExtensionService extensions;
 @PatchMapping("/{id}/extensions")
 @org.hswebframework.web.authorization.annotation.SaveAction
 public reactor.core.publisher.Mono<PropertySpaceEntity> updateExtensions(@PathVariable String id,
   @jakarta.validation.Valid @RequestBody org.jetlinks.pro.property.api.PropertyExtensionRequest request) {
  return extensions.space(id,request);
 }

 @Override public reactor.core.publisher.Mono<org.hswebframework.web.api.crud.entity.QueryParamEntity> injectQueryParam(org.hswebframework.web.api.crud.entity.QueryParamEntity query) {
  return CorrelatesAssetsHolderQueryController.super.injectQueryParam(query)
   .flatMap(scoped -> tenantScope.injectQuery(scoped,getAssetType(),getAssetProperty()));
 }

 @Override @GetMapping("/{id}") @org.hswebframework.web.authorization.annotation.QueryAction
 public reactor.core.publisher.Mono<PropertySpaceEntity> getById(@PathVariable String id) {
  return tenantScope.assertOwned(getAssetType(),id)
   .then(CorrelatesAssetsHolderQueryController.super.getById(id));
 }

 @Override public AssetType getAssetType() {return AssetType.of("space");}
 @Override public Function<PropertySpaceEntity,?> getAssetIdMapper() {return PropertySpaceEntity::getId;}
 @Override public String getAssetProperty() {return "id";}
 @Override public ReactiveCrudService<PropertySpaceEntity,String> getService() {return service;}
}
