package org.jetlinks.pro.property.quality.service;
import lombok.RequiredArgsConstructor;import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.jetlinks.pro.property.quality.entity.*;
import org.jetlinks.pro.notify.command.SendUserNotificationCommand;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.i18n.LocaleUtils;
import reactor.core.publisher.*;
import java.util.*;import java.nio.charset.StandardCharsets;import java.time.Duration;
/** 持久化通知按收件人的实时角色及工单资产权限复核；失败退避重试，不回滚业务提交。 */
@Service @Slf4j @RequiredArgsConstructor
public class QualityNotifications {
 private final QualityNotificationStore store;private final QualityWorkOrderService orders;private final QualityAssignees assignees;private final QualityExecution execution;
 public Mono<Void> enqueue(QualityWorkOrderEntity order,Collection<QualityParticipantEntity> participants){
  return Flux.fromIterable(participants).filter(p->!p.getKind().equals("related")).concatMap(p->{
   var c=new QualityContext(Objects.toString(order.getTenantId()),order.getApplicationId(),order.getCreatorId(),order.getCreatorName());
   var row=QualityScope.initialize(new QualityNotificationEntity(),c);QualityScope.inherit(row,order);
   row.setId(UUID.nameUUIDFromBytes((order.getId()+":"+order.getVersion()+":"+p.getKind()+":"+p.getUserId()).getBytes(StandardCharsets.UTF_8)).toString().replace("-",""));row.setWorkOrderId(order.getId());row.setUserId(p.getUserId());row.setAction(p.getKind());row.setState("pending");row.setAttempts(0);row.setNextAttemptTime(System.currentTimeMillis());return store.save(row);
  }).then();
 }
 public Mono<Void> poll(){long now=System.currentTimeMillis();return store.createQuery().in("state",List.of("pending","sending")).lte("nextAttemptTime",now).fetch().take(100).concatMap(row->send(row,now)).then();}
 private Mono<Void> send(QualityNotificationEntity row,long now){int attempt=row.getAttempts()+1;
  return store.createUpdate().set("state","sending").set("attempts",attempt).set("nextAttemptTime",now+60000).where("id",row.getId()).and("state",row.getState()).and("nextAttemptTime",row.getNextAttemptTime()).execute().filter(n->n==1)
   .flatMap(n->execution.asUser(row,row.getUserId(),()->orders.findById(row.getWorkOrderId()).filter(o->o.getApplicationId().equals(row.getApplicationId())&&Objects.equals(o.getTenantId(),row.getTenantId()))
    .flatMap(o->assignees.assertEligible(row.getUserId(),o,row.getAction(),null).thenReturn(o)).flatMap(o->new SendUserNotificationCommand().withUserId(row.getUserId()).withChannelProvider("inside-mail").withTopicProvider(QualityNotifySubscriberProvider.ID).withTopicName(LocaleUtils.resolveMessage("message.quality.notifications","品质核查通知")).withSubscribeId(QualityNotifySubscriberProvider.ID).withCode(row.getAction()).withDataId(row.getId()).withNotifyTime(row.getCreateTime()).withMessage(LocaleUtils.resolveMessage("message.quality.todo","项目{0}的品质任务{1}有新的待办",o.getProjectName(),o.getTaskName())).withDetail(Map.of("workOrderId",o.getId(),"applicationId",o.getApplicationId(),"action",row.getAction())).execute().timeout(Duration.ofSeconds(20)))
    .then(store.createUpdate().set("state","sent").where("id",row.getId()).execute())))
   .onErrorResume(AccessDenyException.class,e->store.createUpdate().set("state","cancelled").where("id",row.getId()).execute())
   .onErrorResume(e->{log.warn("Quality notification delivery failed: id={}, type={}",row.getId(),e.getClass().getSimpleName());return store.createUpdate().set("state","pending").set("nextAttemptTime",now+Math.min(600000,5000L*(1L<<Math.min(attempt,7)))).where("id",row.getId()).execute();})
   .then().contextWrite(TenantHolder.makeCurrent(Objects.toString(row.getTenantId())));
 }
}
