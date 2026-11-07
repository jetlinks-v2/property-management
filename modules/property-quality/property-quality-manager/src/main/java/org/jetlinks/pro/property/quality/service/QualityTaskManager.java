package org.jetlinks.pro.property.quality.service;
import lombok.RequiredArgsConstructor;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.pro.assets.AssetsHolder;import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.quality.asset.QualityAssetType;import org.jetlinks.pro.property.quality.entity.*;
import reactor.core.publisher.*;import java.util.*;
import static org.jetlinks.pro.property.quality.service.QualityScope.require;import static org.jetlinks.pro.property.quality.service.QualityConfigurationRules.*;
/** 任务定义、模板快照及持久化作业计划在同一事务保存，下发和关闭按版本竞争。 */
@Service @RequiredArgsConstructor public class QualityTaskManager {
 private final QualityTaskService tasks;private final QualityScheduleService schedules;private final QualityScope scope;
 private final QualityConfigurationManager configurations;private final QualityDirectory directory;private final QualityLog log;private final QualityTaskCandidates candidates;
 public Mono<QualityTaskEntity> read(String id){return tasks.findById(id).switchIfEmpty(Mono.error(new NotFoundException())).flatMap(r->scope.assertRecord(r,QualityAssetType.qualityTask,id,CrudAssetPermission.read).thenReturn(r));}
 public Mono<QueryParamEntity> query(QueryParamEntity q){return scope.query(q,QualityAssetType.qualityTask,"id");}
 @Transactional public Mono<QualityTaskEntity> save(QualityTaskEntity input){
  require(input.getTaskName()!=null&&!input.getTaskName().isBlank(),"name_required");require(input.getTaskLevel()!=null&&Set.of(1,2).contains(input.getTaskLevel()),"level_required");
  require(input.getTaskType()!=null&&Set.of(1,2).contains(input.getTaskType()),"task_type_required");require(input.getLineAssignees()!=null&&!input.getLineAssignees().isEmpty(),"assignees_required");
  return scope.current().flatMap(c->{boolean create=input.getId()==null;
   Mono<QualityTaskEntity> old=create?Mono.just(QualityScope.initialize(new QualityTaskEntity(),c)):read(input.getId()).flatMap(r->scope.assertRecord(r,QualityAssetType.qualityTask,r.getId(),CrudAssetPermission.save).thenReturn(r));
   return old.flatMap(previous->{
    require(create||previous.getTaskStatus()==0&&previous.getVersion().equals(input.getVersion()),"task_not_editable");
    if(!create)require(Objects.equals(previous.getProjectId(),input.getProjectId())&&Objects.equals(previous.getTaskLevel(),input.getTaskLevel()),"scope_immutable");
    input.setId(previous.getId());input.setApplicationId(c.applicationId());input.setTenantId(c.tenantId());input.setCreatorId(previous.getCreatorId());input.setCreatorName(previous.getCreatorName());input.setCreateTime(previous.getCreateTime());input.setModifyTime(System.currentTimeMillis());input.setDeleted(false);input.setVersion(previous.getVersion());input.setTaskStatus(0);input.setCompleteTime(null);
    return directory.project(input.getProjectId()).doOnNext(p->{input.setProjectName(p.getName());input.setOrganizationId(p.getId());}).then(configurations.read(input.getTemplateId())).doOnNext(template->{
     require(template.getKind().equals("template")&&template.getStatus()==1&&template.getConfigLevel().equals(input.getTaskLevel()),"invalid_reference");
     require(number(template.getData().getOrDefault("templateType",1))==1,"invalid_reference");
     require(Objects.equals(template.getProjectId(),input.getProjectId()),"reference_scope");
     input.setTemplateName(template.getName());input.setTemplateSnapshot(items(template.getData().get("snapshot")));input.setLineWeights(items(template.getData().get("lineWeights")));
     // 公司和项目任务都只能使用同项目模板；作业频次来自模板快照。
     if(input.getTaskLevel()==2)input.setInspectionFrequency(number(template.getData().get("inspectionFrequency")));
     require(!input.getTemplateSnapshot().isEmpty(),"template_items");
     var lines=input.getTemplateSnapshot().stream().map(s->text(s,"subjectParentId")).collect(java.util.stream.Collectors.toSet());Set<String> assigned=new HashSet<>();
     for(var a:input.getLineAssignees())require(lines.contains(text(a,"subjectParentId"))&&assigned.add(text(a,"subjectParentId"))&&!text(a,"inspectorId").isBlank(),"assignees_required");require(assigned.equals(lines),"assignees_required");
     if(input.getTaskLevel()==2){input.setTaskType(2);input.setReportDeadline(input.getEndTime());}
    }).then(Flux.fromIterable(input.getLineAssignees()).concatMap(a->candidates.assertEligible(input,text(a,"inspectorId")).doOnNext(p->a.put("inspectorName",p.getName()))).then())
     .then(Mono.defer(()->{
      var slots=QualityScheduleRules.build(input);List<QualityScheduleEntity> plan=slots.stream().map(slot->{var s=QualityScope.initialize(new QualityScheduleEntity(),c);QualityScope.inherit(s,input);s.setTaskId(input.getId());s.setScheduledTime(slot.time());s.setCycleStartTime(slot.start());s.setCycleEndTime(slot.end());s.setStatus(0);return s;}).toList();
      Mono<?> saved=create?tasks.insert(input):versioned(input,previous.getVersion());
      return saved.then(schedules.createDelete().where("taskId",input.getId()).and("status",0).execute()).then(schedules.insertBatch(Flux.just(plan))).then(create?AssetsHolder.bindAssets(Flux.just(input),QualityAssetType.qualityTask,QualityTaskEntity::getId).single():Mono.just(input));
     }));
   });
  });
 }
 @Transactional public Mono<QualityTaskEntity> issue(String id){return read(id).flatMap(t->scope.assertRecord(t,QualityAssetType.qualityTask,id,CrudAssetPermission.save).then(scope.current()).flatMap(c->{
  require(t.getTaskStatus()==0,"invalid_state");long version=t.getVersion();t.setTaskStatus(1);t.setIssueTime(System.currentTimeMillis());
  return versioned(t,version).then(schedules.createUpdate().set("status",1).where("taskId",id).and("status",0).execute()).then(log.append(t,"issue",c,Map.of())).thenReturn(t);
 }));}
 @Transactional public Mono<QualityTaskEntity> close(String id){return read(id).flatMap(t->scope.assertRecord(t,QualityAssetType.qualityTask,id,CrudAssetPermission.save).then(scope.current()).flatMap(c->{
  require(t.getTaskStatus()==0||t.getTaskStatus()==1,"invalid_state");long version=t.getVersion();t.setTaskStatus(3);t.setCloseTime(System.currentTimeMillis());
  return versioned(t,version).then(schedules.createUpdate().set("status",3).where("taskId",id).in("status",List.of(0,1)).execute()).then(log.append(t,"close",c,Map.of())).thenReturn(t);
 }));}
 public Mono<Integer> versioned(QualityTaskEntity row,long previous){row.setVersion(previous+1);row.setModifyTime(System.currentTimeMillis());return tasks.createUpdate().set(row).where("id",row.getId()).and("version",previous).execute().doOnNext(n->require(n==1,"conflict"));}
 @Transactional public Mono<Void> deleteBatch(List<String> ids){require(ids!=null&&!ids.isEmpty()&&ids.size()<=100,"batch_size");return Flux.fromIterable(ids).distinct().concatMap(this::delete).then();}
 @Transactional public Mono<Void> delete(String id){return read(id).flatMap(t->{require(t.getTaskStatus()==0,"invalid_state");return scope.assertRecord(t,QualityAssetType.qualityTask,id,CrudAssetPermission.delete).then(tasks.createUpdate().set("deleted",true).where("id",id).and("taskStatus",0).execute()).doOnNext(n->require(n==1,"conflict")).then();});}
}
