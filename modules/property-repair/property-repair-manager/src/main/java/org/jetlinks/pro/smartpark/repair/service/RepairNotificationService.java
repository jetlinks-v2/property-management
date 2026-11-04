package org.jetlinks.pro.smartpark.repair.service;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.i18n.LocaleUtils;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.notify.command.SendUserNotificationCommand;
import org.jetlinks.pro.property.entity.PropertyPersonEntity;
import org.jetlinks.pro.property.service.PropertyPersonService;
import org.jetlinks.pro.smartpark.repair.entity.RepairNotificationEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairProcessEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairReviewEntity;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事务内生成受限收件人快照，提交后由集群任务投递 Cloud 站内信。
 * 发件行用过程ID和收件人唯一标识；远端命令缺少幂等键，丢失成功应答时按至少一次重试。
 */
@Service @Slf4j
public class RepairNotificationService {
    private final RepairNotificationStore store;
    private final RepairOrderService orders;
    private final RepairConfigurationService configurations;
    private final RepairReviewService reviews;
    private final PropertyPersonService people;
    private final RepairRoleService roles;
    private final AtomicLong sent=new AtomicLong(),failures=new AtomicLong();
    private volatile long pendingObserved;
    private volatile String lastErrorType="";

    public RepairNotificationService(RepairNotificationStore store,RepairOrderService orders,
        RepairConfigurationService configurations,RepairReviewService reviews,
        PropertyPersonService people,RepairRoleService roles){
        this.store=store;this.orders=orders;this.configurations=configurations;
        this.reviews=reviews;this.people=people;this.roles=roles;
    }

    Mono<Void> record(RepairProcessEntity process){
        if(Set.of("archive","autoEvaluate").contains(process.getAction()))return Mono.empty();
        return orders.findById(process.getOrderId())
            .filter(order->sameScope(process.getTenantId(),process.getApplicationId(),order))
            .flatMap(order->targets(process,order)
                .concatMap(target->recipients(target,order,context(order)))
                .distinct(Recipient::userId)
                .concatMap(recipient->store.insert(entry(process,order,recipient))).then());
    }

    private Flux<Target> targets(RepairProcessEntity process,RepairOrderEntity order){
        List<Target> targets=new ArrayList<>();
        String action=process.getAction();
        boolean intake=Set.of("create","autoDispatch").contains(action);
        boolean remind=Set.of("urge","timeout").contains(action);
        if(intake||remind){
            if(order.getCandidateRoleId()!=null)targets.add(new Target(null,order.getCandidateRoleId(),"accept"));
            else if(remind&&order.getAssignedUserId()!=null)targets.add(new Target(order.getAssignedUserId(),null,"query"));
            else targets.add(new Target(null,null,"dispatch"));
        }
        if(Set.of("dispatch","transfer","sign","review").contains(action)&&order.getAssignedUserId()!=null)
            targets.add(new Target(order.getAssignedUserId(),null,"query"));
        if(Set.of("accept","grab","follow","finish","callback","review","quote","close","abandonApply").contains(action)
            &&order.getCreatorId()!=null)targets.add(new Target(order.getCreatorId(),null,"query"));
        Flux<Target> callback="finish".equals(action)&&order.getStatus()==8
            ? configurations.findById(order.getCategoryId())
                .filter(category->RepairScheduledService.sameConfigurationScope(order,category))
                .filter(category->RepairFlowConfiguration.enabled(category.getConfiguration()))
                .flatMapMany(category->Mono.justOrEmpty(category.getConfiguration().get("callbackRoleId")).flux())
                .map(role->new Target(null,role.toString(),"callback"))
                .switchIfEmpty(order.getAssignedUserId()==null?Flux.empty():Flux.just(new Target(order.getAssignedUserId(),null,"callback"))) : Flux.empty();
        // 等级、延期、非关和废弃使用已落库的审核角色快照，不从客户端读角色ID。
        Flux<Target> review=Set.of("finish","delay","changeLevel","close","abandonApply").contains(action)
            ? reviews.createQuery().where(RepairReviewEntity::getOrderId,order.getId())
                .and(RepairReviewEntity::getStatus,1).fetch()
                .map(row->new Target(null,row.getRoleId(),"review")) : Flux.empty();
        // 申请人可能既不是发起人也不是责任人，审批结果仍应回到可信申请快照中的员工。
        Object reviewId=process.getDetails()==null?null:process.getDetails().get("reviewId");
        Flux<Target> applicant="review".equals(action)&&reviewId!=null
            ? reviews.findById(reviewId.toString()).filter(row->order.getId().equals(row.getOrderId())
                &&process.getTenantId().equals(row.getTenantId())&&order.getApplicationId().equals(row.getApplicationId()))
                .map(row->new Target(row.getApplicantId(),null,"query")).flux() : Flux.empty();
        return Flux.concat(review,callback,applicant,Flux.fromIterable(targets));
    }

    private Flux<Recipient> recipients(Target target,RepairOrderEntity order,RepairContext context){
        Flux<String> users=target.userId()!=null?Flux.just(target.userId()):
            people.createQuery().where(PropertyPersonEntity::getTenantId,context.tenantId())
                .and(PropertyPersonEntity::getEmployee,true).and(PropertyPersonEntity::getSourceStatus,1)
                .fetch().map(PropertyPersonEntity::getId);
        return users.filterWhen(user->allowed(user,target.roleId(),target.action(),order,context),8)
            .map(user->new Recipient(user,target.roleId(),target.action()));
    }

    private Mono<Boolean> allowed(String userId,String roleId,String action,RepairOrderEntity order,RepairContext context){
        return roleId==null?roles.permitted(userId,action,order,context):roles.eligible(userId,roleId,action,order,context);
    }

    /** 每轮最多100项，租约CAS防止多个节点或上一轮未结束时重复发送。 */
    public Mono<Void> poll(){
        long now=System.currentTimeMillis();
        QueryParamEntity query=QueryParamEntity.newQuery().where("state","in",List.of("pending","sending"))
            .and("nextAttemptTime","lte",now).orderByAsc("nextAttemptTime").orderByAsc("id").getParam();
        query.setPageIndex(0);query.setPageSize(100);
        return store.createQuery().setParam(query).fetch().collectList()
            .doOnNext(rows->pendingObserved=rows.size())
            .flatMapMany(Flux::fromIterable).concatMap(entry->deliver(entry,now)).then()
            .as(MonoTracer.create("/smart-park/repair/inside-mail"));
    }

    private Mono<Void> deliver(RepairNotificationEntity entry,long now){
        int attempts=entry.getAttempts()+1;
        return store.createUpdate().set(RepairNotificationEntity::getState,"sending")
            .set(RepairNotificationEntity::getAttempts,attempts)
            .set(RepairNotificationEntity::getNextAttemptTime,now+60_000)
            .where(RepairNotificationEntity::getId,entry.getId())
            .and(RepairNotificationEntity::getState,entry.getState())
            .and(RepairNotificationEntity::getNextAttemptTime,entry.getNextAttemptTime()).execute()
            .flatMap(changed->changed==1?sendEligible(entry)
                .onErrorResume(error->retry(entry,attempts,error)):Mono.empty());
    }

    private Mono<Void> sendEligible(RepairNotificationEntity entry){
        return orders.findById(entry.getOrderId())
            .filter(order->sameScope(entry.getTenantId(),entry.getApplicationId(),order))
            .filterWhen(order->allowed(entry.getUserId(),entry.getRoleId(),entry.getRequiredAction(),order,context(order)))
            .flatMap(order->command(entry).execute().timeout(Duration.ofSeconds(20))
                .then(store.createUpdate().set(RepairNotificationEntity::getState,"sent")
                    .set(RepairNotificationEntity::getSentTime,System.currentTimeMillis())
                    .where(RepairNotificationEntity::getId,entry.getId()).execute())
                .doOnNext(ignore->sent.incrementAndGet()).thenReturn(true))
            .switchIfEmpty(store.createUpdate().set(RepairNotificationEntity::getState,"cancelled")
                .where(RepairNotificationEntity::getId,entry.getId()).execute().thenReturn(false))
            .then().contextWrite(TenantHolder.makeCurrent(entry.getTenantId()));
    }

    private Mono<Void> retry(RepairNotificationEntity entry,int attempts,Throwable error){
        failures.incrementAndGet();lastErrorType=error.getClass().getSimpleName();
        log.warn("Repair inside-mail delivery failed: entryId={}, errorType={}",entry.getId(),lastErrorType);
        return store.createUpdate().set(RepairNotificationEntity::getState,"pending")
            .set(RepairNotificationEntity::getNextAttemptTime,System.currentTimeMillis()+retryDelay(attempts))
            .set(RepairNotificationEntity::getLastErrorType,lastErrorType)
            .where(RepairNotificationEntity::getId,entry.getId()).execute().then();
    }

    static long retryDelay(int attempts){return Math.min(600_000L,5_000L*(1L<<Math.min(attempts,7)));}
    static boolean sameScope(String tenantId,String applicationId,RepairOrderEntity order){
        return tenantId!=null&&applicationId!=null&&tenantId.equals(order.getExtension("tenantId"))
            &&applicationId.equals(order.getApplicationId());
    }
    private RepairContext context(RepairOrderEntity order){
        return new RepairContext(Objects.toString(order.getExtension("tenantId")),order.getApplicationId(),"system","系统");
    }

    static RepairNotificationEntity entry(RepairProcessEntity process,RepairOrderEntity order,Recipient recipient){
        RepairNotificationEntity entry=new RepairNotificationEntity();
        entry.setId(java.util.UUID.nameUUIDFromBytes((process.getId()+":"+recipient.userId()).getBytes(StandardCharsets.UTF_8)).toString().replace("-",""));
        entry.setProcessId(process.getId());entry.setOrderId(order.getId());entry.setTenantId(process.getTenantId());
        entry.setApplicationId(order.getApplicationId());entry.setUserId(recipient.userId());
        entry.setRoleId(recipient.roleId());entry.setRequiredAction(recipient.action());entry.setAction(process.getAction());
        entry.setState("pending");entry.setAttempts(0);entry.setCreateTime(process.getCreateTime());entry.setNextAttemptTime(process.getCreateTime());
        String actionText=LocaleUtils.resolveMessage("message.repair.notice."+process.getAction(),process.getAction());
        entry.setMessage(LocaleUtils.resolveMessage("message.repair.notice.content","[{0}]工单{1}：{2}",order.getBusinessProjectName(),order.getNumber(),actionText));
        entry.setDetail(Map.of("orderId",order.getId(),"number",order.getNumber(),"applicationId",order.getApplicationId(),
            "businessProjectId",order.getBusinessProjectId(),"action",process.getAction(),"status",order.getStatus()));
        return entry;
    }

    /** 唯一远程边界：固定站内通道及可信目标用户，事件ID用于定位，不用客户端任意收件人。 */
    static SendUserNotificationCommand command(RepairNotificationEntity entry){
        return new SendUserNotificationCommand().withUserId(entry.getUserId()).withChannelProvider("inside-mail")
            .withTopicProvider(RepairNotifySubscriberProvider.ID).withTopicName(LocaleUtils.resolveMessage("message.repair.notifications","报事报修通知"))
            .withSubscribeId(RepairNotifySubscriberProvider.ID).withCode(entry.getAction())
            .withDataId(entry.getProcessId()).withNotifyTime(entry.getCreateTime())
            .withMessage(entry.getMessage()).withDetail(entry.getDetail());
    }

    record Target(String userId,String roleId,String action){}
    record Recipient(String userId,String roleId,String action){}
    public long getSentCount(){return sent.get();}
    public long getFailureCount(){return failures.get();}
    public long getPendingObserved(){return pendingObserved;}
    public String getLastErrorType(){return lastErrorType;}
}
