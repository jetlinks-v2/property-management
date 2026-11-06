package org.jetlinks.pro.property.quality.service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.jetlinks.pro.property.quality.entity.*;
import reactor.core.publisher.Mono;
import java.util.Map;
/** 业务动作与日志由同一响应式事务提交，日志只允许通过所属资产读取。 */
@Service @RequiredArgsConstructor
public class QualityLog {
 private final QualityLogService logs;
 public Mono<Void> append(QualityEntity parent,String action,QualityContext c,Map<String,Object> details){
  var log=QualityScope.initialize(new QualityLogEntity(),c);QualityScope.inherit(log,parent);
  if(parent instanceof QualityWorkOrderEntity)log.setWorkOrderId(parent.getId());
  if(parent instanceof QualityTaskEntity)log.setTaskId(parent.getId());
  if(parent instanceof QualityRectificationEntity r){log.setWorkOrderId(r.getWorkOrderId());log.setRectificationId(r.getId());}
  log.setAction(action);log.setOperatorId(c.userId());log.setOperatorName(c.userName());log.setDetails(details);
  return logs.insert(log).then();
 }
}
