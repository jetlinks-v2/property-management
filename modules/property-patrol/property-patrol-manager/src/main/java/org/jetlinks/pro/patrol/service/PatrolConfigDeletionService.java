package org.jetlinks.pro.patrol.service;

import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.integration.PatrolScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** 配置逻辑删除唯一入口：不级联、不删除执行事实，不把停用当删除。 */
@Service
@AllArgsConstructor
public class PatrolConfigDeletionService {
    private final PatrolLevelService levels;
    private final PatrolStandardService standards;
    private final PatrolWarningService warnings;
    private final PatrolPlanService plans;
    private final PatrolScope scope;
    private final TransactionalOperator transaction;

    public Mono<Void> delete(PatrolAssetType type, String id) {
        PatrolRemovableConfigService<?> service = switch (type) {
            case patrolLevel -> levels;
            case patrolStandard -> standards;
            case patrolWarning -> warnings;
            default -> throw new IllegalArgumentException("Unsupported removable configuration");
        };
        return AssetsHolder.assertPermission(type, id, CrudAssetPermission.delete)
                .then(scope.current()).flatMap(current -> service.findForUpdate(id)
                        .switchIfEmpty(Mono.error(NotFoundException::new))
                        .flatMap(entity -> {
                            if (!current.key().equals(entity.getScopeKey()))
                                return Mono.error(new AccessDenyException.NoStackTrace());
                            return assertUnreferenced(type, id, current.key())
                                    .then(ReactiveAuthenticationHolder.get())
                                    .flatMap(auth -> service.createUpdate()
                                            .set("deletedTime", System.currentTimeMillis())
                                            .set("modifierId", auth.getUser().getId())
                                            .set("modifierName", java.util.Objects.requireNonNullElse(auth.getUser().getName(), ""))
                                            .set("modifyTime", Math.max(System.currentTimeMillis(),
                                                    java.util.Objects.requireNonNullElse(entity.getModifyTime(), 0L) + 1))
                                            .where("id", id).isNull("deletedTime").execute()).then();
                        }).as(transaction::transactional));
    }

    private Mono<Void> assertUnreferenced(PatrolAssetType type, String id, String key) {
        if (type == PatrolAssetType.patrolWarning) return Mono.empty();
        // 停用的岗位关系仍保留引用；不通过删除配置顺手清理关系或改写历史。
        return plans.createQuery().where(type == PatrolAssetType.patrolLevel ? "levelId" : "standardId", id)
                .and("scopeKey", key).select("id").fetchOne()
                .flatMap(plan -> Mono.<Void>error(new BusinessException("error.patrol.config.referenced"))).then();
    }
}
