package org.jetlinks.pro.property.quality.service;
import lombok.RequiredArgsConstructor;import org.springframework.stereotype.Service;import org.jetlinks.pro.property.quality.entity.*;import reactor.core.publisher.*;import java.util.*;import java.nio.charset.StandardCharsets;
/** 工单事务内刷新当前待办，同时保留历史参与；索引查询仍需与工单 AssetsHolder 权限相交。 */
@Service @RequiredArgsConstructor public class QualityParticipation {
 private final QualityNotifications notifications;private final QualityParticipantService participants;private final QualityRecordService records;private final QualityRectificationService rectifications;
 public Mono<Void> refresh(QualityWorkOrderEntity order){return Mono.zip(records.createQuery().where("workOrderId",order.getId()).fetch().collectList(),rectifications.createQuery().where("workOrderId",order.getId()).fetch().collectList()).flatMap(t->{
  Map<String,QualityParticipantEntity> entries=new LinkedHashMap<>();for(var record:t.getT1()){
   add(entries,order,record.getExecutorId(),"related");if(record.getRecordStatus()!=2)add(entries,order,record.getExecutorId(),"inspect");
  }
  for(var rect:t.getT2()){
   for(String id:rect.getExecutorIds()){add(entries,order,id,"related");if(rect.getRectificationStatus()==1||rect.getRectificationStatus()==4)add(entries,order,id,"rectify");}
   add(entries,order,rect.getReviewerId(),"related");if(rect.getRectificationStatus()==2)add(entries,order,rect.getReviewerId(),"review");
  }
  add(entries,order,order.getCreatorId(),"related");
  return participants.createUpdate().set("active",false).where("workOrderId",order.getId()).in("kind",List.of("inspect","rectify","review")).execute().then(participants.save(Flux.fromIterable(entries.values()))).then(notifications.enqueue(order,entries.values()));
 });}
 private static void add(Map<String,QualityParticipantEntity> entries,QualityWorkOrderEntity order,String user,String kind){
  if(user==null)return;var c=new QualityContext(Objects.toString(order.getTenantId()),order.getApplicationId(),"system","系统");var p=QualityScope.initialize(new QualityParticipantEntity(),c);QualityScope.inherit(p,order);p.setId(UUID.nameUUIDFromBytes((order.getId()+":"+user+":"+kind).getBytes(StandardCharsets.UTF_8)).toString().replace("-",""));p.setWorkOrderId(order.getId());p.setUserId(user);p.setKind(kind);p.setActive(true);entries.put(p.getId(),p);
 }
}
