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

/** 预览无副作用；批量 INSERT，数据库主键负责多节点防重，不用 upsert 覆盖已有任务。 */
@Service
@AllArgsConstructor
public class PatrolTaskGenerator {
    private final PatrolPlanService plans;
    private final PatrolPointService points;
    private final PatrolPostService posts;
    private final PatrolStandardService standards;
    private final PatrolTaskService tasks;
    private final ReactiveRepository<PatrolGenerationEntity, String> generations;
    private final TransactionalOperator transaction;

    public Flux<PatrolTaskEntity> preview(String planId, LocalDate day) {
        return AssetsHolder.assertPermission(
                        PatrolAssetType.patrolPlan, planId, CrudAssetPermission.read)
                .then(plans.findById(planId).switchIfEmpty(Mono.error(NotFoundException::new)))
                .flatMapMany(plan -> expand(plan, day));
    }

    public Flux<PatrolTaskEntity> generate(String planId, LocalDate day) {
        String generationId =
                UUID.nameUUIDFromBytes((planId + "|" + day).getBytes(StandardCharsets.UTF_8))
                        .toString();
        return AssetsHolder.assertPermission(
                        PatrolAssetType.patrolPlan, planId, CrudAssetPermission.save)
                .then(getOrCreateGeneration(planId, day, generationId))
                .flatMapMany(Flux::fromIterable)
                .as(
                        FluxTracer.create(
                                "/patrol/tasks/generate",
                                builder -> builder.setAttribute("planId", planId)));
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
                .where(PatrolTaskEntity::getPlanId, planId)
                .and(PatrolTaskEntity::getBusinessDate, day.toString())
                .orderBy(SortOrder.asc(PatrolTaskEntity::getStartTime))
                .fetch()
                .take(3000)
                .collectList();
    }

    private Mono<List<PatrolTaskEntity>> createGeneration(String planId, LocalDate day, String id) {
        var marker = new PatrolGenerationEntity();
        marker.setId(id);
        marker.setPlanId(planId);
        marker.setBusinessDate(day.toString());
        // 单计划单业务日，规则边界限制下最多 2880 个发生项；空日期也封印，避免之后被悄悄补巡。
        return generations
                .insert(marker)
                .then(plans.findById(planId).switchIfEmpty(Mono.error(NotFoundException::new)))
                .flatMapMany(plan -> expand(plan, day))
                .collectList()
                .flatMap(
                        rows ->
                                rows.isEmpty()
                                        ? Mono.just(rows)
                                        : tasks.insertBatch(Mono.just(rows)).thenReturn(rows));
    }

    private Flux<PatrolTaskEntity> expand(PatrolPlanEntity plan, LocalDate day) {
        if (!plan.isEnabled()
                || day.isBefore(plan.getEffectiveFrom())
                || day.isAfter(plan.getEffectiveTo()))
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
                            if (!data.getT1().isEnabled()
                                    || !data.getT2().isEnabled()
                                    || !data.getT3().isEnabled())
                                return Flux.error(
                                        new BusinessException(
                                                "error.patrol.reference.unavailable"));
                            return Flux.fromIterable(
                                            RuleEngine.expand(
                                                    plan.getRule(),
                                                    day,
                                                    ZoneId.of("Asia/Shanghai")))
                                    .map(
                                            occurrence ->
                                                    toTask(
                                                            plan,
                                                            data.getT1(),
                                                            data.getT2(),
                                                            data.getT3(),
                                                            occurrence));
                        });
    }

    private PatrolTaskEntity toTask(
            PatrolPlanEntity plan,
            PatrolPointEntity point,
            PatrolPostEntity post,
            PatrolStandardEntity standard,
            RuleEngine.Occurrence occurrence) {
        var task = new PatrolTaskEntity();
        task.setId(
                UUID.nameUUIDFromBytes(
                                (plan.getId() + "|" + occurrence.start().toEpochMilli())
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString());
        task.setPlanId(plan.getId());
        task.setName(plan.getName());
        task.setPointId(point.getId());
        task.setPointName(point.getName());
        task.setPostId(post.getId());
        task.setPostName(post.getName());
        task.setBusinessDate(occurrence.businessDate().toString());
        task.setStartTime(occurrence.start().toEpochMilli());
        task.setEndTime(occurrence.end().toEpochMilli());
        task.setRequiredCount(occurrence.requiredCount());
        task.setCooldownMinutes(plan.getRule().cooldownMinutes());
        task.setRosterPolicy(plan.getRosterPolicy());
        task.setStandardSnapshot(List.copyOf(standard.getItems()));
        task.setCreateTime(System.currentTimeMillis());
        return task;
    }
}
