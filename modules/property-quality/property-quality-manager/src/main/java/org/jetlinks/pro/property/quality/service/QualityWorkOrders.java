package org.jetlinks.pro.property.quality.service;
import lombok.RequiredArgsConstructor;import org.springframework.stereotype.Service;import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.NotFoundException;import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.quality.asset.QualityAssetType;import org.jetlinks.pro.property.quality.entity.*;import reactor.core.publisher.*;
import java.util.*;
/** 工单、子记录、草稿及日志的读取入口统一复用工单资产权限。 */
@Service @RequiredArgsConstructor public class QualityWorkOrders {
 private final QualityWorkOrderService orders;private final QualityRecordService records;private final QualityRectificationService rectifications;
 private final QualityDraftService drafts;private final QualityLogService logs;private final QualityScope scope;
 public Mono<QueryParamEntity> query(QueryParamEntity q){return scope.query(q,QualityAssetType.qualityWorkOrder,"id");}
 public Mono<QueryParamEntity> personal(QueryParamEntity q,String mode){
  if(mode==null||mode.equals("pool"))return query(q);
  QualityScope.require(Set.of("inspect","rectify","review","related","completed").contains(mode),"list_mode");
  return scope.current().flatMap(c->{var sub=QueryParamEntity.newQuery().where("userId",c.userId()).and("kind",mode.equals("completed")?"related":mode).and("active",true).and("deleted",false).and("applicationId",c.applicationId()).getParam();
   var param=q.clone().toNestQuery().and("id",QualityParticipantTermBuilder.TERM_TYPE,sub.getTerms()).getParam();if(mode.equals("completed"))param.toQuery().and("workOrderStatus",4);return query(param);
  });
 }
 public Mono<QualityWorkOrderEntity> read(String id){return orders.findById(id).switchIfEmpty(Mono.error(new NotFoundException())).flatMap(r->scope.assertRecord(r,QualityAssetType.qualityWorkOrder,id,CrudAssetPermission.read).thenReturn(r));}
 public Mono<QualityWorkOrderEntity> writable(String id){return orders.findById(id).switchIfEmpty(Mono.error(new NotFoundException())).flatMap(r->scope.assertRecord(r,QualityAssetType.qualityWorkOrder,id,CrudAssetPermission.save).thenReturn(r));}
 public Flux<QualityRecordEntity> records(String id){return read(id).thenMany(records.createQuery().where("workOrderId",id).and("deleted",false).orderBy(org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.asc("sortOrder")).orderBy(org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.asc("id")).fetch());}
 public Mono<Map<String,Object>> detail(String id){return read(id).flatMap(order->scope.current().flatMap(c->Mono.zip(
  records.createQuery().where("workOrderId",id).and("deleted",false).orderBy(org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.asc("sortOrder")).fetch().collectList(),
  rectifications.createQuery().where("workOrderId",id).and("deleted",false).orderBy(org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.asc("createTime")).fetch().collectList(),
  logs.createQuery().where("workOrderId",id).and("deleted",false).orderBy(org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.asc("createTime")).fetch().take(1000).collectList(),
  drafts.createQuery().where("workOrderId",id).and("executorId",c.userId()).fetchOne().map(QualityDraftEntity::getContent).defaultIfEmpty(Map.of())
 ).map(t->Map.of("workOrder",order,"records",t.getT1(),"rectifications",t.getT2(),"history",t.getT3(),"draft",t.getT4(),"currentUserId",c.userId()))));}
 public Mono<QualityRectificationEntity> rectification(String id){return rectifications.findById(id).switchIfEmpty(Mono.error(new NotFoundException())).flatMap(r->writable(r.getWorkOrderId()).thenReturn(r));}
}
