package org.jetlinks.pro.patrol.service;

import lombok.AllArgsConstructor;

import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolConfigEntity;
import org.jetlinks.pro.patrol.entity.PatrolPlanEntity;
import org.jetlinks.pro.patrol.entity.PatrolPointEntity;
import org.jetlinks.pro.patrol.integration.PatrolScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 岗位指派与规则配置分别保存；所有选中点位在同一事务中替换，不改写任务快照。 */
@Service
@AllArgsConstructor
public class PatrolPointAssignmentService {
    private final PatrolPointService points;
    private final PatrolPlanService plans;
    private final PatrolConfigurationValidator validator;
    private final PatrolScope scope;
    private final TransactionalOperator transaction;

    public record Rule(String postId, String levelId, String standardId) {}
    public record Detail(String pointId, List<PatrolPlanEntity> assignments) {}

    public Mono<List<Detail>> detail(List<String> pointIds) {
        return authorizePoints(pointIds, CrudAssetPermission.read)
                .then(load(pointIds))
                .map(rows -> rows.stream().filter(PatrolPlanEntity::isEnabled).toList())
                .flatMap(rows -> authorizePlans(rows, CrudAssetPermission.read).thenReturn(rows))
                .map(rows -> pointIds.stream().map(id -> new Detail(id,
                        rows.stream().filter(row -> row.isEnabled() && id.equals(row.getPointId())).toList())).toList());
    }

    public Mono<Void> assign(List<String> pointIds, List<String> postIds) {
        return replace(pointIds, postIds, null);
    }

    public Mono<Void> configure(List<String> pointIds, List<Rule> rules) {
        return replace(pointIds, rules.stream().map(Rule::postId).toList(), rules);
    }

    private Mono<Void> replace(List<String> pointIds, List<String> postIds, List<Rule> rules) {
        return Mono.defer(() -> {
            if (new HashSet<>(pointIds).size() != pointIds.size()
                    || new HashSet<>(postIds).size() != postIds.size())
                return Mono.error(new BusinessException("error.patrol.assignment.duplicate"));
            // 新入口同时控制点位配置和已有计划资产；混入一个越权对象则整体拒绝。
            return authorizePoints(pointIds, CrudAssetPermission.save)
                    .then(points.createQuery().in(PatrolPointEntity::getId, pointIds).fetch().collectList())
                    .zipWith(ReactiveAuthenticationHolder.get())
                    .flatMap(pair -> scope.current().flatMap(current -> {
                        var selected = pair.getT1();
                        if (selected.size() != pointIds.size() || selected.stream()
                                .anyMatch(point -> !point.isEnabled() || !current.key().equals(point.getScopeKey())))
                            return Mono.error(new BusinessException("error.patrol.reference.unavailable"));
                        String userId = pair.getT2().getUser().getId();
                        String userName = Objects.requireNonNullElse(pair.getT2().getUser().getName(), "");
                        long now = System.currentTimeMillis();
                        // 按稳定顺序 CAS 点位，串行化同一点位的批量覆盖；失败时所有写入回滚。
                        return Flux.fromIterable(selected.stream().sorted(Comparator.comparing(PatrolPointEntity::getId)).toList())
                                .concatMap(point -> lock(point, userId, userName, now)).then(load(pointIds))
                                .flatMap(existing -> authorizePlans(existing, CrudAssetPermission.save)
                                        .then(prepare(selected, existing, postIds, rules, userId, userName, now))
                                        .flatMap(rows -> persist(existing, rows, userId, userName, now, rules != null)));
                    })).then().as(transaction::transactional);
        });
    }

    private Mono<Void> authorizePoints(List<String> ids, CrudAssetPermission permission) {
        return AssetsHolder.assertPermission(Flux.fromIterable(ids), PatrolAssetType.patrolPoint,
                id -> id, permission).then();
    }

    private Mono<Void> authorizePlans(List<PatrolPlanEntity> rows, CrudAssetPermission permission) {
        return AssetsHolder.assertPermission(Flux.fromIterable(rows), PatrolAssetType.patrolPlan,
                PatrolPlanEntity::getId, permission).then();
    }

    private Mono<List<PatrolPlanEntity>> load(List<String> ids) {
        return plans.createQuery().in(PatrolPlanEntity::getPointId, ids)
                .orderBy(org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.asc(PatrolPlanEntity::getSortIndex),
                        org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.asc(PatrolPlanEntity::getId))
                .fetch().take(10001).collectList().flatMap(rows -> rows.size() > 10000
                        ? Mono.error(new BusinessException("error.patrol.assignment.limit")) : Mono.just(rows));
    }

    private Mono<Void> lock(PatrolPointEntity point, String userId, String userName, long now) {
        var update = points.createUpdate().set(PatrolPointEntity::getModifierId, userId).set(PatrolPointEntity::getModifierName, userName)
                .set(PatrolPointEntity::getModifyTime, Math.max(now, Objects.requireNonNullElse(point.getModifyTime(), 0L) + 1))
                .where(PatrolPointEntity::getId, point.getId());
        if (point.getModifyTime() == null) update.isNull(PatrolPointEntity::getModifyTime);
        else update.and(PatrolPointEntity::getModifyTime, point.getModifyTime());
        return update.execute().flatMap(count -> count == 1 ? Mono.empty()
                : Mono.error(new BusinessException("error.patrol.assignment.changed")));
    }

    private Mono<List<PatrolPlanEntity>> prepare(List<PatrolPointEntity> selected, List<PatrolPlanEntity> existing,
                                                List<String> postIds, List<Rule> rules, String userId, String userName, long now) {
        List<PatrolPlanEntity> replacements = new ArrayList<>();
        Map<String, PatrolPlanEntity> current = new HashMap<>();
        for (var row : existing) {
            String key = row.getPointId() + "|" + row.getPostId();
            var previous = current.get(key);
            if (previous != null && previous.isEnabled() && row.isEnabled())
                return Mono.error(new BusinessException("error.patrol.assignment.duplicate_plan"));
            if (previous == null || row.isEnabled()) current.put(key, row);
        }
        for (var point : selected) {
            if (rules != null) {
                var assigned = existing.stream().filter(row -> row.isEnabled() && point.getId().equals(row.getPointId()))
                        .map(PatrolPlanEntity::getPostId).collect(java.util.stream.Collectors.toSet());
                if (assigned.isEmpty() || !assigned.equals(new HashSet<>(postIds)))
                    return Mono.error(new BusinessException("error.patrol.assignment.posts_differ"));
            }
            for (int i = 0; i < postIds.size(); i++) {
                String key = point.getId() + "|" + postIds.get(i);
                var old = current.get(key);
                var row = old == null ? new PatrolPlanEntity() : old;
                if (old == null) {
                    row.setId(UUID.nameUUIDFromBytes(("point-post|" + key).getBytes(StandardCharsets.UTF_8)).toString());
                    row.setPointId(point.getId()); row.setPostId(postIds.get(i));
                    row.setCreatorId(userId); row.setCreatorName(userName); row.setCreateTime(now); row.setExtensions(new HashMap<>());
                }
                row.setName(point.getName()); row.setEnabled(true); row.setSortIndex(i);
                row.setModifierId(userId); row.setModifierName(userName); row.setModifyTime(now);
                if (rules != null) {
                    row.setLevelId(rules.get(i).levelId()); row.setStandardId(rules.get(i).standardId());
                    row.setEffectiveFrom(null); row.setEffectiveTo(null);
                }
                replacements.add(row);
            }
        }
        // 引用资产及同应用关系统一交给已有校验 owner；全部校验完成才写入任何计划。
        return Flux.fromIterable(replacements).concatMap(validator::validate).then(Mono.just(replacements));
    }

    private Mono<Void> persist(List<PatrolPlanEntity> existing, List<PatrolPlanEntity> rows, String userId, String userName, long now, boolean clearDates) {
        var oldIds = existing.stream().map(PatrolPlanEntity::getId).collect(java.util.stream.Collectors.toSet());
        var selectedIds = rows.stream().map(PatrolPlanEntity::getId).collect(java.util.stream.Collectors.toSet());
        var removed = existing.stream().filter(row -> !selectedIds.contains(row.getId())).map(PatrolPlanEntity::getId).toList();
        Mono<Void> disable = removed.isEmpty() ? Mono.empty() : plans.createUpdate()
                .set(PatrolPlanEntity::isEnabled, false).set(PatrolPlanEntity::getModifierId, userId).set(PatrolPlanEntity::getModifierName, userName)
                .set(PatrolPlanEntity::getModifyTime, now).in(PatrolPlanEntity::getId, removed).execute().then();
        var added = rows.stream().filter(row -> !oldIds.contains(row.getId())).toList();
        // 不物理删除，重选岗位复用身份；同日封印和全部打卡/漏检历史继续指向原计划。
        var retained = rows.stream().filter(row -> oldIds.contains(row.getId())).toList();
        Mono<Void> update = retained.isEmpty() ? Mono.empty() : plans.save(Flux.fromIterable(retained)).then();
        Mono<Void> clear = !clearDates || retained.isEmpty() ? Mono.empty() : plans.createUpdate()
                .setNull(PatrolPlanEntity::getEffectiveFrom).setNull(PatrolPlanEntity::getEffectiveTo)
                .in(PatrolPlanEntity::getId, retained.stream().map(PatrolPlanEntity::getId).toList()).execute().then();
        return disable.then(update).then(clear)
                .then(added.isEmpty() ? Mono.empty() : plans.insertBatch(Mono.just(added)).then())
                .thenMany(AssetsHolder.bindAssets(Flux.fromIterable(added), PatrolAssetType.patrolPlan, PatrolConfigEntity::getId))
                .then();
    }
}
