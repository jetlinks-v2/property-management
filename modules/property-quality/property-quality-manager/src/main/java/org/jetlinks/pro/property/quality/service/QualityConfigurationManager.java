package org.jetlinks.pro.property.quality.service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.quality.asset.QualityAssetType;
import org.jetlinks.pro.property.quality.entity.*;
import reactor.core.publisher.*;
import java.util.*;
import static org.jetlinks.pro.property.quality.service.QualityScope.require;
import static org.jetlinks.pro.property.quality.service.QualityConfigurationRules.*;
/** 配置公开写入入口：归属不可变、层级引用同域、模板保存检查项快照。 */
@Service @RequiredArgsConstructor
public class QualityConfigurationManager {
 private final QualityConfigurationService service;private final QualityScope scope;private final QualityDirectory directory;
 public Mono<QualityConfigurationEntity> read(String id){return service.findById(id).switchIfEmpty(Mono.error(new NotFoundException())).flatMap(r->scope.assertRecord(r,QualityAssetType.qualityConfiguration,id,CrudAssetPermission.read).thenReturn(r));}
 public Mono<QueryParamEntity> query(QueryParamEntity q){return scope.query(q,QualityAssetType.qualityConfiguration,"id");}
 @Transactional public Mono<QualityConfigurationEntity> save(QualityConfigurationEntity input){
  return save(input,false);
 }
 @Transactional public Mono<QualityConfigurationEntity> save(QualityConfigurationEntity input,boolean companyOperation){
  validate(input);
  return scope.current().flatMap(c->{
   boolean create=input.getId()==null;
   Mono<QualityConfigurationEntity> row=create?Mono.just(QualityScope.initialize(new QualityConfigurationEntity(),c)):read(input.getId()).flatMap(old->scope.assertRecord(old,QualityAssetType.qualityConfiguration,old.getId(),CrudAssetPermission.save).thenReturn(old));
   return row.flatMap(r->{
    // 公司创建的项目配置可供项目使用，只有公司操作入口允许修改。
    if(input.getConfigLevel()==1||companyOperation)return requireCompanyPermission("save").then(saveAuthorized(input,r,create,companyOperation));
    require(create||!Objects.equals(r.getSourceType(),1),"company_configuration_readonly");
    return saveAuthorized(input,r,create,false);
   });
  });
 }
 private Mono<Void> requireCompanyPermission(String action){return org.hswebframework.web.authorization.Authentication.currentReactive().doOnNext(auth->{if(!auth.hasPermission("quality-configuration","companyConfigure")||!auth.hasPermission("quality-configuration",action))throw new org.hswebframework.web.authorization.exception.AccessDenyException();}).then();}
 private Mono<QualityConfigurationEntity> saveAuthorized(QualityConfigurationEntity input,QualityConfigurationEntity r,boolean create,boolean companyOperation){
    if(!create){require(Objects.equals(r.getVersion(),input.getVersion()),"conflict");require(r.getKind().equals(input.getKind())&&r.getConfigLevel().equals(input.getConfigLevel())&&Objects.equals(r.getProjectId(),input.getProjectId()),"scope_immutable");}
    r.setKind(input.getKind());r.setName(input.getName().trim());r.setConfigLevel(input.getConfigLevel());r.setStatus(input.getStatus());r.setParentId(input.getParentId());r.setSortOrder(input.getSortOrder());r.setData(new LinkedHashMap<>(input.getData()));
    // 公司基础定义共用，检查配置与模板始终归属基础档案中的真实项目。
    if(create)r.setProjectId(input.getConfigLevel()==2||Set.of("template","inspection-content").contains(input.getKind())?input.getProjectId():null);
    if(create)r.setSourceType(input.getConfigLevel()==1||companyOperation?1:2);
    r.setUniqueKey(r.getKind()+":"+r.getConfigLevel()+":"+Objects.toString(r.getProjectId(),"company")+":"+Objects.toString(r.getParentId(),"root")+":"+r.getName());
    if(r.getKind().equals("inspection-content"))r.setUniqueKey("inspection-content:"+r.getConfigLevel()+":"+r.getProjectId()+":"+r.getSourceType()+":"+text(r.getData(),"checkContentId"));
    Mono<Void> project=r.getProjectId()==null?Mono.empty():directory.project(r.getProjectId()).doOnNext(p->{r.setProjectName(p.getName());r.setOrganizationId(p.getId());}).then();
    return project.then(validateReference(r)).then(prepareDimensions(r)).then(prepareTemplate(r)).then(validateEvaluation(r)).then(Mono.defer(()->persist(r,create)));
 }
 /** 项目是唯一业务维度；旧业态字段不再进入新的配置与快照。 */
 private Mono<Void> prepareDimensions(QualityConfigurationEntity row){
  row.getData().remove("businessTypeId");row.getData().remove("businessTypeName");
  if(row.getConfigLevel()==2&&row.getKind().equals("template"))require(Set.of(1,2,3).contains(number(row.getData().get("inspectionFrequency"))),"frequency_required");
  return Mono.empty();
 }
 private Mono<Void> validateReference(QualityConfigurationEntity row){
  String ref=Set.of("subject","content").contains(row.getKind())?row.getParentId():row.getKind().equals("inspection-content")?text(row.getData(),"checkContentId"):null;
  if(ref==null&&row.getKind().equals("line")&&!text(row.getData(),"categoryId").isBlank())return read(text(row.getData(),"categoryId")).doOnNext(parent->{require(parent.getKind().equals("category")&&parent.getConfigLevel().equals(row.getConfigLevel())&&Objects.equals(parent.getProjectId(),row.getProjectId()),"reference_scope");row.getData().put("categoryName",parent.getName());}).then();
  if(ref==null)return Mono.empty();
  return read(ref).doOnNext(parent->{
   String expected=switch(row.getKind()){case "subject"->"line";case "content"->"subject";default->"content";};
   require(parent.getKind().equals(expected)&&parent.getStatus()==1,"invalid_reference");
   require(parent.getConfigLevel().equals(row.getConfigLevel())&&Objects.equals(parent.getProjectId(),row.getConfigLevel()==1?null:row.getProjectId()),"reference_scope");
   row.getData().put("parentName",parent.getName());
   if(row.getKind().equals("inspection-content")){row.getData().put("contentName",parent.getName());row.getData().put("checkContent",parent.getData().get("checkContent"));row.getData().put("photoRequirement",parent.getData().get("photoRequirement"));row.getData().put("subjectId",parent.getParentId());row.getData().put("subjectName",parent.getData().get("parentName"));row.getData().put("subjectParentId",parent.getData().get("subjectParentId"));row.getData().put("subjectParentName",parent.getData().get("subjectParentName"));}
   if(row.getKind().equals("content")){row.getData().put("subjectParentId",parent.getParentId());row.getData().put("subjectParentName",parent.getData().get("parentName"));}
  }).then();
 }
 private Mono<Void> prepareTemplate(QualityConfigurationEntity row){
  if(!row.getKind().equals("template"))return Mono.empty();
  var configs=items(row.getData().get("configItems"));require(!configs.isEmpty()&&configs.size()<=1000,"template_items");
  require(configs.stream().map(i->text(i,"configId")).distinct().count()==configs.size(),"template_duplicate");
  return Flux.fromIterable(configs).concatMap(item->read(text(item,"configId")).map(config->{
   require(config.getKind().equals("inspection-content")&&config.getStatus()==1,"invalid_reference");
   require(config.getConfigLevel().equals(row.getConfigLevel()),"reference_scope");
   require(Objects.equals(config.getProjectId(),row.getProjectId()),"reference_scope");
   if(row.getConfigLevel()==2)require(Objects.equals(config.getData().get("inspectionFrequency"),row.getData().get("inspectionFrequency")),"frequency_required");
   Map<String,Object> snap=new LinkedHashMap<>(config.getData());snap.put("configId",config.getId());snap.put("projectId",Objects.toString(config.getProjectId(),""));snap.put("contentName",config.getName());
   Object score=item.getOrDefault("score",snap.get("score"));QualityConfigurationRules.score(score);snap.put("score",score);snap.put("deductionStandard",item.getOrDefault("deductionStandard",snap.getOrDefault("deductionStandard","")));return snap;
  })).collectList().doOnNext(snapshots->{
   row.getData().put("snapshot",snapshots);
   var weights=items(row.getData().get("lineWeights"));QualityScoring.validateWeights(row.getConfigLevel(),snapshots,weights);
   row.getData().put("totalScore",snapshots.stream().mapToInt(s->number(s.get("score"))).sum());
  }).then();
 }
 private Mono<Void> validateEvaluation(QualityConfigurationEntity row){
  if(!row.getKind().equals("evaluation")||row.getStatus()!=1)return Mono.empty();
  return scope.current().flatMapMany(c->service.createQuery().where("applicationId",c.applicationId()).and("kind","evaluation").and("status",1).and("deleted",false).fetch())
   .filter(r->!r.getId().equals(row.getId())).doOnNext(other->require(number(other.getData().get("maxScore"))<number(row.getData().get("minScore"))||number(other.getData().get("minScore"))>number(row.getData().get("maxScore")),"evaluation_overlap")).then();
 }
 private Mono<QualityConfigurationEntity> persist(QualityConfigurationEntity row,boolean create){
  row.setLineId(text(row.getData(),"subjectParentId"));row.setSubjectId(text(row.getData(),"subjectId"));
  row.setCategoryId(text(row.getData(),"categoryId"));
  row.setInspectionFrequency(row.getData().get("inspectionFrequency")==null?null:number(row.getData().get("inspectionFrequency")));
  if(row.getKind().equals("subject"))row.setLineId(row.getParentId());
  if(row.getKind().equals("content"))row.setSubjectId(row.getParentId());
  row.setModifyTime(System.currentTimeMillis());
  if(create)return service.insert(row).then(AssetsHolder.bindAssets(Flux.just(row),QualityAssetType.qualityConfiguration,QualityConfigurationEntity::getId).single());
  long before=row.getVersion();row.setVersion(before+1);
  return service.createUpdate().set(row).where("id",row.getId()).and("version",before).execute().doOnNext(n->require(n==1,"conflict")).then(synchronizeDeduction(row)).thenReturn(row);
 }
 /** 旧配置修改扣分标准会同步模板关联；已保存任务的快照不受后续配置编辑影响。 */
 private Mono<Void> synchronizeDeduction(QualityConfigurationEntity source){
  if(!source.getKind().equals("inspection-content"))return Mono.empty();
  return service.createQuery().where("applicationId",source.getApplicationId()).and("kind","template").and("deleted",false).fetch()
   .filter(template->items(template.getData().get("snapshot")).stream().anyMatch(item->source.getId().equals(text(item,"configId"))))
   .concatMap(template->{
    var data=new LinkedHashMap<>(template.getData());
    for(String key:List.of("snapshot","configItems"))data.put(key,items(data.get(key)).stream().map(item->{var copy=new LinkedHashMap<>(item);if(source.getId().equals(text(item,"configId")))copy.put("deductionStandard",source.getData().getOrDefault("deductionStandard",""));return copy;}).toList());
    return service.createUpdate().set("data",data).set("version",template.getVersion()+1).set("modifyTime",System.currentTimeMillis()).where("id",template.getId()).and("version",template.getVersion()).execute().doOnNext(n->require(n==1,"conflict"));
   }).then();
 }
 @Transactional public Mono<Void> deleteBatch(List<String> ids,boolean companyOperation){require(ids!=null&&!ids.isEmpty()&&ids.size()<=100,"batch_size");return Flux.fromIterable(ids).distinct().concatMap(id->delete(id,companyOperation)).then();}
 @Transactional public Mono<Void> delete(String id){return delete(id,false);}
 @Transactional public Mono<Void> delete(String id,boolean companyOperation){return read(id).flatMap(row->{
  require(row.getConfigLevel()==1||!Objects.equals(row.getSourceType(),1)||companyOperation,"company_configuration_readonly");
  return (row.getConfigLevel()==1||companyOperation?requireCompanyPermission("delete"):Mono.<Void>empty()).then(scope.assertRecord(row,QualityAssetType.qualityConfiguration,id,CrudAssetPermission.delete))
  .then(service.createQuery().where("parentId",id).and("deleted",false).count()).doOnNext(n->require(n==0,"configuration_in_use"))
  .then(service.createUpdate().set("deleted",true).set("uniqueKey","deleted:"+id).where("id",id).execute()).then();});}
}
