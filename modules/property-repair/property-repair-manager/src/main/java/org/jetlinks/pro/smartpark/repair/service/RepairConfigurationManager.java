package org.jetlinks.pro.smartpark.repair.service;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hswebframework.web.exception.NotFoundException;
import org.hswebframework.web.id.IDGenerator;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.smartpark.repair.asset.RepairAssetType;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import reactor.core.publisher.Mono;
import java.util.Objects;
/** 应用配置的创建/编辑边界，不允许客户端伪造 tenantId、applicationId 或覆盖另一应用配置。 */
@Service @AllArgsConstructor
public class RepairConfigurationManager {
 private final RepairContextProvider contexts;
 private final RepairConfigurationService configurations;
 private final RepairOrderService orders;
 private final RepairProcessService processes;
 private final RepairQrConfigurationService qrConfigurations;
 private final org.jetlinks.pro.property.service.PropertyExtensionService projects;
 @Transactional public Mono<RepairConfigurationEntity> save(RepairConfigurationEntity input){
  return contexts.current().flatMap(ctx->{
   input.setApplicationId(ctx.applicationId());
   boolean create=input.getId()==null;
   // 首版描述仅存名称：首次编辑/启停时补正文，保留已有数据可维护。
   if("description".equals(input.getKind())&&(input.getConfiguration()==null||!input.getConfiguration().containsKey("content"))){
    var data=new java.util.HashMap<String,Object>(input.getConfiguration()==null?java.util.Map.of():input.getConfiguration());
    data.put("content",input.getName());input.setConfiguration(data);
   }
   if(input.getEnabled()==null&&!java.util.Set.of("label","satisfaction").contains(input.getKind()))input.setEnabled(true);
   RepairFlowRules.require(!create||!"category".equals(input.getKind())||input.getSortIndex()!=null&&input.getSortIndex()>=0,"category_sort_required");
   RepairConfigurationRules.validate(input);
   long modified = System.currentTimeMillis();
   if(create)RepairFlowConfiguration.record(input,null,ctx,modified);
   Mono<Void> permission=create?Mono.empty():get(input.getId(),ctx,"category".equals(input.getKind())).flatMap(old->{
    RepairFlowRules.require(Objects.equals(old.getBusinessProjectId(),input.getBusinessProjectId()),"configuration_project_mismatch");
    RepairFlowRules.require(Objects.equals(old.getKind(),input.getKind()),"configuration_kind_invalid");
    if("qr".equals(input.getKind()))RepairFlowRules.require(Objects.equals(old.getQrCode(),input.getQrCode()),"qr_code_immutable");
    if("category".equals(input.getKind())){
     RepairFlowRules.require(Objects.equals(old.getModifyTime(),input.getModifyTime()),"configuration_changed");
     input.setModifyTime(Math.max(modified,Objects.requireNonNullElse(old.getModifyTime(),0L)+1));
    }
    RepairFlowConfiguration.record(input,old,ctx,"category".equals(input.getKind())?input.getModifyTime():modified);
    return AssetsHolder.assertPermission(RepairAssetType.repairConfiguration,old.getId(),CrudAssetPermission.save);
   });
   permission=permission.then(projects.businessProject(input.getBusinessProjectId())).then(validateParent(input,ctx)).then(validateDictionary(input,ctx))
    .then(qrConfigurations.validate(input,ctx));
   return lockConfigurationWrite(input,ctx).then(permission).then(Mono.defer(()->{
    input.setApplicationId(ctx.applicationId());input.setExtension("tenantId",ctx.tenantId());
    if(create||!"category".equals(input.getKind()))input.setModifyTime(System.currentTimeMillis());
    if("qr".equals(input.getKind()))input.setQrPublisherId(ctx.userId());
    if(create){input.setCreatorId(ctx.userId());input.setCreatorName(ctx.userName());input.setId(IDGenerator.MD5.generate());input.setCreateTime(input.getModifyTime());}
    if(input.getEnabled()==null)input.setEnabled(true);
    return configurations.save(input).then(create?AssetsHolder.bindAssets(reactor.core.publisher.Flux.just(input),RepairAssetType.repairConfiguration,RepairConfigurationEntity::getId).single():Mono.just(input));
   }));
  });
 }
 /** 按ID固定顺序锁定两类配置，避免并发重关联覆盖或反向移动死锁。 */
 @Transactional public Mono<RepairConfigurationEntity> moveFlow(String sourceId,String targetId,
   org.jetlinks.pro.smartpark.repair.api.RepairFlowMoveRequest request){
  RepairFlowRules.require(!Objects.equals(sourceId,targetId),"flow_same_category");
  return contexts.current().flatMap(ctx->reactor.core.publisher.Flux.fromIterable(
    java.util.stream.Stream.of(sourceId,targetId).sorted().toList())
   .concatMap(id->get(id,ctx,true).flatMap(row->AssetsHolder.assertPermission(
    RepairAssetType.repairConfiguration,id,CrudAssetPermission.save).thenReturn(row)))
   .collectMap(RepairConfigurationEntity::getId).flatMap(rows->{
    var source=rows.get(sourceId);var target=rows.get(targetId);
    RepairFlowRules.require(Objects.equals(source.getModifyTime(),request.getSourceModifyTime()),"configuration_changed");
    RepairFlowConfiguration.move(source,target,request.getConfiguration(),System.currentTimeMillis());
    return projects.businessProject(source.getBusinessProjectId()).then(
     configurations.createUpdate().set(RepairConfigurationEntity::getConfiguration,source.getConfiguration())
      .setNull(RepairConfigurationEntity::getFlowCreatorName).setNull(RepairConfigurationEntity::getFlowModifyTime)
      .set(RepairConfigurationEntity::getModifyTime,source.getModifyTime()).where("id",sourceId).execute())
     .then(configurations.createUpdate().set(RepairConfigurationEntity::getConfiguration,target.getConfiguration())
      .set(RepairConfigurationEntity::getFlowCreatorName,target.getFlowCreatorName())
      .set(RepairConfigurationEntity::getFlowModifyTime,target.getFlowModifyTime())
      .set(RepairConfigurationEntity::getModifyTime,target.getModifyTime()).where("id",targetId).execute())
     .thenReturn(target);
   }));
 }
 /** 保存子配置先锁父级，级联删除与子项写入共用同一事务锁；分类按ID排序以兼容流程重关联。 */
 private Mono<Void> lockConfigurationWrite(RepairConfigurationEntity input,RepairContext ctx){
  if("evaluationLabel".equals(input.getKind()))return lockEvaluationLabel(input,ctx);
  if(!java.util.Set.of("category","label","description").contains(input.getKind()))return Mono.empty();
  Mono<RepairConfigurationEntity> previous=input.getId()==null?Mono.just(input):get(input.getId(),ctx);
  return previous.flatMap(old->{
   var parents=new java.util.TreeSet<String>();
   for(String id:java.util.List.of(Objects.toString(old.getParentId(),""),Objects.toString(input.getParentId(),"")))if(!id.isBlank())parents.add(id);
   boolean category="category".equals(input.getKind());
   if(category&&input.getId()!=null)parents.add(input.getId());
   Mono<Void> locks=reactor.core.publisher.Flux.fromIterable(parents).concatMap(id->get(id,ctx,true)).then();
   return !category&&input.getId()!=null?locks.then(get(input.getId(),ctx,true)).then():locks;
  });
 }
 /** 同级别新增去重与编辑串行；旧标签的级别已删除时仍可维护，不把历史ID当作新关联。 */
 private Mono<Void> lockEvaluationLabel(RepairConfigurationEntity input,RepairContext ctx){
  Mono<RepairConfigurationEntity> previous=input.getId()==null?Mono.just(input):get(input.getId(),ctx);
  return previous.flatMap(old->{
   String target=Objects.toString(input.getConfiguration().get("satisfactionId"),"");
   String source=Objects.toString(old.getConfiguration().get("satisfactionId"),"");
   Mono<Void> locks=reactor.core.publisher.Flux.fromIterable(java.util.stream.Stream.of(source,target)
    .filter(id->!id.isBlank()).distinct().sorted().toList())
    .concatMap(id->get(id,ctx,true).onErrorResume(NotFoundException.class,error->
     input.getId()!=null&&Objects.equals(id,source)?Mono.empty():Mono.error(error))).then();
   return input.getId()==null?locks:locks.then(get(input.getId(),ctx,true)).then();
  });
 }
 private Mono<Void> validateDictionary(RepairConfigurationEntity input,RepairContext ctx){
  if("category".equals(input.getKind())){
   return configurations.createQuery().where(RepairConfigurationEntity::getApplicationId,ctx.applicationId())
    .and(RepairConfigurationEntity::getBusinessProjectId,input.getBusinessProjectId())
    .and(RepairConfigurationEntity::getKind,"category").and(RepairConfigurationEntity::getName,input.getName()).fetch()
    .filter(row->!Objects.equals(row.getId(),input.getId())&&Objects.equals(
     RepairConfigurationRules.categoryParent(row.getParentId()),RepairConfigurationRules.categoryParent(input.getParentId())))
    .doOnNext(row->RepairFlowRules.require(false,"category_name_duplicate")).then();
  }
  if("commission".equals(input.getKind())){
   return configurations.createQuery().where("applicationId",ctx.applicationId())
    .and("businessProjectId",input.getBusinessProjectId()).and("kind","commission").fetch()
    .any(row->!Objects.equals(row.getId(),input.getId()))
    .doOnNext(duplicate->RepairFlowRules.require(!duplicate,"commission_project_duplicate")).then();
  }
  if("level".equals(input.getKind())){
   // 校验同时覆盖尚未填专用唯一键的历史记录；数据库唯一键负责并发保存。
   return configurations.createQuery().where("applicationId",ctx.applicationId())
    .and("businessProjectId",input.getBusinessProjectId()).and("kind","level").fetch()
    .filter(row->!Objects.equals(row.getId(),input.getId()))
    .doOnNext(row->RepairFlowRules.require(row.getConfiguration().get("coefficient")==null ||
     new java.math.BigDecimal(row.getConfiguration().get("coefficient").toString())
      .compareTo(java.math.BigDecimal.valueOf(input.getLevelCoefficient()))!=0,"level_coefficient_duplicate")).then();
  }
  if("evaluationLabel".equals(input.getKind())){
   return validateEvaluationLabel(input,ctx);
  }
  if(!"satisfaction".equals(input.getKind()))return Mono.empty();
  return configurations.createQuery().where("applicationId",ctx.applicationId())
   .and("businessProjectId",input.getBusinessProjectId()).and("kind","satisfaction").fetch()
   .filter(row->!Objects.equals(row.getId(),input.getId()))
   .doOnNext(row->{
    RepairFlowRules.require(!Objects.equals(row.getName(),input.getName()),"satisfaction_level_duplicate");
    RepairFlowRules.require(row.getConfiguration()==null||RepairConfigurationRules.score(row.getConfiguration().get("score"))!=input.getSatisfactionScore(),"satisfaction_score_duplicate");
   }).then();
 }
 private Mono<Void> validateEvaluationLabel(RepairConfigurationEntity input,RepairContext ctx){
  String ratingId=Objects.toString(input.getConfiguration().get("satisfactionId"),"");
  Mono<Boolean> unchanged=input.getId()==null?Mono.just(false):get(input.getId(),ctx)
   .map(old->Objects.equals(old.getConfiguration().get("satisfactionId"),ratingId));
  return unchanged.flatMap(same->same?Mono.empty():get(ratingId,ctx).doOnNext(rating->{
   RepairFlowRules.require("satisfaction".equals(rating.getKind())&&Objects.equals(input.getBusinessProjectId(),rating.getBusinessProjectId()),"configuration_project_mismatch");
   RepairFlowRules.require(!Boolean.TRUE.equals(input.getEnabled())||Boolean.TRUE.equals(rating.getEnabled()),"configuration_invalid");
  }).then()).then(Mono.defer(()->{
   // 旧版仅新增时检查同级别同名；编辑产生的重名历史记录不自动改写。
   if(input.getId()!=null)return Mono.empty();
   return configurations.createQuery().where(RepairConfigurationEntity::getApplicationId,ctx.applicationId())
    .and(RepairConfigurationEntity::getBusinessProjectId,input.getBusinessProjectId())
    .and(RepairConfigurationEntity::getKind,"evaluationLabel").and(RepairConfigurationEntity::getName,input.getName()).fetch()
    .any(row->Objects.equals(row.getConfiguration().get("satisfactionId"),ratingId))
    .doOnNext(duplicate->RepairFlowRules.require(!duplicate,"evaluation_label_duplicate")).then();
  }));
 }
 /** 流程删除不删除分类；沿用旧版已关联任何工单即拒绝删除的规则。 */
 @Transactional public Mono<Void> deleteFlow(String id){
  return contexts.current().flatMap(ctx->get(id,ctx,true).flatMap(row->
   AssetsHolder.assertPermission(RepairAssetType.repairConfiguration,id,CrudAssetPermission.save)
    .then(Mono.defer(()->{
     RepairFlowRules.require("category".equals(row.getKind()),"configuration_kind_invalid");
     RepairFlowRules.require(Boolean.FALSE.equals(row.getConfiguration().get("flowEnabled")),"flow_disable_first");
     return orders.createQuery().where("applicationId",ctx.applicationId()).and("businessProjectId",row.getBusinessProjectId())
      .and("categoryId",id).count();
    })).flatMap(count->{
     RepairFlowRules.require(count==0,"configuration_in_use");
     var data=RepairFlowConfiguration.cleared(row.getConfiguration());
     return configurations.createUpdate().set(RepairConfigurationEntity::getConfiguration,data)
      .setNull(RepairConfigurationEntity::getFlowCreatorName).setNull(RepairConfigurationEntity::getFlowModifyTime)
      .set(RepairConfigurationEntity::getModifyTime,Math.max(System.currentTimeMillis(),Objects.requireNonNullElse(row.getModifyTime(),0L)+1)).where("id",id).execute().then();
    })));
 }
 /** 未被引用的分类可直接删除；停用标签连带删除描述，所有资产授权和引用先整体检查。 */
 @Transactional public Mono<Void> delete(String id){
  return contexts.current().flatMap(ctx->get(id,ctx,true).flatMap(row->
   AssetsHolder.assertPermission(RepairAssetType.repairConfiguration,id,CrudAssetPermission.save)
    .then(Mono.defer(()->{
     RepairFlowRules.require(directDeletion(row.getKind())||!Boolean.TRUE.equals(row.getEnabled()),"configuration_disable_first");
     return deletionIds(row,ctx);
    })).flatMap(ids->assertUnused(row,ids,ctx).then(Mono.defer(()->{
     var deletion=configurations.createDelete().where(RepairConfigurationEntity::getApplicationId,ctx.applicationId())
      .and(RepairConfigurationEntity::getBusinessProjectId,row.getBusinessProjectId()).in(RepairConfigurationEntity::getId,ids);
     return deletion.execute().doOnNext(count->RepairFlowRules.require(count==ids.size(),"configuration_changed")).then();
    })))));
 }
 static boolean directDeletion(String kind){return java.util.Set.of("category","level","commission","reply").contains(kind);}
 private Mono<java.util.Set<String>> deletionIds(RepairConfigurationEntity row,RepairContext ctx){
  if(!"label".equals(row.getKind()))return Mono.just(java.util.Set.of(row.getId()));
  // 标签锁由delete持有，子项保存先取得同一标签锁，避免检查后又插入描述。
  return configurations.createQuery().where(RepairConfigurationEntity::getApplicationId,ctx.applicationId())
   .and(RepairConfigurationEntity::getBusinessProjectId,row.getBusinessProjectId())
   .and(RepairConfigurationEntity::getParentId,row.getId()).and(RepairConfigurationEntity::getKind,"description").fetch()
   .concatMap(child->AssetsHolder.assertPermission(RepairAssetType.repairConfiguration,child.getId(),CrudAssetPermission.save).thenReturn(child.getId()))
   .collect(java.util.HashSet<String>::new,java.util.Set::add).map(ids->{ids.add(row.getId());return ids;});
 }
 private Mono<Void> assertUnused(RepairConfigurationEntity row,java.util.Set<String> ids,RepairContext ctx){
  // 原满意度/评价标签删除不连带处理关联字典；工单与过程中的评分、文字快照继续保留。
  if(java.util.Set.of("satisfaction","evaluationLabel").contains(row.getKind()))return Mono.empty();
  return configurations.createQuery().where(RepairConfigurationEntity::getApplicationId,ctx.applicationId())
   .and(RepairConfigurationEntity::getBusinessProjectId,row.getBusinessProjectId()).fetch()
   .filter(other->!ids.contains(other.getId()))
   .any(other->other.getParentId()!=null&&ids.contains(other.getParentId())||ids.stream().anyMatch(id->RepairQrConfigurationService.references(other,id))
    ||other.getConfiguration()!=null&&other.getConfiguration().get("satisfactionId")!=null&&ids.contains(other.getConfiguration().get("satisfactionId")))
   .doOnNext(referenced->RepairFlowRules.require(!referenced,"configuration_in_use"))
   .then(Mono.defer(()->{
    // 旧版回复语与单条描述可删除；历史正文已保存在工单/处理过程，不按当前字典重构。
    // 标签连带删除仍整体检查，不能借子描述的独立删除规则绕过标签引用保护。
    if(java.util.Set.of("reply","description").contains(row.getKind()))return Mono.empty();
    return orders.createQuery().where("applicationId",ctx.applicationId()).and("businessProjectId",row.getBusinessProjectId()).fetch()
    .any(order->ids.stream().anyMatch(id->references(order,id)))
   .doOnNext(referenced->RepairFlowRules.require(!referenced,"configuration_in_use"))
   .then(processes.createQuery().where("applicationId",ctx.applicationId()).fetch()
    .any(process->ids.stream().anyMatch(id->detailsReference(process.getDetails(),id))))
   .doOnNext(referenced->RepairFlowRules.require(!referenced,"configuration_in_use")).then();
   }));
 }
 static boolean references(org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity order,String id){
  return Objects.equals(id,order.getCategoryId())||Objects.equals(id,order.getChannelId())||Objects.equals(id,order.getLevelId())
   ||Objects.equals(id,order.getStaffSatisfactionId())||Objects.equals(id,order.getOwnerSatisfactionId())
   ||order.getLabelIds()!=null&&order.getLabelIds().contains(id)
   ||order.getDescriptionChoiceIds()!=null&&order.getDescriptionChoiceIds().contains(id);
 }
 static boolean detailsReference(java.util.Map<String,Object> details,String id){
  if(details==null)return false;
  return java.util.List.of("categoryId","levelId","satisfactionId","replyId").stream().anyMatch(key->Objects.equals(id,details.get(key)))
   ||java.util.List.of("labelIds","evaluationLabelIds","descriptionChoiceIds").stream().anyMatch(key->details.get(key) instanceof java.util.Collection<?> ids && ids.contains(id));
 }
 /** 深度与父子关系在保存边界校验，避免跨项目标签和循环分类进入数据库。 */
 private Mono<Void> validateParent(RepairConfigurationEntity input,RepairContext ctx){
  boolean hasParent=input.getParentId()!=null&&!input.getParentId().isBlank();
  String kind=input.getKind();
  if(!hasParent){
   RepairFlowRules.require(!java.util.Set.of("label","description").contains(kind),"configuration_parent_required");
   return "category".equals(kind)?validateSubtreeDepth(input,1):Mono.empty();
  }
  RepairFlowRules.require(java.util.Set.of("category","label","description").contains(kind),"configuration_parent_invalid");
  return get(input.getParentId(),ctx).flatMap(parent->{
   RepairConfigurationRules.parent(input,parent);
   if("description".equals(kind))return Mono.empty();
   return categoryDepth(parent,ctx,1,input.getId()).flatMap(depth->{
    if("label".equals(kind))return Mono.empty();
    return validateSubtreeDepth(input,depth+1);
   });
  });
 }
 public Mono<Integer> categoryDepth(RepairConfigurationEntity category,RepairContext ctx,int depth,String excludedId){
  RepairFlowRules.require(depth<=3&&!Objects.equals(category.getId(),excludedId),"configuration_parent_invalid");
  if(category.getParentId()==null||category.getParentId().isBlank())return Mono.just(depth);
  return get(category.getParentId(),ctx).flatMap(parent->{
   RepairConfigurationRules.parent(category,parent);
   return categoryDepth(parent,ctx,depth+1,excludedId);
  });
 }
 private Mono<Void> validateSubtreeDepth(RepairConfigurationEntity category,int depth){
  RepairFlowRules.require(depth<=3,"category_depth_invalid");
  if(category.getId()==null)return Mono.empty();
  return configurations.createQuery().where(RepairConfigurationEntity::getParentId,category.getId())
   .and(RepairConfigurationEntity::getApplicationId,category.getApplicationId())
   .fetch().concatMap(child->{
    if("category".equals(child.getKind()))return validateSubtreeDepth(child,depth+1);
    return Mono.empty();
   }).then();
 }
 public Mono<RepairConfigurationEntity> get(String id,RepairContext ctx){
  return get(id,ctx,false);
 }
 private Mono<RepairConfigurationEntity> get(String id,RepairContext ctx,boolean lock){
  var query=configurations.createQuery().where("id",id).and("applicationId",ctx.applicationId());
  if(lock)query.forUpdate();
  return query.fetchOne().filter(row->Objects.equals(row.getExtension("tenantId"),ctx.tenantId()))
   .switchIfEmpty(Mono.error(new NotFoundException()))
   .flatMap(row->AssetsHolder.assertPermission(org.jetlinks.pro.assets.AssetType.of("organization"),row.getBusinessProjectId(),CrudAssetPermission.read).thenReturn(row));
 }
 public Mono<RepairConfigurationEntity> enabled(String id,String kind,String businessProjectId,RepairContext ctx){
  return get(id,ctx).doOnNext(row->{
   RepairFlowRules.require(Objects.equals(row.getBusinessProjectId(),businessProjectId),"configuration_project_mismatch");
   RepairFlowRules.require(Objects.equals(row.getKind(),kind)&&Boolean.TRUE.equals(row.getEnabled()),"configuration_invalid");
  });
 }
}
