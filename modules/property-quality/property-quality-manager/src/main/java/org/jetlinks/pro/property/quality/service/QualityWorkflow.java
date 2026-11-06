package org.jetlinks.pro.property.quality.service;
import lombok.RequiredArgsConstructor;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;
import org.jetlinks.pro.property.quality.api.QualityAction;import org.jetlinks.pro.property.quality.entity.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;import org.jetlinks.pro.property.quality.asset.QualityAssetType;
import reactor.core.publisher.*;import java.util.*;import java.util.stream.Collectors;
import static org.jetlinks.pro.property.quality.service.QualityScope.require;
/** 核查、分批整改和原核查人复验的唯一状态 owner；工单版本锁保护跨条线并发及重复提交。 */
@Service @RequiredArgsConstructor public class QualityWorkflow {
 private final QualityFiles files;private final QualityScope scope;private final QualityWorkOrders access;private final QualityWorkOrderService orders;
 private final QualityRecordService records;private final QualityRectificationService rectifications;private final QualityDraftService drafts;
 private final QualityConfigurationService configurations;private final QualityTaskService tasks;private final QualityScheduleService schedules;
 private final QualityAssignees assignees;private final QualityLog log;private final QualityParticipation participation;
 @Transactional public Mono<Void> draft(String id,QualityAction request){return access.writable(id).flatMap(order->scope.current().flatMap(c->{
  require(order.getWorkOrderStatus()==1,"invalid_state");require(order.getVersion().equals(request.getVersion()),"conflict");
  return assigned(order,c).collectList().flatMap(assigned->{Set<String> owned=assigned.stream().map(QualityRecordEntity::getId).collect(Collectors.toSet());
   require(!owned.isEmpty()&&request.getResults().stream().allMatch(r->owned.contains(r.getRecordId())),"not_assignee");
   return drafts.createQuery().where("workOrderId",id).and("executorId",c.userId()).fetchOne().defaultIfEmpty(QualityScope.initialize(new QualityDraftEntity(),c)).flatMap(d->{
    QualityScope.inherit(d,order);d.setWorkOrderId(id);d.setExecutorId(c.userId());d.setContent(Map.of("results",request.getResults()));d.setModifyTime(System.currentTimeMillis());return drafts.save(d).then();
   });
  });
 }));}
 @Transactional public Mono<QualityWorkOrderEntity> inspect(String id,QualityAction request){return access.writable(id).flatMap(order->scope.current().flatMap(c->lock(order,request.getVersion()).then(assigned(order,c).collectList()).flatMap(assigned->{
  require(order.getWorkOrderStatus()==1&&!assigned.isEmpty(),"not_assignee");
  Map<String,QualityAction.Result> inputs=new LinkedHashMap<>();for(var result:request.getResults())require(inputs.put(result.getRecordId(),result)==null,"duplicate_record");
  require(assigned.size()==inputs.size()&&assigned.stream().allMatch(r->inputs.containsKey(r.getId())),"submit_all_assigned");
  List<QualityRecordEntity> failed=new ArrayList<>();long now=System.currentTimeMillis();
  for(var record:assigned){var input=inputs.get(record.getId());QualityScoring.validateScore(record.getOriginalScore(),input.getScore());
   require(record.getRecordStatus()!=2,"already_submitted");record.setScore(input.getScore());record.setIsQualified(input.getIsQualified());record.setCheckResult(input.getCheckResult());record.setPhotoIds(input.getPhotoIds());record.setRecordStatus(2);record.setCompleteTime(now);record.setModifyTime(now);record.setExecutorName(c.userName());
   if(input.getIsQualified()==0)failed.add(record);
  }
  if(!failed.isEmpty())require(!request.getAssigneeIds().isEmpty(),"rectifiers_required");
  Mono<Void> qualify=Flux.fromIterable(request.getAssigneeIds()).concatMap(person->assignees.assertEligible(person,order,"rectify",null)).then();
  return qualify.then(files.validate(assigned.stream().flatMap(r->r.getPhotoIds()==null?java.util.stream.Stream.<String>empty():r.getPhotoIds().stream()).toList())).then(records.save(Flux.fromIterable(assigned))).then(createRectification(order,c,failed,request.getAssigneeIds()))
   .then(drafts.createDelete().where("workOrderId",id).and("executorId",c.userId()).execute())
   .then(log.append(order,"inspect",c,Map.of("recordIds",assigned.stream().map(QualityRecordEntity::getId).toList())))
   .then(refresh(order,c)).flatMap(updated->completeTask(updated).thenReturn(updated));
 })));}
 private Flux<QualityRecordEntity> assigned(QualityWorkOrderEntity order,QualityContext c){return records.createQuery().where("workOrderId",order.getId()).and("executorId",c.userId()).and("deleted",false).not("recordStatus",2).fetch();}
 private Mono<Void> createRectification(QualityWorkOrderEntity order,QualityContext c,List<QualityRecordEntity> failed,List<String> people){
  if(failed.isEmpty())return Mono.empty();var row=QualityScope.initialize(new QualityRectificationEntity(),c);QualityScope.inherit(row,order);row.setWorkOrderId(order.getId());row.setTaskId(order.getTaskId());row.setTaskName(order.getTaskName());row.setTaskLevel(order.getTaskLevel());row.setRectificationStatus(1);row.setRecordIds(failed.stream().map(QualityRecordEntity::getId).toList());row.setExecutorIds(people.stream().distinct().toList());row.setReviewerId(c.userId());row.setReviewerName(c.userName());
  return rectifications.insert(row).then(log.append(row,"assignRectification",c,Map.of("assigneeIds",row.getExecutorIds(),"recordIds",row.getRecordIds())));
 }
 @Transactional public Mono<QualityWorkOrderEntity> rectify(String id,QualityAction request){return access.rectification(id).flatMap(rect->access.writable(rect.getWorkOrderId()).flatMap(order->scope.current().flatMap(c->{
  require((rect.getRectificationStatus()==1||rect.getRectificationStatus()==4)&&rect.getExecutorIds().contains(c.userId()),"not_rectifier");
  Map<String,QualityAction.Result> submitted=new HashMap<>();for(var r:request.getResults())require(submitted.put(r.getRecordId(),r)==null,"duplicate_record");
  require(submitted.keySet().equals(new HashSet<>(rect.getRecordIds())),"rectification_items");require(request.getDescription()!=null&&!request.getDescription().isBlank(),"rectification_description");
  return lock(order,request.getVersion()).then(records.createQuery().in("id",rect.getRecordIds()).and("workOrderId",order.getId()).fetch().collectList()).flatMap(rows->{
   for(var record:rows){var input=submitted.get(record.getId());require(input.getPhotoIds()!=null&&!input.getPhotoIds().isEmpty(),"rectification_photos");record.setRectificationPhotoIds(input.getPhotoIds());record.setModifyTime(System.currentTimeMillis());}
   rect.setRectificationStatus(2);rect.setSubmitterId(c.userId());rect.setSubmitterName(c.userName());rect.setRectificationTime(System.currentTimeMillis());rect.setRectificationDesc(request.getDescription());
   return files.validate(rows.stream().flatMap(r->r.getRectificationPhotoIds().stream()).toList()).then(records.save(Flux.fromIterable(rows))).then(rectifications.updateById(id,rect)).then(log.append(rect,"rectify",c,Map.of())).then(refresh(order,c));
  });
 })));}
 @Transactional public Mono<QualityWorkOrderEntity> review(String id,QualityAction request,boolean pass){return access.rectification(id).flatMap(rect->access.writable(rect.getWorkOrderId()).flatMap(order->scope.current().flatMap(c->{
  require(rect.getRectificationStatus()==2&&c.userId().equals(rect.getReviewerId()),"not_reviewer");if(!pass)require(request.getDescription()!=null&&!request.getDescription().isBlank(),"reject_reason");
  rect.setRectificationStatus(pass?3:4);rect.setReviewTime(System.currentTimeMillis());rect.setReviewerName(c.userName());rect.setRejectReason(pass?null:request.getDescription());
  return lock(order,request.getVersion()).then(rectifications.updateById(id,rect)).then(log.append(rect,pass?"reviewPass":"reviewReject",c,Map.of("description",Objects.toString(request.getDescription(),""))))
   .then(refresh(order,c)).flatMap(updated->completeTask(updated).thenReturn(updated));
 })));}
 @Transactional public Mono<QualityWorkOrderEntity> reassign(String id,QualityAction request,boolean administrative){return access.writable(id).flatMap(order->scope.current().flatMap(c->{
  require(order.getWorkOrderStatus()==1&&request.getAssigneeIds().size()==1&&!request.getRecordIds().isEmpty(),"invalid_state");String person=request.getAssigneeIds().get(0);
  return lock(order,request.getVersion()).then(assignees.assertEligible(person,order,"inspect",null)).flatMap(target->records.createQuery().where("workOrderId",id).in("id",request.getRecordIds()).fetch().collectList().flatMap(rows->{
   require(rows.size()==new HashSet<>(request.getRecordIds()).size()&&rows.stream().allMatch(r->r.getRecordStatus()!=2&&(administrative||c.userId().equals(r.getExecutorId()))),"not_assignee");
   // 整条线转派，不能只移动同一条线的一部分检查项。
   var lines=rows.stream().map(QualityRecordEntity::getSubjectParentId).collect(Collectors.toSet());
   return records.createQuery().where("workOrderId",id).in("subjectParentId",lines).fetch().collectList().flatMap(all->{
    require(all.size()==rows.size()&&all.stream().allMatch(r->request.getRecordIds().contains(r.getId())),"reassign_whole_line");
    for(var row:rows){row.setExecutorId(person);row.setExecutorName(target.getName());}
    return records.save(Flux.fromIterable(rows)).then(drafts.createDelete().where("workOrderId",id).execute()).then(log.append(order,"reassignInspection",c,Map.of("recordIds",request.getRecordIds(),"assigneeId",person)))
     .then(refresh(order,c));
   });
  }));
 }));}
 @Transactional public Mono<QualityWorkOrderEntity> reassignRectification(String id,QualityAction request){return access.rectification(id).flatMap(rect->access.writable(rect.getWorkOrderId()).flatMap(order->scope.current().flatMap(c->{
  require((rect.getRectificationStatus()==1||rect.getRectificationStatus()==4)&&rect.getExecutorIds().contains(c.userId())&&!request.getAssigneeIds().isEmpty(),"not_rectifier");
  return lock(order,request.getVersion()).thenMany(Flux.fromIterable(request.getAssigneeIds()).concatMap(person->assignees.assertEligible(person,order,"rectify",null))).then(Mono.defer(()->{
   var previous=rect.getExecutorIds();rect.setExecutorIds(request.getAssigneeIds().stream().distinct().toList());return rectifications.updateById(id,rect).then(log.append(rect,"reassignRectification",c,Map.of("previous",previous,"assigneeIds",rect.getExecutorIds()))).then(refresh(order,c));
  }));
 })));}
 private Mono<Void> lock(QualityWorkOrderEntity order,Long expected){
  require(expected!=null&&expected.equals(order.getVersion()),"conflict");order.setVersion(expected+1);order.setModifyTime(System.currentTimeMillis());
  return orders.createUpdate().set("version",expected+1).set("modifyTime",order.getModifyTime()).where("id",order.getId()).and("version",expected).execute().doOnNext(n->require(n==1,"conflict")).then();
 }
 /** 部分条线提交时保留待核查；所有条线提交后按未完成整改集合决定待整改、待复验或完成。 */
 private Mono<QualityWorkOrderEntity> refresh(QualityWorkOrderEntity order,QualityContext c){return Mono.zip(
  records.createQuery().where("workOrderId",order.getId()).and("deleted",false).fetch().collectList(),
  rectifications.createQuery().where("workOrderId",order.getId()).and("deleted",false).fetch().collectList()).flatMap(t->{
  var rows=t.getT1();var rects=t.getT2();order.setInspectorIds(rows.stream().map(QualityRecordEntity::getExecutorId).distinct().toList());order.setReviewerIds(rects.stream().filter(r->r.getRectificationStatus()==2).map(QualityRectificationEntity::getReviewerId).distinct().toList());
  if(rows.stream().anyMatch(r->r.getRecordStatus()!=2))order.setWorkOrderStatus(1);
  else{
   int status=rects.stream().anyMatch(r->r.getRectificationStatus()==1||r.getRectificationStatus()==4)?2:rects.stream().anyMatch(r->r.getRectificationStatus()==2)?3:4;
   order.setWorkOrderStatus(status);order.setTotalScore(QualityScoring.total(order.getTaskLevel(),rows,order.getLineWeights()));
   order.setInspectorId(c.userId());order.setInspectorName(c.userName());if(order.getInspectionTime()==null)order.setInspectionTime(System.currentTimeMillis());
   if(status==4)order.setCompleteTime(System.currentTimeMillis());
  }
  Mono<Void> evaluation=order.getTotalScore()==null?Mono.empty():configurations.createQuery().where("applicationId",order.getApplicationId()).and("kind","evaluation").and("status",1).and("deleted",false).fetch()
   .filter(r->QualityConfigurationRules.number(r.getData().get("minScore"))<=order.getTotalScore()&&QualityConfigurationRules.number(r.getData().get("maxScore"))>=order.getTotalScore()).next().doOnNext(r->order.setEvaluationName(r.getName())).then();
  return evaluation.then(orders.updateById(order.getId(),order)).then(participation.refresh(order)).thenReturn(order);
 });}
 private Mono<Void> completeTask(QualityWorkOrderEntity order){
  if(order.getWorkOrderStatus()!=4)return Mono.empty();
  return schedules.createQuery().where("taskId",order.getTaskId()).in("status",List.of(0,1)).count().filter(n->n==0)
   .flatMap(n->orders.createQuery().where("taskId",order.getTaskId()).not("workOrderStatus",4).count()).filter(n->n==0)
   .flatMap(n->tasks.createUpdate().set("taskStatus",2).set("completeTime",System.currentTimeMillis()).where("id",order.getTaskId()).and("taskStatus",1).execute()).then();
 }
}
