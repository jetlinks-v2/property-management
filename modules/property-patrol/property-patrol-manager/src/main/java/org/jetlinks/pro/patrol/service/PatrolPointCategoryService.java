package org.jetlinks.pro.patrol.service;

import lombok.AllArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolCategoryEntity;
import org.jetlinks.pro.patrol.entity.PatrolPlanEntity;
import org.jetlinks.pro.patrol.entity.PatrolPointCategoryEntity;
import org.jetlinks.pro.patrol.entity.PatrolPointEntity;
import org.jetlinks.pro.patrol.integration.PatrolScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 分类关系的写入 owner：按物理点位加锁，只停止被取消分类的岗位计划，不改历史记录。 */
@Service
@AllArgsConstructor
public class PatrolPointCategoryService {
    private final ReactiveRepository<PatrolPointCategoryEntity, String> repository;
    private final PatrolPointService points;
    private final PatrolCategoryService categories;
    private final PatrolPlanService plans;
    private final PatrolScope scope;
    private final TransactionalOperator transaction;

    /** 内部读取：调用方已拥有点位读取权限；有任何关系行后，不再使用旧字段兜底。 */
    public Mono<List<String>> categoryIds(PatrolPointEntity point) {
        return rows(point.getId()).map(rows -> rows.isEmpty()
                ? List.of(point.getCategoryId())
                : rows.stream().filter(PatrolPointCategoryEntity::isEnabled)
                        .map(PatrolPointCategoryEntity::getCategoryId).toList());
    }

    public Mono<Void> link(List<String> pointIds, String categoryId) {
        return change(pointIds, categoryId, true);
    }

    public Mono<Void> cancel(List<String> pointIds, String categoryId) {
        return change(pointIds, categoryId, false);
    }

    private Mono<Void> change(List<String> pointIds, String categoryId, boolean enabled) {
        return Mono.defer(() -> {
            // 这是批量写入的唯一 owner；空选和无界请求不得变成无条件操作。
            if (pointIds.isEmpty() || pointIds.size() > 100)
                return Mono.error(new BusinessException("error.patrol.assignment.limit"));
            if (new HashSet<>(pointIds).size() != pointIds.size())
                return Mono.error(new BusinessException("error.patrol.assignment.duplicate"));
            // 关系没有独立资产：修改受点位 save 控制，分类引用受 category read 控制。
            // 取消涉及的计划另检查 plan save；任一对象无权时整个批次回滚。
            return AssetsHolder.assertPermission(Flux.fromIterable(pointIds), PatrolAssetType.patrolPoint,
                            id -> id, CrudAssetPermission.save).then()
                    .then(AssetsHolder.assertPermission(PatrolAssetType.patrolCategory, categoryId, CrudAssetPermission.read))
                    .then(scope.current()).zipWith(ReactiveAuthenticationHolder.get())
                    .flatMap(pair -> checkedCategory(categoryId, pair.getT1().key(), enabled)
                            .thenMany(Flux.fromIterable(pointIds.stream().sorted().toList())
                                    .concatMap(id -> points.createQuery().where("id", id).forUpdate().fetchOne()
                                            .switchIfEmpty(Mono.error(NotFoundException::new))
                                            .flatMap(point -> {
                                                if (!pair.getT1().key().equals(point.getScopeKey())
                                                        || (enabled && !point.isEnabled()))
                                                    return Mono.error(new BusinessException("error.patrol.reference.unavailable"));
                                                return materialize(point, pair.getT2().getUser().getId())
                                                        .then(changeLocked(point, categoryId, enabled,
                                                                pair.getT2().getUser().getId(),
                                                                Objects.requireNonNullElse(pair.getT2().getUser().getName(), "")))
                                                        .then(points.createUpdate().set(PatrolPointEntity::getModifierId, pair.getT2().getUser().getId())
                                                                .set(PatrolPointEntity::getModifierName, Objects.requireNonNullElse(pair.getT2().getUser().getName(), ""))
                                                                .set(PatrolPointEntity::getModifyTime, Math.max(System.currentTimeMillis(),
                                                                        Objects.requireNonNullElse(point.getModifyTime(), 0L) + 1))
                                                                .where(PatrolPointEntity::getId, point.getId()).execute()).then();
                                            }))).then());
        }).as(transaction::transactional);
    }

    private Mono<PatrolCategoryEntity> checkedCategory(String id, String key, boolean mustBeEnabled) {
        return categories.findById(id).switchIfEmpty(Mono.error(NotFoundException::new))
                .flatMap(category -> key.equals(category.getScopeKey()) && (!mustBeEnabled || category.isEnabled())
                        ? Mono.just(category) : Mono.error(new BusinessException("error.patrol.reference.unavailable")));
    }

    private Mono<List<PatrolPointCategoryEntity>> rows(String pointId) {
        return repository.createQuery().where(PatrolPointCategoryEntity::getPointId, pointId)
                .orderBy(org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.asc(PatrolPointCategoryEntity::getCategoryId))
                .fetch().take(10001).collectList().flatMap(rows -> rows.size() > 10000
                        ? Mono.error(new BusinessException("error.patrol.assignment.limit")) : Mono.just(rows));
    }

    /** 在已持有物理点位锁的写事务中迁移已发布数据；原计划 ID 和任务封印不变。 */
    private Mono<Void> materialize(PatrolPointEntity point, String actor) {
        return rows(point.getId()).flatMap(rows -> {
            if (!rows.isEmpty()) return Mono.empty();
            var legacy = relation(point, point.getCategoryId(), actor);
            return repository.insert(legacy).then(plans.createUpdate()
                    .set(PatrolPlanEntity::getCategoryId, point.getCategoryId())
                    .where(PatrolPlanEntity::getPointId, point.getId())
                    .isNull(PatrolPlanEntity::getCategoryId).execute()).then();
        });
    }

    private Mono<Void> changeLocked(PatrolPointEntity point, String categoryId, boolean enabled, String actor, String actorName) {
        return rows(point.getId()).flatMap(rows -> {
            var target = rows.stream().filter(row -> categoryId.equals(row.getCategoryId())).findFirst();
            // 重复取消不会创建伪关系，也不会停止其他分类的计划。
            if (target.isEmpty() && !enabled) return Mono.empty();
            long now = System.currentTimeMillis();
            Mono<Void> write = target.isEmpty()
                    ? repository.insert(relation(point, categoryId, actor)).then()
                    : repository.createUpdate().set(PatrolPointCategoryEntity::isEnabled, enabled)
                            .set(PatrolPointCategoryEntity::getModifierId, actor)
                            .set(PatrolPointCategoryEntity::getModifyTime, now)
                            .where(PatrolPointCategoryEntity::getId, target.get().getId()).execute().then();
            return (enabled ? Mono.<Void>empty() : stopPlans(point.getId(), categoryId, actor, actorName, now))
                    .then(write);
        });
    }

    private Mono<Void> stopPlans(String pointId, String categoryId, String actor, String actorName, long now) {
        return plans.createQuery().where(PatrolPlanEntity::getPointId, pointId)
                .and(PatrolPlanEntity::getCategoryId, categoryId).and(PatrolPlanEntity::isEnabled, true)
                .fetch().take(10001).collectList().flatMap(rows -> {
                    if (rows.size() > 10000) return Mono.error(new BusinessException("error.patrol.assignment.limit"));
                    if (rows.isEmpty()) return Mono.empty();
                    return AssetsHolder.assertPermission(Flux.fromIterable(rows), PatrolAssetType.patrolPlan,
                                    PatrolPlanEntity::getId, CrudAssetPermission.save).then()
                            .then(plans.createUpdate().set(PatrolPlanEntity::isEnabled, false)
                                    .set(PatrolPlanEntity::getModifierId, actor)
                                    .set(PatrolPlanEntity::getModifierName, actorName)
                                    .set(PatrolPlanEntity::getModifyTime, now)
                                    .in(PatrolPlanEntity::getId, rows.stream().map(PatrolPlanEntity::getId).toList())
                                    .execute()).then();
                });
    }

    private static PatrolPointCategoryEntity relation(PatrolPointEntity point, String categoryId, String actor) {
        var row = new PatrolPointCategoryEntity();
        row.setId(UUID.nameUUIDFromBytes((point.getId() + "|" + categoryId).getBytes(StandardCharsets.UTF_8)).toString());
        row.setPointId(point.getId()); row.setCategoryId(categoryId); row.setScopeKey(point.getScopeKey());
        row.setEnabled(true); row.setCreatorId(actor); row.setModifierId(actor);
        row.setCreateTime(System.currentTimeMillis()); row.setModifyTime(row.getCreateTime());
        return row;
    }
}
