package org.jetlinks.pro.patrol.service;

import lombok.AllArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.crud.query.QueryHelper;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolMissedItemEntity;
import org.jetlinks.pro.patrol.entity.PatrolMissedEventEntity;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.jetlinks.pro.patrol.integration.PatrolScope;
import org.jetlinks.pro.patrol.integration.SaasPersonDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import static org.jetlinks.pro.patrol.entity.PatrolMissedItemEntity.Status;

/** 漏检本系统处理；具体次数 CAS、审核计数和追加事件在同一事务提交，不伪造打卡。 */
@Service
@AllArgsConstructor
public class PatrolMissedService {
    private final ReactiveRepository<PatrolMissedItemEntity, String> items;
    private final ReactiveRepository<PatrolMissedEventEntity, String> events;
    private final PatrolTaskService tasks;
    private final PatrolPlanService plans;
    private final PatrolPostService posts;
    private final PatrolRosterService rosters;
    private final PatrolScope scope;
    private final SaasPersonDirectory people;
    private final QueryHelper helper;
    private final TransactionalOperator transaction;
    private static final int LIMIT = 1000;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** PC 组详情展示已申报、未申报及已补录；关联计划权限在读取和生成明细前注入。 */
    public Mono<List<PatrolMissedItemEntity>> group(String groupId) {
        var query = QueryParamEntity.newQuery().is("taskGroupId", groupId).getParam();
        query.setPaging(false);
        return AssetsHolder.injectQueryParam(query, PatrolAssetType.patrolPlan, "planId")
                .flatMapMany(tasks::query).take(LIMIT + 1L).collectList()
                .flatMap(rows -> materialize(rows).then(readItems(query)))
                .as(MonoTracer.create("/patrol/missed/group"));
    }

    /** 手机端仍取当前应用可读计划，仅显示本人岗位或有重叠班次的历史任务。 */
    public Mono<List<PatrolMissedItemEntity>> mine(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || to.isAfter(from.plusDays(30))) return Mono.error(failure("range"));
        return ReactiveAuthenticationHolder.get().flatMap(auth -> scope.current().flatMap(current -> {
            var query = QueryParamEntity.newQuery().is("scopeKey", current.key()).getParam();
            query.setPaging(false);
            return people.assertMembers(List.of(auth.getUser().getId()))
                    .then(AssetsHolder.injectQueryParam(query, PatrolAssetType.patrolPlan, "id"))
                    .flatMapMany(plans::query).take(LIMIT + 1L).collectList()
                    .flatMapMany(rows -> rows.size() > LIMIT ? Flux.error(failure("too_many")) : Flux.fromIterable(rows))
                    .map(plan -> plan.getId()).collectList().flatMap(ids -> {
                        if (ids.isEmpty()) return Mono.just(List.<PatrolMissedItemEntity>of());
                        var taskQuery = QueryParamEntity.newQuery().in("planId", ids)
                                .lt("endTime", System.currentTimeMillis()).getParam();
                        taskQuery.setPaging(false);
                        // 漏检日期按明细生成时间筛选；先同步当前应用未封印任务，不能用任务结束日代替。
                        var unsynced = taskQuery.clone(); unsynced.toNestQuery().isNull("missedInitialized");
                        return tasks.query(unsynced).concatMap(task -> materialize(List.of(task))).then(
                            readItems(QueryParamEntity.newQuery().in("planId", ids)
                                .gte("createTime", from.atStartOfDay(ZONE).toInstant().toEpochMilli())
                                .lt("createTime", to.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli()).getParam()))
                            .flatMap(rows -> Flux.fromIterable(rows).map(PatrolMissedItemEntity::getTaskId).distinct()
                                .flatMap(taskId -> tasks.findById(taskId).filterWhen(task -> mobileAccess(task, auth.getUser().getId())))
                                .map(PatrolTaskEntity::getId).collect(java.util.stream.Collectors.toSet())
                                .map(allowed -> rows.stream().filter(row -> allowed.contains(row.getTaskId())).toList()));
                    });
        })).as(MonoTracer.create("/patrol/missed/mine"));
    }

    private Mono<List<PatrolMissedItemEntity>> readItems(QueryParamEntity query) {
        query.setPaging(false);
        return helper.select(PatrolMissedItemEntity.class).all(PatrolMissedItemEntity.class)
                .as(PatrolTaskEntity::getPointName, PatrolMissedItemEntity::setPointName)
                .as(PatrolTaskEntity::getPostName, PatrolMissedItemEntity::setPostName)
                .as(PatrolTaskEntity::getStartTime, PatrolMissedItemEntity::setPeriodStartTime)
                .as(PatrolTaskEntity::getEndTime, PatrolMissedItemEntity::setPeriodEndTime)
                .from(PatrolMissedItemEntity.class)
                .leftJoin(PatrolTaskEntity.class, join -> join.is(PatrolTaskEntity::getId, PatrolMissedItemEntity::getTaskId))
                .where(query).orderByAsc(PatrolTaskEntity::getStartTime).orderByAsc(PatrolMissedItemEntity::getTaskId)
                .orderByAsc(PatrolMissedItemEntity::getOccurrenceNo).fetch().take(LIMIT + 1L).collectList()
                .flatMap(rows -> rows.size() > LIMIT ? Mono.error(failure("too_many")) : Mono.just(rows));
    }

    /** 任务的封印 CAS 与漏检明细批量插入同一事务，多节点竞争只能生成一次。 */
    private Mono<Void> materialize(List<PatrolTaskEntity> rows) {
        if (rows.size() > LIMIT) return Mono.error(failure("too_many"));
        return Flux.fromIterable(rows).filter(task -> task.getEndTime() < System.currentTimeMillis()
                && !Boolean.TRUE.equals(task.getMissedInitialized()))
                .concatMap(task -> Mono.defer(() -> {
                    int missing = task.getRequiredCount() - task.getCompletedCount();
                    if (missing > LIMIT) return Mono.error(failure("too_many"));
                    long now = System.currentTimeMillis();
                    var additions = new java.util.ArrayList<PatrolMissedItemEntity>();
                    for (int no = task.getCompletedCount() + 1; no <= task.getRequiredCount(); no++) {
                        var item = new PatrolMissedItemEntity();
                        item.setId(stableId(task.getId() + "|" + no)); item.setTaskId(task.getId());
                        item.setTaskGroupId(task.getTaskGroupId()); item.setPlanId(task.getPlanId());
                        item.setOccurrenceNo(no); item.setCreateTime(now); additions.add(item);
                    }
                    return tasks.createUpdate().set(PatrolTaskEntity::getMissedInitialized, true)
                            .where(PatrolTaskEntity::getId, task.getId()).isNull(PatrolTaskEntity::getMissedInitialized)
                            .execute().flatMap(changed -> changed == 0 || additions.isEmpty()
                                    ? Mono.empty() : items.insertBatch(additions).then());
                }).as(transaction::transactional)).then();
    }

    /** 服务自有后台任务仅同步截止后的任务；无用户数据出口，不复用用户授权查询。 */
    Mono<Void> synchronizeEnded() {
        return tasks.createQuery().isNull(PatrolTaskEntity::getMissedInitialized)
                .lt(PatrolTaskEntity::getEndTime, System.currentTimeMillis()).fetch().limitRate(64)
                .concatMap(task -> materialize(List.of(task)), 1).then()
                .as(MonoTracer.create("/patrol/missed/synchronize"));
    }

    public Mono<Integer> apply(List<String> ids, String reason, boolean mobile) {
        String text = reason.trim();
        return ReactiveAuthenticationHolder.get().flatMap(auth -> {
            Flux<String> unique = Flux.fromIterable(ids).distinct().sort();
            return unique.<Integer>concatMap(id -> authorizedItem(id, auth, mobile).flatMap(item -> {
                if (item.getStatus() != Status.none && item.getStatus() != Status.rejected && item.getStatus() != Status.withdrawn)
                    return Mono.<Integer>error(failure("state"));
                String applicationId = UUID.randomUUID().toString();
                long now = System.currentTimeMillis();
                return items.createUpdate().set(PatrolMissedItemEntity::getStatus, Status.pending)
                        .set(PatrolMissedItemEntity::getApplicationId, applicationId)
                        .set(PatrolMissedItemEntity::getApplicantId, auth.getUser().getId())
                        .set(PatrolMissedItemEntity::getApplicantName, auth.getUser().getName())
                        .set(PatrolMissedItemEntity::getReason, text).set(PatrolMissedItemEntity::getApplyTime, now)
                        .setNull(PatrolMissedItemEntity::getAuditorId).setNull(PatrolMissedItemEntity::getAuditorName)
                        .setNull(PatrolMissedItemEntity::getAuditTime).setNull(PatrolMissedItemEntity::getAuditRemark)
                        .set(PatrolMissedItemEntity::getRevision, item.getRevision() + 1)
                        .where(PatrolMissedItemEntity::getId, id).and(PatrolMissedItemEntity::getRevision, item.getRevision())
                        .execute().flatMap(changed -> changed == 1
                            ? append(item, applicationId, "apply", text, now, auth) : Mono.error(failure("concurrent")));
            })).reduce(0, Integer::sum).as(transaction::transactional);
        }).as(MonoTracer.create("/patrol/missed/apply"));
    }

    /** 请求携带当前 applicationId，防止旧待审窗口误审核驳回后重新提交的新申请。 */
    public record Decision(@jakarta.validation.constraints.NotBlank String itemId, @jakarta.validation.constraints.NotBlank String applicationId) {}
    public Mono<Integer> audit(List<Decision> decisions, boolean approved, String remark) {
        String text = remark == null ? "" : remark.trim();
        if (!approved && text.isEmpty()) return Mono.error(failure("reject_reason"));
        return ReactiveAuthenticationHolder.get().flatMap(auth -> Flux.fromIterable(decisions)
                .distinct().sort(java.util.Comparator.comparing(Decision::itemId))
                .concatMap(decision -> authorizedItem(decision.itemId(), auth, false).flatMap(item -> {
                    Status target = approved ? Status.approved : Status.rejected;
                    if (!decision.applicationId().equals(item.getApplicationId())) return Mono.<Integer>error(failure("state"));
                    if (item.getStatus() == target) return Mono.just(1);
                    if (item.getStatus() != Status.pending) return Mono.<Integer>error(failure("state"));
                    long now = System.currentTimeMillis();
                    return items.createUpdate().set(PatrolMissedItemEntity::getStatus, target)
                            .set(PatrolMissedItemEntity::getAuditorId, auth.getUser().getId())
                            .set(PatrolMissedItemEntity::getAuditorName, auth.getUser().getName())
                            .set(PatrolMissedItemEntity::getAuditRemark, text).set(PatrolMissedItemEntity::getAuditTime, now)
                            .set(PatrolMissedItemEntity::getRevision, item.getRevision() + 1)
                            .where(PatrolMissedItemEntity::getId, item.getId())
                            .and(PatrolMissedItemEntity::getRevision, item.getRevision()).execute()
                            .flatMap(changed -> changed == 1 ? Mono.just(1) : Mono.error(failure("concurrent")))
                            .then(approved ? incrementCompletion(item.getTaskId()) : Mono.just(1))
                            .then(append(item, item.getApplicationId(), approved ? "approve" : "reject", text, now, auth));
                })).reduce(0, Integer::sum).as(transaction::transactional))
                .as(MonoTracer.create("/patrol/missed/audit"));
    }

    public Mono<Integer> withdraw(String id, String applicationId, boolean mobile) {
        return ReactiveAuthenticationHolder.get().flatMap(auth -> authorizedItem(id, auth, mobile).flatMap(item -> {
            if (!auth.getUser().getId().equals(item.getApplicantId())) return Mono.error(new AccessDenyException.NoStackTrace());
            if (!applicationId.equals(item.getApplicationId())) return Mono.<Integer>error(failure("state"));
            if (item.getStatus() == Status.withdrawn) return Mono.just(1);
            if (item.getStatus() != Status.pending) return Mono.<Integer>error(failure("state"));
            return items.createUpdate().set(PatrolMissedItemEntity::getStatus, Status.withdrawn)
                    .set(PatrolMissedItemEntity::getRevision, item.getRevision() + 1)
                    .where(PatrolMissedItemEntity::getId, id).and(PatrolMissedItemEntity::getRevision, item.getRevision())
                    .execute().flatMap(changed -> changed == 1
                        ? append(item, applicationId, "withdraw", "", System.currentTimeMillis(), auth)
                        : Mono.error(failure("concurrent")));
        }).as(transaction::transactional));
    }

    public Flux<PatrolMissedEventEntity> history(String id, boolean mobile) {
        return ReactiveAuthenticationHolder.get().flatMap(auth -> authorizedItem(id, auth, mobile))
                .flatMapMany(item -> events.createQuery().where(PatrolMissedEventEntity::getItemId, id)
                        .orderBy(SortOrder.asc(PatrolMissedEventEntity::getCreateTime), SortOrder.asc(PatrolMissedEventEntity::getId)).fetch());
    }

    private Mono<PatrolMissedItemEntity> authorizedItem(String id, Authentication auth, boolean mobile) {
        return items.findById(id).switchIfEmpty(Mono.error(NotFoundException::new)).flatMap(item ->
            AssetsHolder.assertPermission(PatrolAssetType.patrolPlan, item.getPlanId(), CrudAssetPermission.read)
                .then(tasks.findById(item.getTaskId()).switchIfEmpty(Mono.error(NotFoundException::new)))
                .flatMap(task -> {
                    if (task.getEndTime() >= System.currentTimeMillis()) return Mono.error(failure("not_due"));
                    return mobile ? scope.current().flatMap(current -> plans.findById(task.getPlanId())
                            .filter(plan -> current.key().equals(plan.getScopeKey()))
                            .switchIfEmpty(Mono.error(new AccessDenyException.NoStackTrace()))
                            .then(people.assertMembers(List.of(auth.getUser().getId())))
                            .then(mobileAccess(task, auth.getUser().getId()))
                            .flatMap(allowed -> allowed ? Mono.just(item) : Mono.error(new AccessDenyException.NoStackTrace())))
                        : Mono.just(item);
                }));
    }

    private Mono<Boolean> mobileAccess(PatrolTaskEntity task, String userId) {
        return posts.findById(task.getPostId()).map(post -> post.getUserIds().contains(userId)).defaultIfEmpty(false)
                .flatMap(member -> member ? Mono.just(true) : plans.findById(task.getPlanId()).flatMap(plan ->
                    rosters.createQuery().where(org.jetlinks.pro.patrol.entity.PatrolRosterEntity::getScopeKey, plan.getScopeKey())
                        .and(org.jetlinks.pro.patrol.entity.PatrolRosterEntity::getUserId, userId)
                        .and(org.jetlinks.pro.patrol.entity.PatrolRosterEntity::isRest, false)
                        .and(org.jetlinks.pro.patrol.entity.PatrolRosterEntity::isEnabled, true)
                        .lt(org.jetlinks.pro.patrol.entity.PatrolRosterEntity::getStartTime, task.getEndTime())
                        .gt(org.jetlinks.pro.patrol.entity.PatrolRosterEntity::getEndTime, task.getStartTime()).count().map(count -> count > 0))
                    .defaultIfEmpty(false));
    }

    private Mono<Integer> incrementCompletion(String taskId) {
        return tasks.findById(taskId).flatMap(task -> {
            if (task.getCompletedCount() >= task.getRequiredCount()) return Mono.<Integer>error(failure("state"));
            return tasks.createUpdate().set(PatrolTaskEntity::getCompletedCount, task.getCompletedCount() + 1)
                    .where(PatrolTaskEntity::getId, taskId).and(PatrolTaskEntity::getCompletedCount, task.getCompletedCount())
                    .execute().flatMap(changed -> changed == 1 ? Mono.just(1) : Mono.error(failure("concurrent")));
        });
    }

    private Mono<Integer> append(PatrolMissedItemEntity item, String applicationId, String action, String remark, long now, Authentication auth) {
        var event = new PatrolMissedEventEntity();
        event.setId(stableId(applicationId + "|" + action)); event.setItemId(item.getId());
        event.setApplicationId(applicationId); event.setAction(action); event.setRemark(remark);
        event.setActorId(auth.getUser().getId()); event.setActorName(auth.getUser().getName()); event.setCreateTime(now);
        return events.insert(event);
    }
    private static String stableId(String key) { return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString(); }
    private static BusinessException failure(String code) { return new BusinessException("error.patrol.missed." + code); }
}
