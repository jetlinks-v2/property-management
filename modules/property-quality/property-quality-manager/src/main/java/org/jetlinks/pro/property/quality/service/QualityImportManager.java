package org.jetlinks.pro.property.quality.service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.quality.entity.QualityConfigurationEntity;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import reactor.core.publisher.*;
import java.util.*;
/** 整份文件的层级引用和写入在单一事务；项目名称只从调用人有权读取的基础目录匹配。 */
@Service @RequiredArgsConstructor
public class QualityImportManager {
 private final QualityConfigurationManager manager;private final QualityConfigurationService service;private final QualityDirectory directory;
 @Transactional public Mono<Map<String,Integer>> apply(List<QualityWorkbook.ImportRow> rows,String target,int level,String projectId,boolean companyOperation){
  return Mono.zip(directory.projects().collectList(),manager.query(new QueryParamEntity()).flatMap(q->{q.setPaging(false);return service.createQuery().setParam(q).fetch().collectList();})).flatMap(tuple->{
   var cache=new ArrayList<>(tuple.getT2());var projects=tuple.getT1();Set<String> workbookKeys=new HashSet<>();
   return Flux.fromIterable(rows).concatMap(r->{String project=level==2?projectId:null;
    String businessTypeId=null;
    if(target.equals("task")){
     if(level==2){var matches=projects.stream().filter(p->p.getName().equals(r.project())).toList();QualityScope.require(matches.size()==1,"import_project");QualityScope.require(Objects.equals(projectId,matches.get(0).getId()),"reference_scope");}
     else {var types=cache.stream().filter(c->c.getKind().equals("business-type")&&c.getStatus()==1&&c.getName().equals(r.project())).toList();QualityScope.require(types.size()==1,"business_type_required");businessTypeId=types.get(0).getId();}
    }
    QualityScope.require(workbookKeys.add(Objects.toString(project,businessTypeId)+":"+r.line()+":"+r.subject()+":"+r.content()),"import_duplicate");
    if(target.equals("task")){
     var contents=cache.stream().filter(c->c.getKind().equals("content")&&c.getName().equals(r.content())&&Objects.equals(c.getData().get("parentName"),r.subject())&&Objects.equals(c.getData().get("subjectParentName"),r.line())&&(c.getConfigLevel()==level&&Objects.equals(c.getProjectId(),project))).toList();QualityScope.require(contents.size()==1,"import_content");var content=contents.get(0);
     Map<String,Object> data=new LinkedHashMap<>();data.put("checkContentId",content.getId());if(businessTypeId!=null)data.put("businessTypeId",businessTypeId);data.put("score",r.score());data.put("deductionStandard",r.deduction());if(r.frequency()!=null)data.put("inspectionFrequency",r.frequency());return upsert(cache,"inspection-content",r.content(),level,project,null,data,r.status(),companyOperation);
    }
    String scope=project;
    return upsert(cache,"line",r.line(),level,scope,null,Map.of(),1,companyOperation).flatMap(line->upsert(cache,"subject",r.subject(),level,scope,line.getId(),Map.of(),target.equals("subjects")?r.status():1,companyOperation))
     .flatMap(subject->target.equals("subjects")?Mono.just(subject):upsert(cache,"content",r.content(),level,scope,subject.getId(),Map.of("checkContent",r.requirement(),"photoRequirement",r.photo()),r.status(),companyOperation));
   }).then(Mono.just(Map.of("imported",rows.size())));
  });
 }
 private Mono<QualityConfigurationEntity> upsert(List<QualityConfigurationEntity> cache,String kind,String name,int level,String project,String parent,Map<String,Object> data,int status,boolean companyOperation){
  var previous=cache.stream().filter(c->c.getKind().equals(kind)&&c.getName().equals(name)&&c.getConfigLevel()==level&&Objects.equals(c.getProjectId(),project)&&Objects.equals(c.getParentId(),parent)&&(!kind.equals("inspection-content")||Objects.equals(c.getData().get("businessTypeId"),data.get("businessTypeId"))&&Objects.equals(c.getSourceType(),companyOperation||level==1?1:2))).findFirst();
  var row=new QualityConfigurationEntity();row.setKind(kind);row.setName(name);row.setConfigLevel(level);row.setProjectId(project);row.setParentId(parent);row.setStatus(status);row.setData(data);previous.ifPresent(p->{row.setId(p.getId());row.setVersion(p.getVersion());});
  return manager.save(row,companyOperation).doOnNext(saved->{previous.ifPresent(cache::remove);cache.add(saved);});
 }
}
