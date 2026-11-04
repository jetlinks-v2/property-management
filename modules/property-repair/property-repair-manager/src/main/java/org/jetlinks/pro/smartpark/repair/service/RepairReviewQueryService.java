package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.util.ArrayList;
import java.util.Set;

/** 从已注入工单权限的查询获取审核清单；不会因审核角色或前端筛选扩大工单可见范围。 */
@Service @AllArgsConstructor
public class RepairReviewQueryService {
 private final QueryHelper helper;
 public Mono<PagerResult<RepairReviewOrderRow>> query(QueryParamEntity scoped,Integer status,String kind){
  var selection=selection(scoped,status,kind);
  return selection.fetchPaged(Math.max(0,scoped.getPageIndex()),Math.min(200,Math.max(1,scoped.getPageSize())));
 }
 public Flux<RepairReviewOrderRow> exportRows(QueryParamEntity scoped,Integer status,String kind){
  return selection(scoped,status,kind).fetch();
 }
 private QueryHelper.ExecuteSpec<RepairReviewOrderRow> selection(QueryParamEntity scoped,Integer status,String kind){
  RepairFlowRules.require(status==null||Set.of(1,2,3).contains(status),"review_status_invalid");
  RepairFlowRules.require(kind==null||Set.of("delay","close","levelChange","archive","abandon").contains(kind),"review_kind_invalid");
  String sql="select sp_repair_order.*,r.id as \"reviewId\",r.kind as \"reviewKind\",r.status as \"reviewStatus\","
   +"r.reason as \"reviewReason\",r.applicant_name as \"reviewApplicantName\",r.create_time as \"reviewCreateTime\",r.review_time as \"reviewTime\","
   +"r.reviewer_name as \"reviewReviewerName\",r.opinion as \"reviewOpinion\" "
   +"from sp_repair_order join sp_repair_review r on r.order_id=sp_repair_order.id "
   +"and r.application_id=sp_repair_order.application_id and r.tenant_id=sp_repair_order.tenant_id";
  var parameters=new ArrayList<Object>();
  if(status!=null){sql+=" and r.status=?";parameters.add(status);}
  if(kind!=null){sql+=" and r.kind=?";parameters.add(kind);}
  sql+=" order by r.create_time desc,r.id";
  // 排序固定在审核时间；用户筛选仍按工单字段解析，避免状态字段与审核状态混淆。
  scoped.setSorts(java.util.List.of());
  return helper.select(sql,RepairReviewOrderRow::new,parameters.toArray()).where(scoped);
 }
}
