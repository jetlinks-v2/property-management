package org.jetlinks.pro.smartpark.repair.service;
import lombok.AllArgsConstructor;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.exception.NotFoundException;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.io.file.FileManager;
import org.jetlinks.pro.property.service.*;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import org.jetlinks.pro.property.base.space.PropertySpaceInfo;
import org.jetlinks.pro.smartpark.repair.api.*;
import org.jetlinks.pro.smartpark.repair.asset.RepairAssetType;
import org.jetlinks.pro.smartpark.repair.entity.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 工单应用服务。公开动作在这里拥有资产/应用授权、状态约束及工单与过程的事务边界。
 * 角色动作授权由控制器 ResourceAction 承担，业务处理还要求指派资格，超管不自动绕过。
 */
@Service @AllArgsConstructor
public class RepairOrderManager {
 private final RepairContextProvider contexts;
 private final RepairOrderService orders;
 private final RepairProcessService processes;
 private final RepairConfigurationManager configurations;
 private final RepairDirectory extensions;
 private final RepairDirectory people;
 private final RepairDirectory spaces;
 private final FileManager files;
 private final RepairAssigneeService assignees;
 private final TenantAssetScope tenantScope;
 private final RepairIntakeService intake;
 private final RepairWorkflowService workflow;
 private final RepairReviewService reviews;
 private final RepairRoleService roles;
 private final RepairCommerceService commerce;
 private final RepairCandidateService candidates;
 private final RepairCompletionService completion;
 private final RepairCreationService creation;
 private final RepairFormOptionsService formOptions;
 @Transactional
 public Mono<RepairOrderEntity> create(CreateRepairRequest request){
  return contexts.current().flatMap(ctx -> employee(ctx.userId(),ctx)
   .then(Mono.zip(extensions.project(request.getBusinessProjectId()),configurations.enabled(request.getCategoryId(),"category",request.getBusinessProjectId(),ctx),configurations.enabled(request.getChannelId(),"channel",request.getBusinessProjectId(),ctx),formOptions.load(ctx,request.getBusinessProjectId())))
   .flatMap(tuple -> {tuple.getT4().normalize(request);return validateSpace(request).flatMap(space -> validateFiles(request.getAttachmentIds())
    .then(creation.create(request,tuple.getT1(),tuple.getT2(),tuple.getT3(),ctx,
     order->intake.populate(order,request,tuple.getT2(),ctx,tuple.getT4()),
     order->AssetsHolder.bindAssets(Flux.just(order),RepairAssetType.repairOrder,RepairOrderEntity::getId).then(), space)));}));
 }

 public Mono<RepairOrderEntity> read(String id){
  return contexts.current().flatMap(ctx -> ownedOrder(id,ctx))
   .flatMap(order->AssetsHolder.assertPermission(RepairAssetType.repairOrder,order.getId(),CrudAssetPermission.read).thenReturn(order));
 }
 public Flux<RepairProcessEntity> history(String id){
  // 同一次动作的自动归档与完结可能同毫秒落库，按工单版本与动作顺序展示，不能按随机 ID 排序。
  return read(id).flatMapMany(order->processes.createQuery().where(RepairProcessEntity::getOrderId,id).fetch())
   .sort(Comparator.comparing(RepairProcessEntity::getOrderVersion)
    .thenComparing(process->"archive".equals(process.getAction())?1:0)
    .thenComparing(RepairProcessEntity::getCreateTime)
    .thenComparing(RepairProcessEntity::getId));
 }
 @Transactional
 public Mono<RepairOrderEntity> action(String id,String action,RepairActionRequest request){
  return contexts.current().flatMap(ctx->employee(ctx.userId(),ctx).then(ownedOrder(id,ctx))
   .flatMap(order->AssetsHolder.assertPermission(RepairAssetType.repairOrder,id,CrudAssetPermission.save).thenReturn(order))
   .flatMap(order->processes.findById(processId(id,request.getRequestId())).flatMap(process->{
    RepairFlowRules.require(Objects.equals(process.getActorId(),ctx.userId())&&Objects.equals(process.getAction(),action),"request_conflict");return Mono.just(order);
   }).switchIfEmpty(Mono.defer(()->apply(order,ctx,action,request)))));
 }
 private Mono<RepairOrderEntity> apply(RepairOrderEntity order,RepairContext ctx,String action,RepairActionRequest request){
  RepairFlowRules.require(Objects.equals(order.getVersion(),request.getVersion()),"version_conflict");
  return configurations.get(order.getCategoryId(),ctx).doOnNext(category->{
   RepairFlowRules.require("category".equals(category.getKind())&&Objects.equals(category.getBusinessProjectId(),order.getBusinessProjectId()),"configuration_invalid");
  }).flatMap(category->{
   Map<String,Object> flow=category.getConfiguration()==null?Map.of():category.getConfiguration();
   // 停用流程不等于分类停用；旧版缺少有效流程时仍进入回访，不直接归档。
   boolean callback=!RepairFlowConfiguration.enabled(flow)||Boolean.TRUE.equals(flow.get("callbackRequired"));
   int before=order.getStatus();int after=RepairFlowRules.nextStatus(order,action,ctx.userId(),request,callback);
   Mono<Void> preparation=switch(action){
    case "dispatch" -> assign(order,ctx,request);
    case "accept" -> candidates.accept(order,ctx);
    case "transfer" -> transfer(order,ctx,request,flow);
    case "grab" -> employee(ctx.userId(),ctx).flatMap(person->assignees.assertCanHandle(person,order,ctx))
     .doOnNext(person->{order.setAssignedUserId(ctx.userId());order.setAssignedUserName(ctx.userName());}).then();
    case "changeCategory" -> changeCategory(order,ctx,request);
    case "follow" -> prepareFollow(order,ctx,request,flow);
    case "callback" -> {
     String role=RepairFlowConfiguration.enabled(flow)?Objects.toString(flow.get("callbackRoleId"),""):"";
     yield callbackActor(order,ctx,role);
    }
    case "finish" -> {
     String role=RepairFlowConfiguration.enabled(flow)?Objects.toString(flow.get("callbackRoleId"),""):"";
     yield callback&&!role.isBlank()?roles.assertAvailable(role,"callback",order,ctx):Mono.empty();
    }
    default -> Mono.empty();
   };
   Mono<Void> signatureFile="sign".equals(action)&&request.getSignatureFileId()!=null?validateFiles(List.of(request.getSignatureFileId())):Mono.empty();
   return workflow.guard(order,action).then(preparation)
    .then(completion.restoreSignedFinish(order,action,request)).flatMap(restored ->
    Mono.defer(()->validateFiles(request.getAttachmentIds())).then(signatureFile)
    .then(restored?Mono.empty():commerce.prepare(action,order,request,ctx))
    // 先完成字典快照解析，再校验动作表单；回访不能依赖客户端自行传入满意度分值。
    .then(Mono.defer(()->workflow.prepare(order,ctx,action,request,flow,after))).flatMap(finalStatus->Mono.defer(()->{
    long now=System.currentTimeMillis();long version=order.getVersion();
    order.setStatus(finalStatus);order.setVersion(version+1);order.setModifyTime(now);
    if(action.equals("accept")||action.equals("grab"))order.setAcceptTime(now);
    if(action.equals("finish")){order.setFinishTime(now);order.setTimedOut(order.getFinishDeadline()!=null&&now>order.getFinishDeadline());}
    if(finalStatus==5&&before!=5)order.setArchiveTime(now);
    if(action.equals("callback")&&Boolean.TRUE.equals(request.getRework())){order.setFinishTime(null);order.setFinishDeadline(now+days(flow)*86400000L);}
    // 条件更新确保同版本只有一个动作胜出，过程插入失败回滚整个事务。
    var update = orders.createUpdate().set(order);
    if(order.getFinishTime()==null)update.setNull(RepairOrderEntity::getFinishTime);
    if(order.getAcceptTime()==null)update.setNull(RepairOrderEntity::getAcceptTime);
    if(order.getFinishDeadline()==null)update.setNull(RepairOrderEntity::getFinishDeadline);
    if(order.getCandidateRoleId()==null)update.setNull(RepairOrderEntity::getCandidateRoleId);
    if(order.getCandidateSource()==null)update.setNull(RepairOrderEntity::getCandidateSource);
    return update.where(RepairOrderEntity::getId,order.getId()).and(RepairOrderEntity::getVersion,version).execute()
     .flatMap(changed->{if(changed!=1)return Mono.error(new BusinessException("error.repair.version_conflict",409));
      return writeProcess(order,ctx,action,request.getRequestId(),before,request.getDescription(),request.getAttachmentIds(),request)
       .then(finalStatus==5&&before!=5?writeProcess(order,ctx,"archive",request.getRequestId()+"-archive",finalStatus,"自动归档",List.of()):Mono.empty()).thenReturn(order);
     });
   })));
  });
 }
 /** 候选列表按目标工单检查每人的真实处理授权；无需与工单组织具有相同人员归属。 */
 public Flux<RepairAssignee> assignees(String id){
  return contexts.current().flatMapMany(ctx->read(id).flatMapMany(order->{
   RepairFlowRules.require(java.util.Set.of(0,6,7,1).contains(order.getStatus()),"invalid_state");
   return people.employees()
    .filterWhen(person->assignees.canHandle(person,order,ctx),8)
    .map(person->new RepairAssignee(person.getId(),display(person.getName(),person.getName())));
  }));
 }
 public Flux<RepairReviewEntity> reviews(String id){
  return read(id).flatMapMany(order->reviews.createQuery().where(RepairReviewEntity::getOrderId,id)
   .orderBy(SortOrder.desc(RepairReviewEntity::getCreateTime),SortOrder.asc(RepairReviewEntity::getId)).fetch());
 }
 private Mono<Void> transfer(RepairOrderEntity order,RepairContext ctx,RepairActionRequest request,Map<String,Object> flow){
  RepairFlowConfiguration.requireEnabled(flow);
  int count=order.getTransferCount()==null?0:order.getTransferCount();
  RepairFlowRules.require(count<((Number)flow.getOrDefault("transferLimit",0)).intValue(),"transfer_limit");
  return assign(order,ctx,request).doOnSuccess(ignore->{order.setTransferCount(count+1);order.setAcceptTime(null);
   order.setFinishDeadline(null);order.setFinishTime(null);order.setTimedOut(false);});
 }
 private Mono<Void> changeCategory(RepairOrderEntity order,RepairContext ctx,RepairActionRequest request){
  RepairFlowRules.require(request.getCategoryId()!=null,"category_leaf_required");
  RepairFlowRules.require(request.getLabelIds()!=null&&!request.getLabelIds().isEmpty(),"labels_required");
  return configurations.enabled(request.getCategoryId(),"category",order.getBusinessProjectId(),ctx).flatMap(category->
   configurations.categoryDepth(category,ctx,1,null).doOnNext(depth->RepairFlowRules.require(depth==3,"category_leaf_required"))
    .thenMany(Flux.fromIterable(request.getLabelIds()).distinct().concatMap(id->configurations.enabled(id,"label",order.getBusinessProjectId(),ctx)
     .doOnNext(label->RepairFlowRules.require(Objects.equals(label.getParentId(),category.getId()),"label_category_mismatch"))))
    .collectList().doOnNext(labels->{order.setCategoryId(category.getId());order.setCategoryName(category.getName());
     order.setLabelIds(labels.stream().map(RepairConfigurationEntity::getId).toList());order.setLabelNames(labels.stream().map(RepairConfigurationEntity::getName).toList());
     order.setDescriptionChoiceIds(List.of());}).then());
 }
 private Mono<Void> assign(RepairOrderEntity order,RepairContext ctx,RepairActionRequest request){
  RepairFlowRules.require(request.getAssignedUserId()!=null&&!request.getAssignedUserId().isBlank(),"assignee_required");
  return employee(request.getAssignedUserId(),ctx)
   .flatMap(person->assignees.assertCanHandle(person,order,ctx))
   .doOnNext(person->{order.setAssignedUserId(person.getId());order.setAssignedUserName(display(person.getName(),person.getName()));
    order.setCandidateRoleId(null);order.setCandidateSource(null);}).then();
 }
 private Mono<Void> prepareFollow(RepairOrderEntity order,RepairContext ctx,RepairActionRequest request,Map<String,Object> flow){
  if(order.getStatus()!=7)return Mono.empty();
  return configurations.enabled(request.getLevelId(),"level",order.getBusinessProjectId(),ctx).doOnNext(level->{
   RepairWorkflowService.applyLevel(order,level);
   order.setFinishDeadline(System.currentTimeMillis()+days(flow)*86400000L);
  }).then();
 }
 private long days(Map<String,Object> flow){RepairFlowConfiguration.requireEnabled(flow);return ((Number)flow.getOrDefault("finishTimeLimitDays",1)).longValue();}
 private Mono<Void> callbackActor(RepairOrderEntity order,RepairContext ctx,String role){
  if(role.isBlank()){RepairFlowRules.require(Objects.equals(order.getAssignedUserId(),ctx.userId()),"not_assignee");return Mono.empty();}
  return roles.assertRole(role,"callback",order,ctx);
 }
 private Mono<PropertyUserInfo> employee(String id,RepairContext ctx){
  return people.employee(id);
 }
 private Mono<PropertySpaceInfo> validateSpace(CreateRepairRequest request){
  if(request.getSpaceId()==null||request.getSpaceId().isBlank())return Mono.just(new PropertySpaceInfo());
  return spaces.space(request.getBusinessProjectId(),request.getSpaceId());
 }
 private Mono<Void> validateFiles(List<String> ids){
  if(ids==null||ids.isEmpty())return Mono.empty();
  return Flux.fromIterable(ids).concatMap(id->AssetsHolder.assertPermission(AssetType.of("file"),id,CrudAssetPermission.read)
   .then(files.getFile(id)).switchIfEmpty(Mono.error(new BusinessException("error.repair.attachment_missing",400)))).then();
 }
 private Mono<RepairOrderEntity> ownedOrder(String id,RepairContext ctx){
  return orders.findById(id).filter(row->Objects.equals(row.getApplicationId(),ctx.applicationId())&&Objects.equals(row.getExtension("tenantId"),ctx.tenantId()))
   .switchIfEmpty(Mono.error(new NotFoundException()));
 }
 private Mono<Void> writeProcess(RepairOrderEntity order,RepairContext ctx,String action,String requestId,Integer before,String description,List<String> attachments){
  return writeProcess(order,ctx,action,requestId,before,description,attachments,null);
 }
 private Mono<Void> writeProcess(RepairOrderEntity order,RepairContext ctx,String action,String requestId,Integer before,String description,List<String> attachments,RepairActionRequest request){
  RepairProcessEntity process=new RepairProcessEntity();process.setId(processId(order.getId(),requestId));process.setOrderId(order.getId());
  process.setTenantId(ctx.tenantId());process.setApplicationId(ctx.applicationId());process.setAction(action);process.setRequestId(requestId);
  process.setActorId(ctx.userId());process.setActorName(ctx.userName());process.setBeforeStatus(before);process.setAfterStatus(order.getStatus());process.setOrderVersion(order.getVersion());
  process.setDescription(description);process.setAttachmentIds(attachments);process.setCreateTime(System.currentTimeMillis());
  if(request!=null){
   Map<String,Object> details=org.hswebframework.web.bean.FastBeanCopier.copy(request,new java.util.LinkedHashMap<>());
   details.values().removeIf(Objects::isNull);details.remove("requestId");details.remove("version");process.setDetails(details);
  }
  if(order.getCandidateRoleId()!=null){
   Map<String,Object> details=process.getDetails()==null?new java.util.LinkedHashMap<>():new java.util.LinkedHashMap<>(process.getDetails());
   details.put("candidateRoleId",order.getCandidateRoleId());details.put("candidateSource",order.getCandidateSource());process.setDetails(details);
  }
  return processes.insert(process).then();
 }
 private String processId(String order,String request){return UUID.nameUUIDFromBytes((order+":"+request).getBytes(StandardCharsets.UTF_8)).toString().replace("-","");}
 private String display(String custom,String source){return custom==null||custom.isBlank()?source:custom;}
}
