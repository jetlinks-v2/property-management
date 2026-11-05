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

/** 角色可单独指派，也可连同规则一次保存；选中点位在同一事务中替换，不改写任务快照。 */
@Service
@AllArgsConstructor
public class PatrolPointAssignmentService {
    private final PatrolPointService points;
    private final PatrolPlanService plans;
    private final PatrolConfigurationValidator validator;
    private final PatrolScope scope;
    private final TransactionalOperator transaction;

    public record Rule(String roleId, String levelId, String standardId) {
        public Rule {
            roleId = roleId == null ? "" : roleId.trim();
            levelId = levelId == null || levelId.isBlank() ? null : levelId.trim();
            standardId = standardId == null || standardId.isBlank() ? null : standardId.trim();
        }
    }
    public record Detail(String pointId, String categoryId, List<PatrolPlanEntity> assignments) {}

    /** 全部分类概览仅汇总可读计划；编辑入口仍要求明确分类并校验完整计划集合。 */
    public Mono<List<Detail>> summary(List<String> pointIds) {
        if (pointIds.isEmpty()) return Mono.just(List.of());
        if (pointIds.size() > 100) return Mono.error(new BusinessException("error.patrol.assignment.limit"));
        return authorizePoints(pointIds, CrudAssetPermission.read).then(scope.current()).flatMap(current -> {
            var query = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
                    .in("pointId", pointIds).is("scopeKey", current.key()).is("enabled", true).getParam();
            query.setPaging(false);
            return AssetsHolder.injectQueryParam(query, PatrolAssetType.patrolPlan, "id")
                    .flatMapMany(plans::query).take(10001).collectList().flatMap(rows -> {
                        if (rows.size() > 10000) return Mono.error(new BusinessException("error.patrol.assignment.limit"));
                        return Mono.just(pointIds.stream().map(id -> new Detail(id, null,
                                rows.stream().filter(row -> id.equals(row.getPointId())).toList())).toList());
                    });
        });
    }

    public Mono<List<Detail>> detail(List<String> pointIds) {
        return detail(pointIds, null);
    }

    public Mono<List<Detail>> detail(List<String> pointIds, String categoryId) {
        if (pointIds.isEmpty()) return Mono.just(List.of());
        if (pointIds.size() > 100) return Mono.error(new BusinessException("error.patrol.assignment.limit"));
        return authorizePoints(pointIds, CrudAssetPermission.read)
                .thenMany(points.findById(pointIds)).collectList()
                .flatMap(selected -> scope.current().flatMap(current -> {
                    checkPoints(selected, pointIds, current.key());
                    var selection = selectCategories(selected, categoryId);
                    return load(selected, selection)
                            .map(rows -> rows.stream().filter(PatrolPlanEntity::isEnabled).toList())
                            .flatMap(rows -> authorizePlans(rows, CrudAssetPermission.read).thenReturn(rows))
                            .map(rows -> pointIds.stream().map(id -> new Detail(id, selection.get(id),
                                    rows.stream().filter(row -> id.equals(row.getPointId())).toList())).toList());
                }));
    }

    public Mono<Void> assign(List<String> pointIds, List<String> roleIds) {
        return assign(pointIds, roleIds, null);
    }

    public Mono<Void> assign(List<String> pointIds, List<String> roleIds, String categoryId) {
        return replace(pointIds, roleIds, null, categoryId);
    }

    public Mono<Void> configure(List<String> pointIds, List<Rule> rules) {
        return configure(pointIds, rules, null);
    }

    public Mono<Void> configure(List<String> pointIds, List<Rule> rules, String categoryId) {
        return replace(pointIds, rules.stream().map(Rule::roleId).toList(), rules, categoryId, true);
    }

    /** 同时替换角色及规则，不要求预先保存角色；复用原有资产校验、点位锁和事务。 */
    public Mono<Void> configureAll(List<String> pointIds, List<Rule> rules, String categoryId) {
        if (rules.stream().anyMatch(rule -> (rule.levelId() == null) != (rule.standardId() == null)))
            return Mono.error(new BusinessException("error.patrol.assignment.incomplete", 400));
        return replace(pointIds, rules.stream().map(Rule::roleId).toList(), rules, categoryId, false);
    }

    private Mono<Void> replace(List<String> pointIds, List<String> roleIds, List<Rule> rules, String categoryId) {
        return replace(pointIds, roleIds, rules, categoryId, false);
    }

    private Mono<Void> replace(List<String> pointIds, List<String> roleIds, List<Rule> rules, String categoryId,
                               boolean requireAssignedRoles) {
        return Mono.defer(() -> {
            if (pointIds.isEmpty() || pointIds.size() > 100)
                return Mono.error(new BusinessException("error.patrol.assignment.limit"));
            if (new HashSet<>(pointIds).size() != pointIds.size()
                    || new HashSet<>(roleIds).size() != roleIds.size())
                return Mono.error(new BusinessException("error.patrol.assignment.duplicate"));
            // 新入口同时控制点位配置和已有计划资产；混入一个越权对象则整体拒绝。
            return authorizePoints(pointIds, CrudAssetPermission.save)
                    .thenMany(points.findById(pointIds)).collectList()
                    .zipWith(ReactiveAuthenticationHolder.get())
                    .flatMap(pair -> scope.current().flatMap(current -> {
                        var selected = pair.getT1();
                        checkPoints(selected, pointIds, current.key());
                        var selection = selectCategories(selected, categoryId);
                        String userId = pair.getT2().getUser().getId();
                        String userName = Objects.requireNonNullElse(pair.getT2().getUser().getName(), "");
                        long now = System.currentTimeMillis();
                        // 按稳定顺序 CAS 点位，串行化同一点位的批量覆盖；失败时所有写入回滚。
                        return Flux.fromIterable(selected.stream().sorted(Comparator.comparing(PatrolPointEntity::getId)).toList())
                                .concatMap(point -> lock(point, userId, userName, now)).then(load(selected, selection))
                                .flatMap(existing -> authorizePlans(existing, CrudAssetPermission.save)
                                        .then(prepare(selected, existing, selection, roleIds, rules, userId, userName, now, requireAssignedRoles))
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

    private static void checkPoints(List<PatrolPointEntity> selected, List<String> ids, String scopeKey) {
        if (selected.size() != ids.size() || selected.stream()
                .anyMatch(point -> !point.isEnabled() || !scopeKey.equals(point.getScopeKey())))
            throw new BusinessException("error.patrol.reference.unavailable");
    }

    private static Map<String, String> selectCategories(List<PatrolPointEntity> selected, String categoryId) {
        var selection = new HashMap<String, String>();
        for (var point : selected) {
            String chosen = categoryId;
            if (chosen == null) {
                if (point.getCategoryIds().size() != 1)
                    throw new BusinessException("error.patrol.point.category_required");
                chosen = point.getCategoryIds().get(0);
            }
            if (!point.getCategoryIds().contains(chosen))
                throw new BusinessException("error.patrol.reference.unavailable");
            selection.put(point.getId(), chosen);
        }
        return selection;
    }

    private Mono<List<PatrolPlanEntity>> load(List<PatrolPointEntity> selected, Map<String, String> selection) {
        var filter = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery();
        for (var point : selected) {
            String category = selection.get(point.getId());
            var part = filter.orNest().and("pointId", point.getId()).nest().and("categoryId", category);
            if (category.equals(point.getCategoryId())) part.or().isNull("categoryId");
            part.end().end();
        }
        return plans.createQuery().setParam(filter.getParam())
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
                                                Map<String, String> selection,
                                                List<String> roleIds, List<Rule> rules, String userId, String userName, long now,
                                                boolean requireAssignedRoles) {
        List<PatrolPlanEntity> replacements = new ArrayList<>();
        Map<String, PatrolPlanEntity> current = new HashMap<>();
        for (var row : existing) {
            String key = row.getPointId() + "|" + row.getRoleId();
            var previous = current.get(key);
            if (previous != null && previous.isEnabled() && row.isEnabled())
                return Mono.error(new BusinessException("error.patrol.assignment.duplicate_plan"));
            if (previous == null || row.isEnabled()) current.put(key, row);
        }
        for (var point : selected) {
            if (requireAssignedRoles) {
                var assigned = existing.stream().filter(row -> row.isEnabled() && point.getId().equals(row.getPointId()))
                        .map(PatrolPlanEntity::getRoleId).collect(java.util.stream.Collectors.toSet());
                if (assigned.isEmpty() || !assigned.equals(new HashSet<>(roleIds)))
                    return Mono.error(new BusinessException("error.patrol.assignment.posts_differ"));
            }
            for (int i = 0; i < roleIds.size(); i++) {
                String key = point.getId() + "|" + roleIds.get(i);
                var old = current.get(key);
                var row = old == null ? new PatrolPlanEntity() : old;
                if (old == null) {
                    row.setId(UUID.nameUUIDFromBytes(("point-category-post|" + point.getId() + "|" + selection.get(point.getId())
                            + "|" + roleIds.get(i)).getBytes(StandardCharsets.UTF_8)).toString());
                    row.setPointId(point.getId()); row.setRoleId(roleIds.get(i));
                    row.setCreatorId(userId); row.setCreatorName(userName); row.setCreateTime(now); row.setExtensions(new HashMap<>());
                }
                row.setCategoryId(selection.get(point.getId()));
                row.setName(point.getName()); row.setEnabled(true); row.setSortIndex(i);
                row.setModifierId(userId); row.setModifierName(userName); row.setModifyTime(now);
                if (rules != null) {
                    row.setLevelId(rules.get(i).levelId()); row.setStandardId(rules.get(i).standardId());
                    // 当前规则由等级重新解析；两项都清空时停止后续任务生成，历史快照保留。
                    row.setRule(null);
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
        var clearedIds = retained.stream().filter(row -> row.getStandardId() == null)
                .map(PatrolPlanEntity::getId).toList();
        // save 跳过 null 字段，显式清除已取消的规则，避免旧配置残留在数据库。
        Mono<Void> clearRules = !clearDates || clearedIds.isEmpty() ? Mono.empty() : plans.createUpdate()
                .setNull(PatrolPlanEntity::getLevelId).setNull(PatrolPlanEntity::getStandardId)
                .setNull(PatrolPlanEntity::getRule).in(PatrolPlanEntity::getId, clearedIds).execute().then();
        return disable.then(update).then(clear).then(clearRules)
                .then(added.isEmpty() ? Mono.empty() : plans.insertBatch(Mono.just(added)).then())
                .thenMany(AssetsHolder.bindAssets(Flux.fromIterable(added), PatrolAssetType.patrolPlan, PatrolConfigEntity::getId))
                .then();
    }
}
