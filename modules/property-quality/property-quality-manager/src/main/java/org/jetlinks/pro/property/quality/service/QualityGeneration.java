package org.jetlinks.pro.property.quality.service;
import lombok.RequiredArgsConstructor;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;
import org.jetlinks.pro.assets.AssetsHolder;import org.jetlinks.pro.property.quality.asset.QualityAssetType;import org.jetlinks.pro.property.quality.entity.*;
import reactor.core.publisher.*;import java.util.*;import java.nio.charset.StandardCharsets;
import static org.jetlinks.pro.property.quality.service.QualityScope.require;import static org.jetlinks.pro.property.quality.service.QualityConfigurationRules.*;
/** 批次按任务和计划时刻唯一；领取计划、生成工单和检查项在同一事务，崩溃不会留下半批次。 */
@Service @RequiredArgsConstructor public class QualityGeneration {
 private final QualityTaskService tasks;private final QualityScheduleService schedules;private final QualityWorkOrderService orders;private final QualityRecordService records;private final QualityLog log;private final QualityParticipation participation;
 @Transactional public Mono<Void> generate(QualityScheduleEntity schedule){
  return tasks.findById(schedule.getTaskId()).filter(t->t.getTaskStatus()==1&&!Boolean.TRUE.equals(t.getDeleted())).flatMap(task->{
   // 与关闭操作锁定同一任务版本，关闭提交后不再产生后续工单。
   long version=task.getVersion();
   return tasks.createUpdate().set("version",version+1).where("id",task.getId()).and("version",version).and("taskStatus",1).execute().filter(n->n==1)
    .flatMap(n->schedules.createUpdate().set("status",2).where("id",schedule.getId()).and("status",1).execute()).filter(n->n==1)
    .flatMap(n->create(task,schedule));
  }).then();
 }
 private Mono<Void> create(QualityTaskEntity task,QualityScheduleEntity schedule){
  var c=new QualityContext(Objects.toString(task.getTenantId()),task.getApplicationId(),task.getCreatorId(),task.getCreatorName());
  var order=QualityScope.initialize(new QualityWorkOrderEntity(),c);QualityScope.inherit(order,task);
  order.setId(UUID.nameUUIDFromBytes((task.getId()+":"+schedule.getScheduledTime()).getBytes(StandardCharsets.UTF_8)).toString().replace("-",""));
  order.setTaskId(task.getId());order.setTaskName(task.getTaskName());order.setTaskLevel(task.getTaskLevel());order.setTaskType(task.getTaskType());order.setTemplateId(task.getTemplateId());order.setTemplateName(task.getTemplateName());order.setTaskCode(order.getId());order.setScheduledTime(schedule.getScheduledTime());order.setCycleStartTime(schedule.getCycleStartTime());order.setCycleEndTime(schedule.getCycleEndTime());order.setReportDeadline(task.getTaskLevel()==2?schedule.getCycleEndTime():QualityScheduleRules.deadline(task,schedule.getScheduledTime()));order.setWorkOrderStatus(1);order.setLineWeights(task.getLineWeights());
  Map<String,Map<String,Object>> assigned=new HashMap<>();for(var a:task.getLineAssignees())assigned.put(text(a,"subjectParentId"),a);
  List<QualityRecordEntity> rows=new ArrayList<>();
  for(var snap:task.getTemplateSnapshot()){
   var record=QualityScope.initialize(new QualityRecordEntity(),c);QualityScope.inherit(record,task);var assignee=assigned.get(text(snap,"subjectParentId"));require(assignee!=null,"assignees_required");
   record.setWorkOrderId(order.getId());record.setTaskId(task.getId());record.setSubjectParentId(text(snap,"subjectParentId"));record.setSubjectParentName(text(snap,"subjectParentName"));record.setSubjectId(text(snap,"subjectId"));record.setSubjectName(text(snap,"subjectName"));record.setCheckContentId(text(snap,"checkContentId"));record.setCheckContentName(text(snap,"contentName"));record.setCheckRequirement(text(snap,"checkContent"));record.setPhotoRequirement(text(snap,"photoRequirement"));record.setOriginalScore(number(snap.get("score")));record.setDeductionStandard(text(snap,"deductionStandard"));
   if(task.getConfigItems()!=null)for(var override:task.getConfigItems())if(Objects.equals(text(override,"configId"),text(snap,"configId")))record.setDeductionStandard(text(override,"deductionStandard"));
   record.setExecutorId(text(assignee,"inspectorId"));record.setExecutorName(text(assignee,"inspectorName"));record.setRecordStatus(order.getReportDeadline()<System.currentTimeMillis()?3:1);record.setReportDeadline(order.getReportDeadline());record.setSortOrder(rows.size());rows.add(record);
  }
  order.setRecordCount(rows.size());order.setOriginalTotalScore(rows.stream().mapToInt(QualityRecordEntity::getOriginalScore).sum());order.setInspectorIds(rows.stream().map(QualityRecordEntity::getExecutorId).distinct().toList());order.setReviewerIds(List.of());
  return orders.insert(order).then(records.insertBatch(Flux.just(rows))).then(AssetsHolder.bindAssets(Flux.just(order),QualityAssetType.qualityWorkOrder,QualityWorkOrderEntity::getId).then())
   .then(schedules.createUpdate().set("workOrderId",order.getId()).where("id",schedule.getId()).execute())
   .then(log.append(order,"generate",c,Map.of("lineAssignees",task.getLineAssignees()))).then(participation.refresh(order));
 }
}
