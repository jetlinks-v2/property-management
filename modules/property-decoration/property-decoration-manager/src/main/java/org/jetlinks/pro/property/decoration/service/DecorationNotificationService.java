package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.hswebframework.web.i18n.LocaleUtils;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.decoration.entity.DecorationHistoryEntity;
import org.jetlinks.pro.property.decoration.entity.DecorationApplicationEntity;
import org.jetlinks.pro.property.decoration.entity.DecorationNotificationEntity;
import org.jetlinks.pro.property.decoration.integration.DecorationInsideMailSender;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** 业务事务内只入队；站内信提交后投递，故障不回滚装修办理，也不冒充消息已送达。 */
@Service @Slf4j @RequiredArgsConstructor
public class DecorationNotificationService {
    private final DecorationNotificationRepository repository;
    private final DecorationApplicationRepository applications;
    private final DecorationScope scope;
    private final DecorationInsideMailSender sender;
    private final DecorationTaskRepository tasks;
    private final DecorationNotificationRecipients recipients;
    private final AtomicLong sent=new AtomicLong(),failures=new AtomicLong();
    private volatile long pendingObserved;

    public record Notice(String id,String decorationId,String number,String spaceName,String title,String message,
                         String step,String detailUrl,long createTime,String taskId,String side) {}

    public Mono<PagerResult<Notice>> mine(QueryParamEntity input) { return mine(input, false); }

    /** 本人通知同时按端别、项目和角色过滤；员工身份仍由基础服务实时核验。 */
    public Mono<PagerResult<Notice>> mine(QueryParamEntity input, boolean staff) {
        return scope.current().flatMap(ctx->scope.query(input,ctx).flatMap(query->{
            restrictRecipient(query,ctx,staff);
            return (staff ? scope.employee().then() : Mono.<Void>empty()).then(QueryHelper.queryPager(query,repository::createQuery))
                .map(page->PagerResult.of(page.getTotal(),page.getData().stream().map(DecorationNotificationService::view).toList(),query));
        }));
    }
    static void restrictRecipient(QueryParamEntity query, DecorationScope.Context ctx, boolean staff) {
        query.toNestQuery(n -> {
            n.and("userId",ctx.userId());
            if(staff) {
                n.and("recipientSide","staff");
                var roleIds=ctx.authentication().getDimensions("role").stream().map(role->role.getId()).toList();
                var role=n.nest().isNull("roleId");
                if(!roleIds.isEmpty())role.or("roleId","in",roleIds);
            } else n.nest().isNull("recipientSide").or("recipientSide","owner");
        });
    }
    private static Notice view(DecorationNotificationEntity row) {
        return new Notice(row.getId(),row.getDecorationId(),row.getNumber(),row.getSpaceName(),row.getTitle(),row.getMessage(),
            row.getStep(),row.getDetailUrl(),row.getCreateTime(),row.getTaskId(),row.getRecipientSide()==null?"owner":row.getRecipientSide());
    }

    /** 在原轨迹事务中调用；相同轨迹ID重试不会生成第二条，回滚亦不会留下通知。 */
    Mono<Void> record(DecorationHistoryEntity history) {
        var progress=DecorationProgressNotice.from(history);
        if(progress==null)return Mono.empty();
        return LocaleUtils.currentReactive().defaultIfEmpty(LocaleUtils.current()).flatMap(locale->{
            var row=history.getSnapshot();var notice=new DecorationNotificationEntity();
            notice.setId(history.getId());notice.setDecorationId(row.getId());notice.setTenantId(row.getTenantId());
            notice.setApplicationId(row.getApplicationId());notice.setProjectId(row.getProjectId());notice.setUserId(row.getOwnerUserId());
            notice.setRecipientSide("owner");notice.setEvent(progress.code());notice.setStep(progress.step());notice.setNumber(row.getNumber());notice.setSpaceName(row.getSpaceName());
            notice.setTitle(LocaleUtils.resolveMessage("message.decoration.notice.title",locale,"装修办理提醒"));
            notice.setMessage(LocaleUtils.resolveMessage("message.decoration.notice."+progress.code(),locale,progress.code()));
            notice.setDetailUrl("/decoration-h5/?side=owner&applicationScope="+encode(row.getApplicationId())
                +"&decorationId="+encode(row.getId())+"&step="+progress.step());
            notice.setCreateTime(history.getCreateTime());notice.setState("pending");notice.setAttempts(0);notice.setNextAttemptTime(history.getCreateTime());
            return repository.findById(notice.getId()).switchIfEmpty(repository.insert(notice).thenReturn(notice)).then();
        });
    }
    /** 与任务变化同事务入队；角色收件人延迟解析，目录故障不回滚业主的整改提交。 */
    Mono<Void> task(DecorationApplicationEntity row, org.jetlinks.pro.property.decoration.entity.DecorationTaskEntity task, String event) {
        return LocaleUtils.currentReactive().defaultIfEmpty(LocaleUtils.current()).flatMap(locale -> {
            var notice=new DecorationNotificationEntity();
            notice.setId(DecorationApplicationService.identity(task.getId()+":"+task.getVersion()+":"+event));
            notice.setDecorationId(row.getId());notice.setTenantId(row.getTenantId());notice.setApplicationId(row.getApplicationId());
            notice.setProjectId(row.getProjectId());notice.setTaskId(task.getId());notice.setTaskVersion(task.getVersion());notice.setEvent(event);
            boolean staff=java.util.Set.of("firstTaskAssigned","secondTaskAssigned","rectificationReview").contains(event);
            notice.setRecipientSide(staff?"staff":"owner");notice.setStep(staff?"task":"rectification");
            notice.setRoleId("rectificationReview".equals(event)?task.getDetails().getReviewRoleId():null);
            notice.setUserId(staff ? notice.getRoleId()==null?task.getAssigneeId():"" : row.getOwnerUserId());
            notice.setDispatcherId(task.getDetails().getAssignedById()!=null?task.getDetails().getAssignedById()
                :row.getInspectionPlan()==null?null:row.getInspectionPlan().getAssignedById());
            notice.setNumber(row.getNumber());notice.setSpaceName(row.getSpaceName());
            notice.setTitle(LocaleUtils.resolveMessage("message.decoration.notice.title",locale,"装修办理提醒"));
            notice.setMessage(LocaleUtils.resolveMessage("message.decoration.notice."+event,locale,event));
            notice.setDetailUrl("/decoration-h5/?side="+notice.getRecipientSide()+"&applicationScope="+encode(row.getApplicationId())
                +"&decorationId="+encode(row.getId())+"&step="+notice.getStep()+"&taskId="+encode(task.getId()));
            notice.setCreateTime(System.currentTimeMillis());notice.setState("pending");notice.setAttempts(0);notice.setNextAttemptTime(notice.getCreateTime());
            return repository.findById(notice.getId()).switchIfEmpty(repository.insert(notice).thenReturn(notice)).then();
        });
    }
    private static String encode(String text){return URLEncoder.encode(text,StandardCharsets.UTF_8);}

    /** 有界数据库分页及租约CAS；远端无幂等收据，成功应答丢失时可能至少投递一次。 */
    public Mono<Void> poll() {
        long now=System.currentTimeMillis();
        var query=QueryParamEntity.newQuery().where("state","in",List.of("pending","sending"))
            .and("nextAttemptTime","lte",now).orderByAsc("nextAttemptTime").orderByAsc("id").getParam();
        query.setPageIndex(0);query.setPageSize(50);
        return repository.createQuery().setParam(query).fetch().collectList().doOnNext(rows->pendingObserved=rows.size())
            .flatMapMany(Flux::fromIterable).concatMap(notice->deliver(notice,now)).then()
            .as(MonoTracer.create("/property/decoration/notifications"));
    }
    private Mono<Void> deliver(DecorationNotificationEntity notice,long now) {
        int attempt=notice.getAttempts()+1;
        return repository.createUpdate().set("state","sending").set("attempts",attempt).set("nextAttemptTime",now+60_000)
            .where("id",notice.getId()).and("state",notice.getState()).and("nextAttemptTime",notice.getNextAttemptTime())
            .execute().filter(n->n==1).flatMap(ignore->sendCurrent(notice)
                .onErrorResume(error->{
                    failures.incrementAndGet();String type=error.getClass().getSimpleName();
                    if(error instanceof ObsoleteRecipientException)return repository.createUpdate().set("state","cancelled").where("id",notice.getId()).execute().then();
                    log.warn("Decoration notice delivery failed: id={}, errorType={}",notice.getId(),type);
                    return repository.createUpdate().set("state","pending").set("lastErrorType",type)
                        .set("nextAttemptTime",System.currentTimeMillis()+retryDelay(attempt)).where("id",notice.getId()).execute().then();
                })).then();
    }
    private Mono<Void> sendCurrent(DecorationNotificationEntity notice) {
        // 发件快照不得在申请删除、归属改变后继续向旧收件人投递；无身份令牌写入通知链接。
        return applications.findById(notice.getDecorationId()).filter(row->!Boolean.TRUE.equals(row.getDeleted())
                &&Objects.equals(row.getTenantId(),notice.getTenantId())&&Objects.equals(row.getApplicationId(),notice.getApplicationId())
                &&Objects.equals(row.getProjectId(),notice.getProjectId()))
            .filterWhen(row -> "staff".equals(notice.getRecipientSide()) ? currentTask(notice).hasElement()
                : Mono.just(Objects.equals(row.getOwnerUserId(),notice.getUserId())))
            .flatMap(row->send(notice).timeout(Duration.ofSeconds(20))
                .then(Mono.defer(() -> {
                    boolean expanded=notice.getUserId().isEmpty();
                    var update=repository.createUpdate().set("state",expanded?"expanded":"sent");
                    if(!expanded)update.set("sentTime",System.currentTimeMillis());
                    return update.where("id",notice.getId()).execute();
                })).doOnNext(ignore->{if(!notice.getUserId().isEmpty())sent.incrementAndGet();}).thenReturn(true))
            .switchIfEmpty(repository.createUpdate().set("state","cancelled").where("id",notice.getId()).execute().thenReturn(false))
            .then().contextWrite(TenantHolder.makeCurrent(notice.getTenantId()));
    }
    private Mono<Void> send(DecorationNotificationEntity notice) {
        if(!"staff".equals(notice.getRecipientSide()))return sender.send(notice);
        if(!notice.getUserId().isEmpty())return recipients.allowed(notice.getUserId(),notice)
            .filter(Boolean.TRUE::equals).flatMap(ignore->sender.send(notice).thenReturn(true))
            .switchIfEmpty(Mono.error(new ObsoleteRecipientException())).then();
        // 一个角色事件先可靠展开为每人独立发件行；单人的失败不会重复向已成功的其他人发信。
        return recipients.roleMembers(notice).concatMap(user -> {
            var child=new DecorationNotificationEntity();
            org.hswebframework.web.bean.FastBeanCopier.copy(notice,child);
            child.setId(DecorationApplicationService.identity(notice.getId()+":"+user));child.setUserId(user);
            child.setState("pending");child.setAttempts(0);child.setNextAttemptTime(System.currentTimeMillis());
            return repository.findById(child.getId()).switchIfEmpty(repository.insert(child).thenReturn(child));
        }).then().onErrorMap(org.hswebframework.web.authorization.exception.AccessDenyException.class, error -> new ObsoleteRecipientException());
    }
    private Mono<org.jetlinks.pro.property.decoration.entity.DecorationTaskEntity> currentTask(DecorationNotificationEntity notice) {
        return tasks.findById(notice.getTaskId()).filter(task -> Objects.equals(task.getDecorationId(),notice.getDecorationId())
            &&Objects.equals(task.getApplicationId(),notice.getApplicationId())&&Objects.equals(task.getTenantId(),notice.getTenantId())
            &&Objects.equals(task.getProjectId(),notice.getProjectId())
            &&Objects.equals(task.getVersion(),notice.getTaskVersion()))
            .filter(task -> notice.getRoleId()!=null ? "waiting_review".equals(task.getStatus())&&Objects.equals(task.getDetails().getReviewRoleId(),notice.getRoleId())
                : "pending".equals(task.getStatus())&&Objects.equals(task.getAssigneeId(),notice.getUserId()));
    }
    private static class ObsoleteRecipientException extends RuntimeException {}
    static long retryDelay(int attempt){return Math.min(600_000L,5_000L*(1L<<Math.min(attempt,7)));}
    public long getSentCount(){return sent.get();}
    public long getFailureCount(){return failures.get();}
    public long getPendingObserved(){return pendingObserved;}
}
