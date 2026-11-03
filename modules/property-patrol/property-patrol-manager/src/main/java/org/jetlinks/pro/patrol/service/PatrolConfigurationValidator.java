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
import org.jetlinks.pro.patrol.entity.PatrolPostEntity;
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
    private final PatrolPostService posts;
    private final SaasPersonDirectory people;
    private final PatrolScope scope;

    public Mono<Void> validate(PatrolConfigEntity entity) {
        return scope.current()
                .flatMap(
                        current -> {
                            entity.setScopeKey(current.key());
                            entity.setDescription(
                                    entity.getDescription() == null ? "" : entity.getDescription());
                            if (entity instanceof PatrolCategoryEntity category
                                    && category.getParentId() == null) category.setParentId("");
                            if (entity instanceof PatrolPointEntity point) {
                                point.setLocation(
                                        point.getLocation() == null ? "" : point.getLocation());
                                point.setNfcCode(
                                        point.getNfcCode() == null ? "" : point.getNfcCode());
                                return reference(
                                                PatrolAssetType.patrolCategory,
                                                point.getCategoryId(),
                                                categories.findById(point.getCategoryId()),
                                                current.key())
                                        .then();
                            }
                            if (entity instanceof PatrolCategoryEntity category)
                                return checkParents(
                                        category,
                                        category.getParentId(),
                                        new HashSet<>(Set.of(category.getId())),
                                        current.key());
                            if (entity instanceof PatrolStandardEntity standard) {
                                Set<String> ids = new HashSet<>();
                                for (var item : standard.getItems())
                                    if (!ids.add(item.id())
                                            || new HashSet<>(item.options()).size()
                                                    != item.options().size())
                                        return Mono.error(
                                                new BusinessException(
                                                        "error.patrol.standard.duplicate_item"));
                            }
                            if (entity instanceof PatrolPostEntity post)
                                return people.assertMembers(post.getUserIds());
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
                                return reference(
                                                PatrolAssetType.patrolPost,
                                                roster.getPostId(),
                                                posts.findById(roster.getPostId()),
                                                current.key())
                                        .flatMap(
                                                post ->
                                                        post.getUserIds()
                                                                        .contains(
                                                                                roster.getUserId())
                                                                ? people.assertMembers(
                                                                        List.of(roster.getUserId()))
                                                                : Mono.error(
                                                                        new BusinessException(
                                                                                "error.patrol.execution.unassigned")))
                                        .then();
                            }
                            if (entity instanceof PatrolPlanEntity plan) {
                                if (plan.getEffectiveTo().isBefore(plan.getEffectiveFrom())
                                        || plan.getEffectiveTo()
                                                .isAfter(plan.getEffectiveFrom().plusYears(2)))
                                    return Mono.error(
                                            new BusinessException("error.patrol.plan.dates"));
                                try {
                                    RuleEngine.validate(plan.getRule());
                                    // 覆盖有效期的日期和次日，保存时就拒绝跨天重叠，不等调度时才报错。
                                    for (LocalDate day = plan.getEffectiveFrom();
                                            !day.isAfter(plan.getEffectiveTo().plusDays(1));
                                            day = day.plusDays(1))
                                        RuleEngine.expand(
                                                plan.getRule(), day, ZoneId.of("Asia/Shanghai"));
                                } catch (IllegalArgumentException e) {
                                    return Mono.error(new BusinessException(e.getMessage()));
                                }
                                return Mono.when(
                                        reference(
                                                PatrolAssetType.patrolPoint,
                                                plan.getPointId(),
                                                points.findById(plan.getPointId()),
                                                current.key()),
                                        reference(
                                                PatrolAssetType.patrolPost,
                                                plan.getPostId(),
                                                posts.findById(plan.getPostId()),
                                                current.key()),
                                        reference(
                                                PatrolAssetType.patrolStandard,
                                                plan.getStandardId(),
                                                standards.findById(plan.getStandardId()),
                                                current.key()));
                            }
                            return Mono.empty();
                        });
    }

    private Mono<Void> checkParents(
            PatrolCategoryEntity category, String parent, Set<String> visited, String key) {
        if (parent == null || parent.isEmpty()) return Mono.empty();
        if (!visited.add(parent) || visited.size() > 32)
            return Mono.error(new BusinessException("error.patrol.category.cycle"));
        return reference(PatrolAssetType.patrolCategory, parent, categories.findById(parent), key)
                .flatMap(value -> checkParents(category, value.getParentId(), visited, key));
    }

    private <E extends PatrolConfigEntity> Mono<E> reference(
            PatrolAssetType type, String id, Mono<E> source, String key) {
        return AssetsHolder.assertPermission(type, id, CrudAssetPermission.read)
                .then(source.switchIfEmpty(Mono.error(NotFoundException::new)))
                .flatMap(
                        entity ->
                                entity.isEnabled() && key.equals(entity.getScopeKey())
                                        ? Mono.just(entity)
                                        : Mono.error(
                                                new BusinessException(
                                                        "error.patrol.reference.unavailable")));
    }
}
