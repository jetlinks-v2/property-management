package org.jetlinks.pro.patrol.service;

import lombok.AllArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.core.trace.FluxTracer;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.domain.RuleEngine;
import org.jetlinks.pro.patrol.entity.PatrolGenerationEntity;
import org.jetlinks.pro.patrol.entity.PatrolPlanEntity;
import org.jetlinks.pro.patrol.entity.PatrolPointEntity;
import org.jetlinks.pro.patrol.entity.PatrolPostEntity;
import org.jetlinks.pro.patrol.entity.PatrolStandardEntity;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.TreeMap;

/** 预览无副作用；批量 INSERT，数据库主键负责多节点防重，不用 upsert 覆盖已有任务。 */
@Service
@AllArgsConstructor
public class PatrolTaskGenerator {
    private final PatrolPlanService plans;
    private final PatrolPointService points;
    private final PatrolPostService posts;
    private final PatrolStandardService standards;
    private final PatrolTaskService tasks;
    private final PatrolLevelService levels;
    private final PatrolCategoryService categories;
    private final ReactiveRepository<PatrolGenerationEntity, String> generations;
    private final TransactionalOperator transaction;

    public Flux<PatrolTaskEntity> preview(String planId, LocalDate day) {
        return AssetsHolder.assertPermission(
                        PatrolAssetType.patrolPlan, planId, CrudAssetPermission.read)
                .then(plans.findById(planId).switchIfEmpty(Mono.error(NotFoundException::new)))
                .flatMap(this::resolveRule)
                .flatMapMany(plan -> expand(plan, day));
    }

    public Flux<PatrolTaskEntity> generate(String planId, LocalDate day) {
        String generationId = groupId(planId, day);
        return AssetsHolder.assertPermission(
                        PatrolAssetType.patrolPlan, planId, CrudAssetPermission.save)
                .then(getOrCreateGeneration(planId, day, generationId))
                .flatMapMany(Flux::fromIterable)
                .as(
                        FluxTracer.create(
                                "/patrol/tasks/generate",
                                builder -> builder.setAttribute("planId", planId)));
    }

    /** 仅供服务自有定时器使用；有租户的计划恢复原技术上下文，平台应用不伪造租户。 */
    Mono<Void> generateScheduled(PatrolPlanEntity plan, LocalDate day) {
        String tenantId = plan.getScopeKey().split("\\|", 2)[0];
        Mono<Void> generation = getOrCreateGeneration(plan.getId(), day, groupId(plan.getId(), day)).then();
        return tenantId.isBlank() ? generation
                : generation.contextWrite(org.jetlinks.pro.tenant.holder.TenantHolder.makeCurrent(tenantId));
    }

    private Mono<List<PatrolTaskEntity>> getOrCreateGeneration(
            String planId, LocalDate day, String id) {
        Mono<List<PatrolTaskEntity>> create =
                Mono.defer(() -> createGeneration(planId, day, id))
                        .as(transaction::transactional)
                        // 冲突事务整体回滚后再读取已提交的封印与任务。
                        .onErrorResume(
                                DatabaseConflicts::isDuplicateKey,
                                error ->
                                        generations
                                                .findById(id)
                                                .switchIfEmpty(Mono.error(error))
                                                .flatMap(marker -> loadGenerated(planId, day)));
        return generations
                .findById(id)
                .flatMap(marker -> loadGenerated(planId, day))
                .switchIfEmpty(create);
    }

    private Mono<List<PatrolTaskEntity>> loadGenerated(String planId, LocalDate day) {
        return tasks.createQuery()
                .where(PatrolTaskEntity::getTaskGroupId, groupId(planId, day))
                .orderBy(SortOrder.asc(PatrolTaskEntity::getStartTime))
                .fetch()
                .take(3000)
                .collectList();
    }

    private Mono<List<PatrolTaskEntity>> createGeneration(String planId, LocalDate day, String id) {
        // 先固定快照，再将组封印与全部物理时段原子写入；主键冲突整笔回滚。
        return plans.findById(planId).switchIfEmpty(Mono.error(NotFoundException::new))
                .flatMap(this::resolveRule)
                .flatMap(plan -> expand(plan, day).collectList().flatMap(rows -> {
                    var group = new PatrolGenerationEntity();
                    group.setId(id);
                    group.setPlanId(planId);
                    group.setBusinessDate(day.toString());
                    group.setBelongDate(day.toString());
                    group.setName(plan.getName());
                    group.setPointId(plan.getPointId());
                    group.setPostId(plan.getPostId());
                    group.setRuleVersion(ruleVersion(plan));
                    group.setPeriodCount(rows.size());
                    group.setRequiredCount(rows.stream().mapToInt(PatrolTaskEntity::getRequiredCount).sum());
                    if (!rows.isEmpty()) {
                        group.setPointName(rows.get(0).getPointName());
                        group.setPostName(rows.get(0).getPostName());
                        group.setStartTime(rows.get(0).getStartTime());
                        group.setEndTime(rows.get(rows.size() - 1).getEndTime());
                    }
                    return generations.insert(group)
                            .then(rows.isEmpty() ? Mono.just(rows)
                                    : tasks.insertBatch(Mono.just(rows)).thenReturn(rows));
                }));
    }

    /** 等级是可复用规则源；每次新日期生成固定快照，不修改已生成组。 */
    private Mono<PatrolPlanEntity> resolveRule(PatrolPlanEntity plan) {
        if (plan.getLevelId() == null || plan.getLevelId().isBlank()) return Mono.just(plan);
        return levels.findById(plan.getLevelId())
                .filter(level -> level.isEnabled() && level.getScopeKey().equals(plan.getScopeKey()))
                .switchIfEmpty(Mono.error(new BusinessException("error.patrol.reference.unavailable")))
                .map(level -> { plan.setRule(level.getRule()); plan.setLevelName(level.getName()); return plan; });
    }

    /** 指派计划 ID 是稳定业务身份，规则版本只作快照，不参与重跑唯一键。 */
    static String groupId(String planId, LocalDate day) {
        return UUID.nameUUIDFromBytes((planId + "|" + day).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String ruleVersion(PatrolPlanEntity plan) {
        var rule = plan.getRule();
        String snapshot = "v2-configured-period|" + plan.getLevelId() + "|" + plan.getPointId() + "|" + plan.getCategoryId() + "|" + plan.getPostId() + "|"
                + plan.getStandardId() + "|" + plan.getRosterPolicy() + "|" + rule.cooldownMinutes()
                + "|" + new TreeMap<>(rule.weekly()) + "|" + new TreeMap<>(rule.dates());
        return UUID.nameUUIDFromBytes(snapshot.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private Flux<PatrolTaskEntity> expand(PatrolPlanEntity plan, LocalDate day) {
        if (!plan.isEnabled()
                || plan.getStandardId() == null || plan.getRule() == null
                || (plan.getEffectiveFrom() != null && day.isBefore(plan.getEffectiveFrom()))
                || (plan.getEffectiveTo() != null && day.isAfter(plan.getEffectiveTo())))
            return Flux.error(new BusinessException("error.patrol.plan.inactive"));
        // 关联对象必须仍有效；权限只控制计划入口，关联对象由保存计划时确定的同应用关系保证。
        return Mono.zip(
                        points.findById(plan.getPointId()),
                        posts.findById(plan.getPostId()),
                        standards.findById(plan.getStandardId()))
                .switchIfEmpty(
                        Mono.error(new BusinessException("error.patrol.reference.unavailable")))
                .flatMapMany(
                        data -> {
                            String categoryId = plan.getCategoryId() == null ? data.getT1().getCategoryId() : plan.getCategoryId();
                            if (!data.getT1().isEnabled()
                                    || !data.getT2().isEnabled()
                                    || !data.getT3().isEnabled()
                                    || !data.getT1().getCategoryIds().contains(categoryId))
                                return Flux.error(
                                        new BusinessException(
                                                "error.patrol.reference.unavailable"));
                            return categories.findById(categoryId)
                                    .map(category -> category.getName()).defaultIfEmpty("")
                                    .flatMapMany(categoryName -> Flux.fromIterable(
                                            RuleEngine.expand(
                                                    plan.getRule(),
                                                    day,
                                                    ZoneId.of("Asia/Shanghai")))
                                    .index()
                                    .map(
                                            indexed ->
                                                    toTask(
                                                            plan,
                                                            data.getT1(),
                                                            data.getT2(),
                                                            data.getT3(),
                                                            indexed.getT2(), indexed.getT1().intValue() + 1, categoryName)));
                        });
    }

    private PatrolTaskEntity toTask(
            PatrolPlanEntity plan,
            PatrolPointEntity point,
            PatrolPostEntity post,
            PatrolStandardEntity standard,
            RuleEngine.Occurrence occurrence, int periodNo, String categoryName) {
        var task = new PatrolTaskEntity();
        task.setId(
                UUID.nameUUIDFromBytes(
                                (plan.getId() + "|" + occurrence.start().toEpochMilli())
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString());
        task.setTaskGroupId(groupId(plan.getId(), occurrence.businessDate()));
        task.setBelongDate(occurrence.businessDate().toString());
        task.setPeriodNo(periodNo);
        task.setRuleVersion(ruleVersion(plan));
        task.setPlanId(plan.getId());
        task.setName(plan.getName());
        task.setPointId(point.getId());
        task.setPointName(point.getName());
        task.setCategoryId(plan.getCategoryId() == null ? point.getCategoryId() : plan.getCategoryId());
        task.setCategoryName(categoryName);
        task.setLevelId(plan.getLevelId());
        task.setLevelName(plan.getLevelName());
        task.setPostId(post.getId());
        task.setPostName(post.getName());
        task.setBusinessDate(occurrence.businessDate().toString());
        task.setStartTime(occurrence.start().toEpochMilli());
        task.setEndTime(occurrence.end().toEpochMilli());
        task.setRequiredCount(occurrence.requiredCount());
        task.setCooldownMinutes(occurrence.cooldownMinutes());
        task.setRosterPolicy(plan.getRosterPolicy());
        task.setStandardSnapshot(List.copyOf(standard.getItems()));
        task.setCreateTime(System.currentTimeMillis());
        return task;
    }
}
