package org.jetlinks.pro.smartpark.repair.web;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.hswebframework.web.authorization.annotation.*;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.service.ReactiveCrudService;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.crud.CorrelatesAssetsHolderQueryController;
import org.jetlinks.pro.smartpark.repair.entity.*;
import org.jetlinks.pro.smartpark.repair.api.*;
import org.jetlinks.pro.smartpark.repair.service.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.util.function.Function;
/** 工单/配置查询保留框架 QueryParam、分页与资产注入，并限定可信应用范围。 */
@RestController @AllArgsConstructor @RequestMapping("/repair/configurations")
@Resource(id="repair-configuration",name="报事配置")
public class RepairConfigurationController implements CorrelatesAssetsHolderQueryController<RepairConfigurationEntity,String>{
 private final RepairContextProvider contexts;
 private final org.jetlinks.pro.property.service.TenantAssetScope tenantScope;
 private final RepairConfigurationService service;
 private final RepairConfigurationManager manager;
 private final RepairDirectory directory;
 @Override public AssetType getAssetType(){return AssetType.of("repairConfiguration");}
 @Override public Function<RepairConfigurationEntity,?> getAssetIdMapper(){return RepairConfigurationEntity::getId;}
 @Override public String getAssetProperty(){return "id";}
 @Override public ReactiveCrudService<RepairConfigurationEntity,String> getService(){return service;}
 @Override public Mono<QueryParamEntity> injectQueryParam(QueryParamEntity query){
  // 配置资产权限与关联业务项目组织权限取交集；前端项目筛选不能替代这个边界。
  return contexts.current().flatMap(ctx->CorrelatesAssetsHolderQueryController.super
   .injectQueryParam(query.clone().toNestQuery().and("applicationId",ctx.applicationId()).getParam())
   // 组织权限由独立基础服务通过命令解析；报修库只按授权项目 ID 取交集，不解析 Cloud 组织表。
   .flatMap(scoped->directory.projects().map(org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo::getId)
    .collectList().map(ids->scoped.toNestQuery(q->{
     if(ids.isEmpty())q.isNull("businessProjectId").notNull("businessProjectId");
     else q.in("businessProjectId",ids);
    }).getParam()))
   .flatMap(scoped -> tenantScope.injectQuery(scoped,getAssetType(),getAssetProperty())));
 }
 @Override @GetMapping("/{id}") @QueryAction
 public Mono<RepairConfigurationEntity> getById(@PathVariable String id){
  return contexts.current().flatMap(ctx -> manager.get(id,ctx))
   .flatMap(row -> org.jetlinks.pro.assets.AssetsHolder.assertPermission(getAssetType(),row.getId(),org.jetlinks.pro.assets.crud.CrudAssetPermission.read).thenReturn(row));
 }
 @PostMapping @SaveAction
 public Mono<RepairConfigurationEntity> save(@jakarta.validation.Valid @RequestBody RepairConfigurationEntity input){return manager.save(input);}
 @DeleteMapping("/{id}") @SaveAction
 public Mono<Void> delete(@PathVariable String id){return manager.delete(id);}
 /** 删除分类对应的独立流程配置，保留分类及其标签。 */
 @DeleteMapping("/{id}/flow") @SaveAction
 public Mono<Void> deleteFlow(@PathVariable String id){return manager.deleteFlow(id);}
 /** 原流程编辑允许更换报事分类；仅移动流程字段，保留分类和工单。 */
 @PutMapping("/{id}/flow/{targetId}") @SaveAction
 public Mono<RepairConfigurationEntity> moveFlow(@PathVariable String id,@PathVariable String targetId,
   @jakarta.validation.Valid @RequestBody RepairFlowMoveRequest request){return manager.moveFlow(id,targetId,request);}
}
