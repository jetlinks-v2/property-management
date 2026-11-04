package org.jetlinks.pro.property.quality.service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.exception.NotFoundException;
import org.hswebframework.reactor.excel.*;
import org.jetlinks.pro.io.excel.ExcelUtils;
import org.jetlinks.pro.io.file.FileManager;
import org.jetlinks.pro.property.quality.entity.*;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
/** 数据库队列承接异步导出；下载前逐批重新检查每个源工单，撤权后不可下载旧文件。 */
@Service @RequiredArgsConstructor
public class QualityExportManager {
 private final QualityStatistics statistics;private final QualityTaskManager taskAccess;private final QualityTaskService tasks;private final QualityScope scope;private final QualityExportService exports;private final QualityReports reports;
 private final QualityWorkOrders access;private final QualityWorkOrderService orders;private final QualityRecordService records;private final FileManager files;
 public Mono<QualityExportEntity> create(QueryParamEntity query,String type){
  QualityScope.require(Set.of("orders","records","problems","project","line").contains(type),"export_type");
  return scope.current().flatMap(c->access.query(query).flatMap(q->{
   var row=QualityScope.initialize(new QualityExportEntity(),c);row.setName("品质核查-"+type+"-"+row.getCreateTime()+".xlsx");row.setExportScope(type);row.setStatus(0);row.setQuery(Map.of("query",q));row.setLeaseUntil(0L);return exports.insert(row).thenReturn(row);
  }));
 }
 public Mono<PagerResult<QualityExportEntity>> query(QueryParamEntity q){return scope.current().flatMap(c->{var scoped=q.clone().toNestQuery().and("applicationId",c.applicationId()).and("creatorId",c.userId()).and("deleted",false).getParam();scoped.setPageSize(Math.min(100,Math.max(1,q.getPageSize())));return exports.queryPager(scoped);});}
 public Mono<QualityExportEntity> read(String id){return scope.current().flatMap(c->exports.findById(id).filter(r->r.getApplicationId().equals(c.applicationId())&&r.getCreatorId().equals(c.userId())&&Objects.equals(r.getTenantId(),c.tenantId()))).switchIfEmpty(Mono.error(new NotFoundException()));}
 public Mono<QualityExportEntity> createProblems(org.jetlinks.pro.property.quality.api.QualityProblemQuery request){
  return create(request.getOrderQuery(),"problems").flatMap(row->{var query=new LinkedHashMap<>(row.getQuery());query.put("recordQuery",request.getRecordQuery());row.setQuery(query);return exports.updateById(row.getId(),row).thenReturn(row);});
 }
 public Mono<QualityExportEntity> createReport(org.jetlinks.pro.property.quality.api.QualityReportFilter filter,String type){
  QualityScope.require(Set.of("task-statistics","line-statistics").contains(type),"export_type");
  return scope.current().flatMap(c->{var row=QualityScope.initialize(new QualityExportEntity(),c);row.setName("品质核查-"+type+"-"+row.getCreateTime()+".xlsx");row.setExportScope(type);row.setStatus(0);row.setQuery(Map.of("reportFilter",filter));row.setLeaseUntil(0L);return exports.insert(row).thenReturn(row);});
 }
 public Mono<Void> generate(QualityExportEntity row){
  if(row.getQuery().containsKey("reportFilter"))return generateReport(row);
  QueryParamEntity query=com.alibaba.fastjson.JSON.parseObject(com.alibaba.fastjson.JSON.toJSONString(row.getQuery().get("query")),QueryParamEntity.class);
  return access.query(query).flatMap(q->{q.setPaging(false);return orders.createQuery().setParam(q).fetch().map(QualityWorkOrderEntity::getId).take(10001).collectList();}).flatMap(ids->{
   QualityScope.require(ids.size()<=10000,"export_limit");row.setSourceOrderIds(ids);row.setWorkOrderCount(ids.size());
   Flux<Map<String,Object>> data;List<String> headers;
   switch(row.getExportScope()){
    case "project"->{headers=List.of("projectName","workOrderCount","completedCount","averageScore");data=reports.grouped(query,"project");}
    case "line"->{headers=List.of("projectName","lineName","recordCount","submittedCount","unqualifiedCount","qualifiedRate","score");data=reports.grouped(query,"line");}
    case "records","problems"->{headers=List.of("projectName","workOrderId","subjectParentName","subjectName","checkContentName","checkRequirement","originalScore","score","isQualified","checkResult","executorName","photoIds","rectificationPhotoIds");
     var rq=row.getQuery().containsKey("recordQuery")?com.alibaba.fastjson.JSON.parseObject(com.alibaba.fastjson.JSON.toJSONString(row.getQuery().get("recordQuery")),QueryParamEntity.class):new QueryParamEntity();rq.setPaging(false);if(row.getExportScope().equals("problems"))rq.toQuery().and("isQualified",0);
     data=reports.recordQuery(query,rq).flatMapMany(p->records.createQuery().setParam(p).fetch()).map(r->com.alibaba.fastjson.JSON.parseObject(com.alibaba.fastjson.JSON.toJSONString(r)));}
    default->{headers=List.of("projectName","taskName","taskCode","templateName","workOrderStatus","recordCount","originalTotalScore","totalScore","evaluationName","inspectorName","inspectionTime","reportDeadline");data=access.query(query).flatMapMany(q->{q.setPaging(false);return orders.createQuery().setParam(q).fetch();}).map(r->com.alibaba.fastjson.JSON.parseObject(com.alibaba.fastjson.JSON.toJSONString(r)));}
   }
   return write(row,data,headers);
  });
 }
 private Mono<Void> write(QualityExportEntity row,Flux<Map<String,Object>> data,List<String> headers){
   AtomicInteger count=new AtomicInteger();var names=headers;
   var cells=data.map(r->{QualityScope.require(count.incrementAndGet()<=200000,"export_limit");Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<names.size();i++){Object v=r.get(names.get(i));if(v instanceof String s&&!s.isEmpty()&&"=+-@\t\r\n".indexOf(s.charAt(0))>=0)v="'"+s;out.put("c"+i,Objects.toString(v,""));}return out;});
   var excelHeaders=IntStream.range(0,names.size()).mapToObj(i->new ExcelHeader("c"+i,names.get(i),CellDataType.STRING)).toList();
   return files.saveFile(row.getName(),ExcelUtils.write(excelHeaders,cells,"xlsx").subscribeOn(Schedulers.boundedElastic())).flatMap(file->{row.setFileId(file.getId());row.setStatus(2);row.setRecordCount(count.get());row.setModifyTime(System.currentTimeMillis());return exports.updateById(row.getId(),row).then();});
 }
 private Mono<Void> generateReport(QualityExportEntity row){
  var filter=com.alibaba.fastjson.JSON.parseObject(com.alibaba.fastjson.JSON.toJSONString(row.getQuery().get("reportFilter")),org.jetlinks.pro.property.quality.api.QualityReportFilter.class);
  return taskAccess.query(QualityStatistics.taskQuery(filter)).flatMap(q->tasks.createQuery().setParam(q).fetch().map(QualityTaskEntity::getId).take(10001).collectList().flatMap(ids->{
   QualityScope.require(ids.size()<=10000,"export_limit");row.setSourceTaskIds(ids);
   var oq=QueryParamEntity.newQuery().where("taskId",QualityTaskTermBuilder.TYPE,q.getTerms()).getParam();oq.setPaging(false);
   return access.query(oq).flatMap(scoped->orders.createQuery().setParam(scoped).fetch().map(QualityWorkOrderEntity::getId).take(10001).collectList());
  })).flatMap(ids->{
   QualityScope.require(ids.size()<=10000,"export_limit");row.setSourceOrderIds(ids);row.setWorkOrderCount(ids.size());
   boolean taskReport=row.getExportScope().equals("task-statistics");
   var headers=taskReport?List.of(filter.getTaskLevel()==1?"businessTypeName":"projectName","totalTask","closeTask","completeTask","totalRate","validRate","qualifiedContentCount","unqualifiedContentCount","totalRectificationCount","rectificationRate","comprehensiveQualifiedRate")
    :List.of("lineName","problemCount","rectifiedCount","unrectifiedCount","score","rectificationRate","previousProblemCount","problemGrowthRate");
   return write(row,taskReport?statistics.tasks(filter):statistics.lines(filter),headers);
  });
 }

 public Flux<DataBuffer> download(String id){return read(id).flatMap(row->{QualityScope.require(row.getStatus()==2,"export_not_ready");return Flux.fromIterable(row.getSourceOrderIds()).concatMap(access::read).thenMany(Flux.fromIterable(row.getSourceTaskIds()==null?List.<String>of():row.getSourceTaskIds()).concatMap(taskAccess::read)).then(Mono.just(row));}).flatMapMany(row->files.read(row.getFileId()));}
}
