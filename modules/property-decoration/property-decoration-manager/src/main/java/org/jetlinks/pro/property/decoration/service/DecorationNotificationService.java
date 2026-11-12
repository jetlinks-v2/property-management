package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.hswebframework.web.i18n.LocaleUtils;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.decoration.entity.DecorationHistoryEntity;
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
    private final AtomicLong sent=new AtomicLong(),failures=new AtomicLong();
    private volatile long pendingObserved;

    public record Notice(String id,String decorationId,String number,String spaceName,String title,String message,
                         String step,String detailUrl,long createTime) {}

    /** 唯一收件人为聚合登记业主；当前账号、应用、租户及项目范围均由原统一边界注入。 */
    public Mono<PagerResult<Notice>> mine(QueryParamEntity input) {
        return scope.current().flatMap(ctx->scope.query(input,ctx).flatMap(query->{
            query.toNestQuery(n->n.and("userId",ctx.userId()));
            return QueryHelper.queryPager(query,repository::createQuery)
                .map(page->PagerResult.of(page.getTotal(),page.getData().stream().map(DecorationNotificationService::view).toList(),query));
        }));
    }
    private static Notice view(DecorationNotificationEntity row) {
        return new Notice(row.getId(),row.getDecorationId(),row.getNumber(),row.getSpaceName(),row.getTitle(),row.getMessage(),
            row.getStep(),row.getDetailUrl(),row.getCreateTime());
    }

    /** 在原轨迹事务中调用；相同轨迹ID重试不会生成第二条，回滚亦不会留下通知。 */
    Mono<Void> record(DecorationHistoryEntity history) {
        var progress=DecorationProgressNotice.from(history);
        if(progress==null)return Mono.empty();
        return LocaleUtils.currentReactive().defaultIfEmpty(LocaleUtils.current()).flatMap(locale->{
            var row=history.getSnapshot();var notice=new DecorationNotificationEntity();
            notice.setId(history.getId());notice.setDecorationId(row.getId());notice.setTenantId(row.getTenantId());
            notice.setApplicationId(row.getApplicationId());notice.setProjectId(row.getProjectId());notice.setUserId(row.getOwnerUserId());
            notice.setEvent(progress.code());notice.setStep(progress.step());notice.setNumber(row.getNumber());notice.setSpaceName(row.getSpaceName());
            notice.setTitle(LocaleUtils.resolveMessage("message.decoration.notice.title",locale,"装修办理提醒"));
            notice.setMessage(LocaleUtils.resolveMessage("message.decoration.notice."+progress.code(),locale,progress.code()));
            notice.setDetailUrl("/decoration-h5/?side=owner&applicationScope="+encode(row.getApplicationId())
                +"&decorationId="+encode(row.getId())+"&step="+progress.step());
            notice.setCreateTime(history.getCreateTime());notice.setState("pending");notice.setAttempts(0);notice.setNextAttemptTime(history.getCreateTime());
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
                    log.warn("Decoration notice delivery failed: id={}, errorType={}",notice.getId(),type);
                    return repository.createUpdate().set("state","pending").set("lastErrorType",type)
                        .set("nextAttemptTime",System.currentTimeMillis()+retryDelay(attempt)).where("id",notice.getId()).execute().then();
                })).then();
    }
    private Mono<Void> sendCurrent(DecorationNotificationEntity notice) {
        // 发件快照不得在申请删除、归属改变后继续向旧收件人投递；无身份令牌写入通知链接。
        return applications.findById(notice.getDecorationId()).filter(row->!Boolean.TRUE.equals(row.getDeleted())
                &&Objects.equals(row.getTenantId(),notice.getTenantId())&&Objects.equals(row.getApplicationId(),notice.getApplicationId())
                &&Objects.equals(row.getOwnerUserId(),notice.getUserId())&&Objects.equals(row.getProjectId(),notice.getProjectId()))
            .flatMap(row->sender.send(notice).timeout(Duration.ofSeconds(20))
                .then(repository.createUpdate().set("state","sent").set("sentTime",System.currentTimeMillis())
                    .where("id",notice.getId()).execute()).doOnNext(ignore->sent.incrementAndGet()).thenReturn(true))
            .switchIfEmpty(repository.createUpdate().set("state","cancelled").where("id",notice.getId()).execute().thenReturn(false))
            .then().contextWrite(TenantHolder.makeCurrent(notice.getTenantId()));
    }
    static long retryDelay(int attempt){return Math.min(600_000L,5_000L*(1L<<Math.min(attempt,7)));}
    public long getSentCount(){return sent.get();}
    public long getFailureCount(){return failures.get();}
    public long getPendingObserved(){return pendingObserved;}
}
