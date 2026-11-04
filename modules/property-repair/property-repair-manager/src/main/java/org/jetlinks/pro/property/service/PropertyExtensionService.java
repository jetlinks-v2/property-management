package org.jetlinks.pro.property.service;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.jetlinks.pro.property.api.PropertyExtensionRequest;
import org.jetlinks.pro.property.entity.*;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.exception.NotFoundException;
import reactor.core.publisher.Mono;

/** 共享扩展唯一写入入口；资产权限关联真实 Cloud 组织、用户或空间，不改变认证主数据。 */
@Service @AllArgsConstructor
public class PropertyExtensionService {
 private final TenantAssetScope tenantScope;
 private final PropertyOrganizationService organizations;
 private final PropertyPersonService persons;
 private final PropertySpaceService spaces;
 @Transactional
 public Mono<PropertyOrganizationEntity> organization(String id, PropertyExtensionRequest request) {
  return tenantScope.assertOwned(AssetType.of("organization"),id)
   .then(AssetsHolder.assertPermission(AssetType.of("organization"),id,CrudAssetPermission.save))
   .then(organizations.findById(id)).switchIfEmpty(Mono.error(new NotFoundException()))
   .flatMap(row -> {
    var update=organizations.createUpdate().where(PropertyOrganizationEntity::getId,id);
    if(request.getBusinessProject()!=null) update.set(PropertyOrganizationEntity::getBusinessProject,request.getBusinessProject());
    if(request.getDisplayName()!=null) update.set(PropertyOrganizationEntity::getDisplayName,request.getDisplayName());
    if(request.getAddress()!=null) update.set(PropertyOrganizationEntity::getAddress,request.getAddress());
    return update.set(PropertyOrganizationEntity::getModifyTime,System.currentTimeMillis()).execute().then(organizations.findById(id));
   });
 }
 @Transactional
 public Mono<PropertyPersonEntity> person(String id,PropertyExtensionRequest request) {
  return tenantScope.assertOwned(AssetType.of("user"),id)
   .then(AssetsHolder.assertPermission(AssetType.of("user"),id,CrudAssetPermission.save))
   .then(persons.findById(id)).switchIfEmpty(Mono.error(new NotFoundException()))
   .flatMap(row -> {
    var update=persons.createUpdate().where(PropertyPersonEntity::getId,id);
    if(request.getEmployee()!=null) update.set(PropertyPersonEntity::getEmployee,request.getEmployee());
    if(request.getOwner()!=null) update.set(PropertyPersonEntity::getOwner,request.getOwner());
    if(request.getDisplayName()!=null) update.set(PropertyPersonEntity::getDisplayName,request.getDisplayName());
    return update.set(PropertyPersonEntity::getModifyTime,System.currentTimeMillis()).execute().then(persons.findById(id));
   });
 }
 @Transactional
 public Mono<PropertySpaceEntity> space(String id,PropertyExtensionRequest request) {
  Mono<Void> project = request.getBusinessProjectId()==null || request.getBusinessProjectId().isBlank()
   ? Mono.empty() : businessProject(request.getBusinessProjectId()).then();
  return tenantScope.assertOwned(AssetType.of("space"),id)
   .then(AssetsHolder.assertPermission(AssetType.of("space"),id,CrudAssetPermission.save))
   .then(project).then(spaces.findById(id)).switchIfEmpty(Mono.error(new NotFoundException()))
   .flatMap(row -> {
    var update=spaces.createUpdate().where(PropertySpaceEntity::getId,id);
    if(request.getBusinessProjectId()!=null) update.set(PropertySpaceEntity::getBusinessProjectId,request.getBusinessProjectId());
    if(request.getDisplayName()!=null) update.set(PropertySpaceEntity::getDisplayName,request.getDisplayName());
    return update.set(PropertySpaceEntity::getModifyTime,System.currentTimeMillis()).execute().then(spaces.findById(id));
   });
 }
 /** 创建工单和空间归属都要求业务项目已打标签且源组织仍可访问。 */
 public Mono<PropertyOrganizationEntity> businessProject(String id) {
  return tenantScope.assertOwned(AssetType.of("organization"),id)
   .then(AssetsHolder.assertPermission(AssetType.of("organization"),id,CrudAssetPermission.read))
   .then(organizations.findById(id)).filter(p -> Boolean.TRUE.equals(p.getBusinessProject()) && Boolean.TRUE.equals(p.getSourceEnabled()))
   .switchIfEmpty(Mono.error(new BusinessException("error.repair.project_not_enabled",400)));
 }
}
