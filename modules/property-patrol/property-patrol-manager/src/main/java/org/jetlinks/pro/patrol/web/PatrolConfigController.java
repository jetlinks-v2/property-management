package org.jetlinks.pro.patrol.web;

import jakarta.annotation.Nonnull;
import jakarta.validation.Valid;

import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.hswebframework.web.crud.service.ReactiveCrudService;
import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.annotation.AssetsController;
import org.jetlinks.pro.assets.crud.CorrelatesAssetsHolderQueryController;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolConfigEntity;
import org.jetlinks.pro.patrol.service.PatrolConfigurationValidator;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** 通用查询沿用平台；写入只允许完整新增/编辑，客户端不得修改归属、ID 和审计元数据。 */
public abstract class PatrolConfigController<E extends PatrolConfigEntity>
        implements CorrelatesAssetsHolderQueryController<E, String> {
    private final GenericReactiveCrudService<E, String> service;
    private final PatrolConfigurationValidator validator;
    private final PatrolAssetType type;

    protected PatrolConfigController(
            GenericReactiveCrudService<E, String> service,
            PatrolConfigurationValidator validator,
            PatrolAssetType type) {
        this.service = service;
        this.validator = validator;
        this.type = type;
    }

    @Nonnull
    public AssetType getAssetType() {
        return type;
    }

    @Nonnull
    public Function<E, ?> getAssetIdMapper() {
        return PatrolConfigEntity::getId;
    }

    @Nonnull
    public String getAssetProperty() {
        return "id";
    }

    public ReactiveCrudService<E, String> getService() {
        return service;
    }

    @PostMapping
    @SaveAction
    @AssetsController(ignore = true)
    @Transactional
    public Mono<E> create(@RequestBody @Valid E entity) {
        // 新 ID、审计和扩展字段由服务端产生；绑定与插入在一个事务内完成。
        entity.setId(UUID.randomUUID().toString());
        entity.setExtensions(new HashMap<>());
        return ReactiveAuthenticationHolder.get()
                .flatMap(
                        auth -> {
                            entity.setCreatorId(auth.getUser().getId());
                            entity.setCreatorName(Objects.requireNonNullElse(auth.getUser().getName(), ""));
                            entity.setModifierName(entity.getCreatorName());
                            entity.setCreateTime(System.currentTimeMillis());
                            entity.setModifierId(entity.getCreatorId());
                            entity.setModifyTime(entity.getCreateTime());
                            return validator
                                    .validate(entity)
                                    .then(service.insert(entity))
                                    .thenMany(
                                            AssetsHolder.bindAssets(
                                                    Flux.just(entity),
                                                    type,
                                                    PatrolConfigEntity::getId))
                                    .single()
                                    .flatMap(this::created);
                        });
    }

    @PutMapping("/{id}")
    @SaveAction
    @AssetsController(ignore = true)
    @Transactional
    public Mono<E> update(@PathVariable String id, @RequestBody @Valid E entity) {
        return AssetsHolder.assertPermission(type, id, CrudAssetPermission.save)
                .then(findForUpdate(id).switchIfEmpty(Mono.error(NotFoundException::new)))
                .zipWith(ReactiveAuthenticationHolder.get())
                .flatMap(
                        pair -> {
                            E old = pair.getT1();
                            entity.setId(id);
                            entity.setExtensions(old.getExtensions());
                            entity.setScopeKey(old.getScopeKey());
                            entity.setCreatorId(old.getCreatorId());
                            entity.setCreatorName(old.getCreatorName());
                            entity.setModifierName(Objects.requireNonNullElse(pair.getT2().getUser().getName(), ""));
                            entity.setCreateTime(old.getCreateTime());
                            entity.setModifierId(pair.getT2().getUser().getId());
                            entity.setModifyTime(System.currentTimeMillis());
                            return validator
                                    .validate(entity, old)
                                    .then(
                                            Mono.defer(
                                                    () -> {
                                                        if (!Objects.equals(
                                                                old.getScopeKey(),
                                                                entity.getScopeKey()))
                                                            return Mono.error(
                                                                    new AccessDenyException
                                                                            .NoStackTrace());
                                                        return persistUpdate(old, entity);
                                                    }));
                        });
    }

    @SuppressWarnings("unchecked")
    private Mono<E> findForUpdate(String id) {
        if (service instanceof org.jetlinks.pro.patrol.service.PatrolRemovableConfigService<?> removable)
            return (Mono<E>) removable.findForUpdate(id);
        return service.findById(id);
    }

    /** 需要业务修改记录的配置在同一事务追加事实；普通配置不引入审计副作用。 */
    protected Mono<E> created(E entity) { return Mono.just(entity); }

    protected Mono<E> persistUpdate(E old, E entity) {
        return service.updateById(entity.getId(), entity).thenReturn(entity);
    }
}
