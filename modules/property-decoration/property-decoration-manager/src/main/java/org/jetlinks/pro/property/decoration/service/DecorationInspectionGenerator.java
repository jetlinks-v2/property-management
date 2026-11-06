package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.decoration.entity.DecorationApplicationEntity;
import org.jetlinks.pro.property.decoration.model.DecorationModels;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 持久化游标生成独立日期任务；共享申请行锁和确定性任务键使多实例、重试均幂等。 */
@Service @RequiredArgsConstructor
public class DecorationInspectionGenerator {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final DecorationApplicationRepository applications;
    private final DecorationTaskRepository tasks;
    private final ReactiveTransactionManager transactions;

    /** 每批最多100份计划，每份最多追赶30日；不加载全部申请或按完成状态阻塞后续日期。 */
    public Mono<Integer> generateBatch(long now) {
        return applications.createQuery().where("deleted", false).isNull("actualEndTime")
            .lte("nextInspectionTime", now).orderBy(org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.asc("nextInspectionTime")).paging(0, 100).fetch()
            .map(DecorationApplicationEntity::getId).concatMap(id -> generateOne(id, now))
            .reduce(0, Integer::sum).as(MonoTracer.create("/property/decoration/inspection-generate"));
    }

    private Mono<Integer> generateOne(String id, long now) {
        return applications.createQuery().where("id", id).forUpdate().fetchOne()
            .filter(row -> !Boolean.TRUE.equals(row.getDeleted()) && row.getActualEndTime() == null
                && row.getNextInspectionTime() != null && row.getNextInspectionTime() <= now)
            .flatMap(row -> generateLocked(row, now).flatMap(count -> {
                var update = applications.createUpdate();
                if (row.getNextInspectionTime() == null) update.setNull("nextInspectionTime");
                else update.set("nextInspectionTime", row.getNextInspectionTime());
                return update.where("id", id).execute().thenReturn(count);
            })).defaultIfEmpty(0)
            .as(TransactionalOperator.create(transactions)::transactional);
    }

    /** 由指派事务或后台申请行锁调用；游标仅在任务全部成功后提交，任一失败整份回滚。 */
    Mono<Integer> generateLocked(DecorationApplicationEntity row, long now) {
        if (row.getNextInspectionTime() > now) return Mono.just(0);
        var plan = row.getInspectionPlan();
        LocalDate first = date(row.getNextInspectionTime());
        LocalDate last = date(Math.min(now, plan.getEndTime()));
        LocalDate limit = first.plusDays(29).isBefore(last) ? first.plusDays(29) : last;
        if (first.isAfter(last)) { row.setNextInspectionTime(null); return Mono.just(0); }
        List<LocalDate> days = first.datesUntil(limit.plusDays(1)).toList();
        return Flux.fromIterable(days).concatMap(day -> createOccurrence(row, plan, day))
            .reduce(0, Integer::sum).doOnNext(count -> row.setNextInspectionTime(limit.isBefore(date(plan.getEndTime()))
                ? start(limit.plusDays(1)) : null));
    }

    private Mono<Integer> createOccurrence(DecorationApplicationEntity row, DecorationModels.TaskDetails plan, LocalDate day) {
        long offset = ChronoUnit.DAYS.between(date(plan.getStartTime()), day);
        var due = plan.getInspectionRoles().stream().filter(role -> offset >= 0 && offset % role.intervalDays() == 0).toList();
        if (due.isEmpty()) return Mono.just(0);
        String id = DecorationApplicationService.identity(row.getId() + ":inspection:" + day);
        return tasks.findById(id).map(existing -> 0).switchIfEmpty(Mono.defer(() -> {
            // 后台只消费申请中经授权冻结的计划，不建立管理员身份或重新扩张项目范围。
            var context = new DecorationScope.Context(row.getTenantId(), row.getApplicationId(), plan.getAssignedById(), plan.getAssignedByName(), null);
            var task = DecorationTaskService.newTask(row, "daily", context); task.setId(id);
            var details = task.getDetails(); details.setInspectionRoles(due); details.setScheduledDate(day.toString());
            details.setStartTime(Math.max(start(day), plan.getStartTime()));
            details.setEndTime(Math.min(start(day.plusDays(1)) - 1, plan.getEndTime()));
            details.setAuditRoleId(plan.getAuditRoleId()); details.setAuditRoleName(plan.getAuditRoleName());
            details.setReviewRoleId(plan.getReviewRoleId()); details.setReviewRoleName(plan.getReviewRoleName()); details.setChecks(plan.getChecks());
            return tasks.insert(task).thenReturn(1);
        }));
    }

    static long dayStart(long time) { return start(date(time)); }
    private static LocalDate date(long time) { return Instant.ofEpochMilli(time).atZone(ZONE).toLocalDate(); }
    private static long start(LocalDate day) { return day.atStartOfDay(ZONE).toInstant().toEpochMilli(); }
}
