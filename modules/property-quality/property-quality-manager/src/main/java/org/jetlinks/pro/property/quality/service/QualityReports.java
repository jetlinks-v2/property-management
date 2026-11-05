package org.jetlinks.pro.property.quality.service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.pro.property.quality.entity.*;
import reactor.core.publisher.*;
import java.util.*;
import java.math.*;
/** 首页、项目和条线统计以授权工单及真实检查项为共同来源；数据流分批读取，不展开全量ID。 */
@Service @RequiredArgsConstructor
public class QualityReports {
 private final QualityWorkOrders access;private final QualityWorkOrderService orders;private final QualityRecordService records;
 public Mono<QueryParamEntity> recordQuery(QueryParamEntity orderQuery,QueryParamEntity recordQuery){
  return access.query(orderQuery).map(q->{q.setPaging(false);return recordQuery.clone().toNestQuery().and("workOrderId",QualityWorkOrderTermBuilder.TYPE,q.getTerms()).and("deleted",false).getParam();});
 }
 public Mono<PagerResult<QualityRecordEntity>> problems(QueryParamEntity oq,QueryParamEntity rq){
  rq=rq.clone();rq.toQuery().and("isQualified",0);rq.setPageSize(Math.min(200,Math.max(1,rq.getPageSize())));
  return recordQuery(oq,rq).flatMap(records::queryPager);
 }
 public Mono<Map<String,Object>> summary(QueryParamEntity query){return access.query(query).flatMap(q->{
  q.setPaging(false);
  return orders.createQuery().setParam(q).fetch().reduce(new LinkedHashMap<String,Long>(),(acc,o)->{acc.merge("total",1L,Long::sum);acc.merge("status"+o.getWorkOrderStatus(),1L,Long::sum);if(o.getReportDeadline()!=null&&o.getReportDeadline()<System.currentTimeMillis()&&o.getWorkOrderStatus()!=4)acc.merge("overdue",1L,Long::sum);return acc;})
   .flatMap(counts->recordQuery(query,new QueryParamEntity()).flatMap(rq->records.createQuery().setParam(rq).fetch().reduce(new long[3],(n,r)->{n[0]++;if(r.getRecordStatus()==2)n[1]++;if(Objects.equals(r.getIsQualified(),0))n[2]++;return n;}))
    .map(n->{Map<String,Object> result=new LinkedHashMap<>(counts);result.put("recordCount",n[0]);result.put("submittedRecordCount",n[1]);result.put("unqualifiedCount",n[2]);result.put("qualifiedRate",n[1]==0?BigDecimal.ZERO:BigDecimal.valueOf(n[1]-n[2]).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(n[1]),2,RoundingMode.HALF_UP));return result;}));
 });}
 public Flux<Map<String,Object>> grouped(QueryParamEntity query,String group){
  QualityScope.require(Set.of("project","line").contains(group),"report_group");
  if(group.equals("line"))return recordQuery(query,new QueryParamEntity()).flatMapMany(q->{q.setPaging(false);return records.createQuery().setParam(q).fetch();})
   .collect(LinkedHashMap<String,Map<String,Object>>::new,(groups,r)->{String key=Objects.toString(r.getProjectId(),"")+":"+r.getSubjectParentId();var row=groups.computeIfAbsent(key,k->base(r.getProjectId(),r.getProjectName(),r.getSubjectParentId(),r.getSubjectParentName()));add(row,"recordCount",1);if(r.getRecordStatus()==2){add(row,"submittedCount",1);add(row,"score",r.getScore()==null?0:r.getScore());}if(Objects.equals(r.getIsQualified(),0))add(row,"unqualifiedCount",1);})
   .flatMapMany(groups->Flux.fromIterable(groups.values()).map(QualityReports::rate));
  return access.query(query).flatMapMany(q->{q.setPaging(false);return orders.createQuery().setParam(q).fetch();})
   .collect(LinkedHashMap<String,Map<String,Object>>::new,(groups,o)->{var row=groups.computeIfAbsent(o.getProjectId(),k->base(o.getProjectId(),o.getProjectName(),"",""));add(row,"workOrderCount",1);if(o.getWorkOrderStatus()==4)add(row,"completedCount",1);if(o.getTotalScore()!=null){add(row,"score",o.getTotalScore());add(row,"scoredCount",1);}})
   .flatMapMany(groups->Flux.fromIterable(groups.values()).map(r->{long n=((Number)r.getOrDefault("scoredCount",0)).longValue();r.put("averageScore",n==0?BigDecimal.ZERO:new BigDecimal(r.get("score").toString()).divide(BigDecimal.valueOf(n),2,RoundingMode.HALF_UP));return r;}));
 }
 private static Map<String,Object> base(String project,String name,String line,String lineName){Map<String,Object> r=new LinkedHashMap<>();r.put("projectId",project);r.put("projectName",name);r.put("lineId",line);r.put("lineName",lineName);return r;}
 private static void add(Map<String,Object> row,String key,long n){row.put(key,((Number)row.getOrDefault(key,0)).longValue()+n);}
 private static Map<String,Object> rate(Map<String,Object> row){long n=((Number)row.getOrDefault("submittedCount",0)).longValue(),bad=((Number)row.getOrDefault("unqualifiedCount",0)).longValue();row.put("qualifiedRate",n==0?BigDecimal.ZERO:BigDecimal.valueOf(n-bad).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(n),2,RoundingMode.HALF_UP));return row;}
}
