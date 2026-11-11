package org.jetlinks.pro.patrol.service;

import lombok.AllArgsConstructor;

import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.domain.RuleEngine;
import org.jetlinks.pro.patrol.entity.PatrolCategoryEntity;
import org.jetlinks.pro.patrol.entity.PatrolConfigEntity;
import org.jetlinks.pro.patrol.entity.PatrolPlanEntity;
import org.jetlinks.pro.patrol.entity.PatrolPointEntity;
import org.jetlinks.pro.patrol.entity.PatrolRosterEntity;
import org.jetlinks.pro.patrol.entity.PatrolStandardEntity;
import org.jetlinks.pro.patrol.integration.PatrolScope;
import org.jetlinks.pro.patrol.integration.SaasPersonDirectory;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 唯一的配置关系校验入口；被引用资产检查 read，配置本身的 save 权限由 Controller 拥有。 */
@Component
@AllArgsConstructor
public class PatrolConfigurationValidator {
    private final PatrolCategoryService categories;
    private final PatrolPointService points;
    private final PatrolStandardService standards;
    private final PatrolRoleService roles;
    private final SaasPersonDirectory people;
    private final PatrolScope scope;
    private final PatrolAttachmentService attachments;
    private final PatrolLevelService levels;
    private final PatrolTagService tags;

    public Mono<Void> validate(PatrolConfigEntity entity) {
        return validate(entity, null);
    }

    /** 更新点位时沿已读取的分类关系校验，初始 categoryId 不能作为变更关系的旁路。 */
    public Mono<Void> validate(PatrolConfigEntity entity, PatrolConfigEntity previous) {
        // categoryId 为服务端只读字段；完整编辑计划也必须保留原分类，不能按当前单分类重新推断。
        Mono<Void> retainPlanCategory = Mono.empty();
        if (entity instanceof PatrolPlanEntity plan && previous instanceof PatrolPlanEntity old) {
            retainPlanCategory = old.getCategoryId() == null
                    ? points.findById(old.getPointId()).doOnNext(point -> plan.setCategoryId(point.getCategoryId())).then()
                    : Mono.fromRunnable(() -> plan.setCategoryId(old.getCategoryId()));
        }
        Mono<Void> references = retainPlanCategory.then(scope.current())
                .flatMap(
                        current -> {
                            entity.setScopeKey(current.key());
                            entity.setDescription(
                                    entity.getDescription() == null ? "" : entity.getDescription());
                            if (entity instanceof PatrolCategoryEntity category
                                    && category.getParentId() == null) category.setParentId("");
                            if (entity instanceof PatrolPointEntity point) {
                                point.completeCode(previous instanceof PatrolPointEntity old ? old.getCode() : null);
                                if (previous instanceof PatrolPointEntity old
                                        && !java.util.Objects.equals(old.getCategoryId(), point.getCategoryId()))
                                    return Mono.error(new BusinessException("error.patrol.point.category_managed"));
                                point.setLocation(
                                        point.getLocation() == null ? "" : point.getLocation());
                                point.setNfcCode(
                                        point.getNfcCode() == null ? "" : point.getNfcCode());
                                var categoryIds = previous instanceof PatrolPointEntity old
                                        ? old.getCategoryIds() : List.of(point.getCategoryId());
                                point.setCategoryIds(categoryIds);
                                return reactor.core.publisher.Flux.fromIterable(categoryIds)
                                        .concatMap(id -> reference(PatrolAssetType.patrolCategory, id,
                                                categories.findForUpdate(id), current.key(), entity)).then()
                                        .then(reactor.core.publisher.Flux.fromIterable(
                                                point.getTagIds() == null ? List.<String>of() : point.getTagIds())
                                                .concatMap(id -> reference(PatrolAssetType.patrolTag, id,
                                                        tags.findById(id), current.key(), entity))
                                                .then());
                            }
                            if (entity instanceof PatrolCategoryEntity category)
                                return validateCategory(category, previous, current.key());
                            if (entity instanceof org.jetlinks.pro.patrol.entity.PatrolWarningEntity warning) {
                                if (!warning.getTriggerChildCode().startsWith(warning.getTriggerParentCode() + "_"))
                                    return Mono.error(new BusinessException("error.patrol.warning.trigger"));
                                warning.setWarningCode("YJ" + warning.getId().replace("-", "").toUpperCase());
                                if (warning.getName().length() > 30)
                                    return Mono.error(new BusinessException("error.patrol.warning.name"));
                            }
                            if (entity instanceof org.jetlinks.pro.patrol.entity.PatrolLevelEntity level) {
                                try { RuleEngine.validate(level.getRule()); }
                                catch (IllegalArgumentException error) {
                                    return Mono.error(new BusinessException(error.getMessage()));
                                }
                            }
                            if (entity instanceof PatrolStandardEntity standard) {
                                PatrolInspection.validateStandard(standard.getItems());
                                return attachments.assertReferences(standard.getItems().stream()
                                                .map(PatrolStandardEntity.CheckItem::exampleFileId)
                                                .filter(id -> id != null && !id.isBlank()).toList(),
                                        org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity.Purpose.example,
                                        current.key(), null, standard);
                            }
                            if (entity instanceof PatrolRosterEntity roster) {
                                if (roster.getEndTime() <= roster.getStartTime()
                                        || roster.getEndTime() - roster.getStartTime() > 86400000L)
                                    return Mono.error(
                                            new BusinessException("error.patrol.roster.window"));
                                LocalDate shiftDay =
                                        Instant.ofEpochMilli(roster.getStartTime())
                                                .atZone(ZoneId.of("Asia/Shanghai"))
                                                .toLocalDate();
                                if (!shiftDay.equals(roster.getBusinessDate()))
                                    return Mono.error(
                                            new BusinessException("error.patrol.roster.date"));
                                Mono<Void> memberCheck = roster.getRoleId().isBlank()
                                        ? people.assertMembers(List.of(roster.getUserId()))
                                        : roleReference(roster.getRoleId())
                                        .flatMap(
                                                post ->
                                                        post.getUserIds()
                                                                        .contains(
                                                                                roster.getUserId())
                                                                ? people.assertMembers(
                                                                        List.of(roster.getUserId()))
                                                                : Mono.error(
                                                                        new BusinessException(
                                                                                "error.patrol.execution.unassigned")));
                                return memberCheck.then(people.query(org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
                                                .is("id", roster.getUserId()).getParam()))
                                        .doOnNext(page -> { if (!page.getData().isEmpty()) roster.setUserName(page.getData().get(0).name()); })
                                        .then();
                            }
                            if (entity instanceof PatrolPlanEntity plan) {
                                if (plan.getStandardId() == null && (plan.getRule() != null || plan.getLevelId() != null))
                                    return Mono.error(new BusinessException("error.patrol.assignment.incomplete"));
                                if (plan.getEffectiveFrom() != null && plan.getEffectiveTo() != null
                                        && (plan.getEffectiveTo().isBefore(plan.getEffectiveFrom())
                                        || plan.getEffectiveTo()
                                                .isAfter(plan.getEffectiveFrom().plusYears(2))))
                                    return Mono.error(
                                            new BusinessException("error.patrol.plan.dates"));
                                Mono<Void> levelCheck = plan.getLevelId() == null || plan.getLevelId().isBlank()
                                        ? Mono.empty()
                                        : reference(PatrolAssetType.patrolLevel, plan.getLevelId(),
                                                levels.findForUpdate(plan.getLevelId()), current.key(), entity)
                                                .doOnNext(level -> plan.setRule(level.getRule())).then();
                                Mono<Void> ruleCheck = plan.getStandardId() == null && plan.getRule() == null
                                        && plan.getLevelId() == null ? Mono.empty()
                                        : levelCheck.then(Mono.fromRunnable(() -> validatePlanRule(plan)))
                                            .then(reference(PatrolAssetType.patrolStandard, plan.getStandardId(),
                                                    standards.findForUpdate(plan.getStandardId()), current.key(), entity)).then();
                                return ruleCheck.then(Mono.when(
                                        reference(
                                                PatrolAssetType.patrolPoint,
                                                plan.getPointId(),
                                                points.findById(plan.getPointId()),
                                                current.key(), entity).doOnNext(point -> {
                                            if (plan.getCategoryId() == null) {
                                                if (point.getCategoryIds().size() != 1)
                                                    throw new BusinessException("error.patrol.point.category_required");
                                                plan.setCategoryId(point.getCategoryIds().get(0));
                                            }
                                            if (!point.getCategoryIds().contains(plan.getCategoryId()))
                                                throw new BusinessException("error.patrol.reference.unavailable");
                                        }),
                                        plan.getRoleId().isBlank()
                                                ? Mono.fromRunnable(() -> plan.setRoleName(""))
                                                : roleReference(plan.getRoleId()).doOnNext(role -> plan.setRoleName(role.getName())).then()));
                            }
                            return Mono.empty();
                        });
        // 全应用预警不需要项目上下文，仍执行当前应用 Scope 及预警内容校验。
        if (entity instanceof org.jetlinks.pro.patrol.entity.PatrolWarningEntity)
            return PatrolOwnership.resolve(entity, previous, points).then(references);
        return PatrolOwnership.resolve(entity, previous, points).then(Mono.defer(() ->
                org.jetlinks.pro.patrol.integration.PatrolProjectContext.current().flatMap(context ->
                        entity.getProjectId() == null ? references
                                : references.contextWrite(ctx -> ctx.put(
                                        org.jetlinks.pro.patrol.integration.PatrolProjectContext.class,
                                        context.forProject(entity.getProjectId()))))));
    }

    /** 先解析被选等级，再验证生效期内的规则及相邻日期跨天重叠。 */
    private static void validatePlanRule(PatrolPlanEntity plan) {
        try {
            RuleEngine.validate(plan.getRule());
            // RuleEngine 已覆盖整周与全部高级日期边界，无需扫描人工生效期。
        } catch (IllegalArgumentException error) {
            throw new BusinessException(error.getMessage());
        }
    }

    /** 新建和移动同时约束整棵子树；历史超深节点不改父级时允许修改名称等资料。 */
    private Mono<Void> validateCategory(PatrolCategoryEntity category, PatrolConfigEntity previous, String key) {
        boolean moved = !(previous instanceof PatrolCategoryEntity old)
                || !java.util.Objects.equals(java.util.Objects.requireNonNullElse(old.getParentId(), ""), category.getParentId());
        var visited = new HashSet<>(Set.of(category.getId()));
        return checkParents(category, category.getParentId(), visited, key, moved)
                .then(Mono.defer(() -> moved
                        ? checkChildDepth(category.getId(), visited.size(), key) : Mono.empty()));
    }

    private Mono<Void> checkParents(
            PatrolCategoryEntity category, String parent, Set<String> visited, String key, boolean limitDepth) {
        if (parent == null || parent.isEmpty()) return Mono.empty();
        if (!visited.add(parent) || visited.size() > 32)
            return Mono.error(new BusinessException("error.patrol.category.cycle"));
        if (limitDepth && visited.size() > 3)
            return Mono.error(new BusinessException("error.patrol.category.depth", 400));
        return reference(PatrolAssetType.patrolCategory, parent, categories.findForUpdate(parent), key, category)
                .flatMap(value -> checkParents(category, value.getParentId(), visited, key, limitDepth));
    }

    /** 引用完整性包含同应用不可见后代；仅校验深度，不把这些节点返回给调用者。 */
    private Mono<Void> checkChildDepth(String id, int depth, String key) {
        var children = categories.createQuery().where("parentId", id).and("scopeKey", key).select("id");
        if (depth >= 3) return children.fetchOne().hasElement().flatMap(found -> found
                ? Mono.error(new BusinessException("error.patrol.category.depth", 400)) : Mono.empty());
        return children.fetch().concatMap(child -> checkChildDepth(child.getId(), depth + 1, key)).then();
    }

    /** 角色属于当前应用，不与点位项目比较；认证服务执行 role 资产授权。 */
    private Mono<org.jetlinks.pro.patrol.entity.PatrolRole> roleReference(String id) {
        return roles.findById(id).switchIfEmpty(Mono.error(new BusinessException("error.patrol.reference.unavailable")));
    }

    private <E extends PatrolConfigEntity> Mono<E> reference(
            PatrolAssetType type, String id, Mono<E> source, String key, PatrolConfigEntity owner) {
        return AssetsHolder.assertPermission(type, id, CrudAssetPermission.read)
                .then(source.switchIfEmpty(Mono.error(NotFoundException::new)))
                .flatMap(
                        entity ->
                                entity.isEnabled() && key.equals(entity.getScopeKey())
                                        ? org.jetlinks.pro.patrol.integration.PatrolProjectContext.current().flatMap(context -> {
                                            if (!context.canReference(owner.getProjectId(), entity.getProjectId()))
                                                return Mono.error(new BusinessException("error.patrol.project_mismatch", 400));
                                            return Mono.just(entity);
                                        })
                                        : Mono.error(
                                                new BusinessException(
                                                        "error.patrol.reference.unavailable")));
    }
}
