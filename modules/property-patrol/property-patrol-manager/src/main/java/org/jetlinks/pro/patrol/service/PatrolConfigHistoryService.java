package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolConfigEntity;
import org.jetlinks.pro.patrol.entity.PatrolConfigHistoryEntity;
import org.jetlinks.pro.patrol.entity.PatrolLevelEntity;
import org.jetlinks.pro.patrol.integration.PatrolScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 配置历史不补写虚构的旧事实；修改使用单调版本 CAS，配置和追加记录原子提交。 */
@Service
public class PatrolConfigHistoryService extends GenericReactiveCrudService<PatrolConfigHistoryEntity, String> {
    private final PatrolScope scope;
    private final TransactionalOperator transaction;

    public PatrolConfigHistoryService(ReactiveRepository<PatrolConfigHistoryEntity, String> events,
            PatrolScope scope, TransactionalOperator transaction) {
        super(events); this.scope = scope; this.transaction = transaction;
    }

    /** 调用方是配置写权限、校验和绑定的唯一 owner；此处只追加同一写入的事实。 */
    public <E extends PatrolConfigEntity> Mono<E> created(PatrolAssetType type, E entity) {
        return append(type, null, entity).thenReturn(entity);
    }

    public <E extends PatrolConfigEntity> Mono<E> update(GenericReactiveCrudService<E, String> service,
            PatrolAssetType type, E old, E entity) {
        return Mono.defer(() -> {
            entity.setModifyTime(Math.max(entity.getModifyTime(), Objects.requireNonNullElse(old.getModifyTime(), 0L) + 1));
            var update = service.createUpdate().set("name", entity.getName()).set("enabled", entity.isEnabled())
                    .set("modifierId", entity.getModifierId()).set("modifyTime", entity.getModifyTime())
                    .where("id", old.getId());
            if (entity.getModifierName() == null) update.setNull("modifierName"); else update.set("modifierName", entity.getModifierName());
            if (entity.getDescription() == null) update.setNull("description"); else update.set("description", entity.getDescription());
            if (entity instanceof PatrolLevelEntity level) update.set("rule", level.getRule());
            if (old.getModifyTime() == null) update.isNull("modifyTime"); else update.and("modifyTime", old.getModifyTime());
            return update.execute().flatMap(count -> count == 1 ? append(type, old, entity).thenReturn(entity)
                    : Mono.error(new BusinessException("error.patrol.config.changed")));
        }).as(transaction::transactional);
    }

    private Mono<Void> append(PatrolAssetType type, PatrolConfigEntity before, PatrolConfigEntity after) {
        return ReactiveAuthenticationHolder.get().flatMap(auth -> {
            var event = new PatrolConfigHistoryEntity(); event.setId(UUID.randomUUID().toString());
            event.setAssetType(type.getId()); event.setEntityId(after.getId()); event.setScopeKey(after.getScopeKey());
            event.setAction(before == null ? "create" : "update"); event.setCreateTime(after.getModifyTime());
            event.setActorId(auth.getUser().getId()); event.setActorName(auth.getUser().getName());
            event.setBeforeValue(before == null ? null : snapshot(before)); event.setAfterValue(snapshot(after));
            return insert(event).then();
        });
    }

    private Map<String, Object> snapshot(PatrolConfigEntity entity) {
        var value = new LinkedHashMap<String, Object>();
        value.put("name", entity.getName()); value.put("enabled", entity.isEnabled()); value.put("description", entity.getDescription());
        if (entity instanceof PatrolLevelEntity level) value.put("rule", level.getRule());
        return value;
    }

    /** 历史读取检查原资产 read，额外固定当前应用；调用方条件只能收窄，分页不能无界。 */
    public Mono<PagerResult<PatrolConfigHistoryEntity>> history(PatrolAssetType type, String id, QueryParamEntity query) {
        query.setPaging(true); query.setPageSize(Math.max(1, Math.min(50, query.getPageSize())));
        query.setPageIndex(Math.max(0, query.getPageIndex()));
        query.toQuery().orderByDesc("createTime").orderByDesc("id");
        return AssetsHolder.assertPermission(type, id, CrudAssetPermission.read).then(scope.current()).flatMap(current -> {
            query.toNestQuery().and("assetType", type.getId()).and("entityId", id).and("scopeKey", current.key());
            return queryPager(query);
        });
    }
}
