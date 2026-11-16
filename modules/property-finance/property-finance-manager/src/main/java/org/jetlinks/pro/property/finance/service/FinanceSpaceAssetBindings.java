package org.jetlinks.pro.property.finance.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.DimensionType;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.impl.DimensionAssetBindManager;
import org.jetlinks.pro.assets.impl.entity.DimensionAssetBindingEntity;
import org.jetlinks.pro.authorize.OrgDimensionType;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.QueryPropertySpaceOrganizationBindingsCommand;
import org.reactivestreams.Publisher;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 收费侧空间绑定提供方：组织归属实时读取基础服务，其他维度继续使用收费本地绑定。
 * Holder 仍负责角色支持项、权限位、应用作用域及上限；此处不创建任何额外授权。
 */
@Component
public class FinanceSpaceAssetBindings implements AssetBindProvider {
    private static final AssetType SPACE = AssetType.of("space");
    private final DimensionAssetBindManager local;

    public FinanceSpaceAssetBindings(ReactiveRepository<DimensionAssetBindingEntity, String> rows) {
        local = new DimensionAssetBindManager(rows);
    }

    @Override
    public AssetType getAssetType() { return SPACE; }

    @Override
    public Optional<AssetBindFeatures> getSettings(String dimension) {
        // 只接管组织到空间的归属，客户、用户、应用等既有绑定路由保持平台默认行为。
        if (!OrgDimensionType.org.getId().equals(dimension)) return Optional.empty();
        var features = new AssetBindFeatures();
        features.setMultiple(true);
        features.setPermissionSupported(true);
        return Optional.of(features);
    }

    protected Flux<AssetBinding> organizationBindings(Collection<String> targets, Collection<?> ids) {
        if (targets != null && targets.isEmpty() || ids != null && ids.isEmpty()) return Flux.empty();
        var command = new QueryPropertySpaceOrganizationBindingsCommand();
        command.setOrganizationIds(targets == null ? null : List.copyOf(targets));
        command.setSpaceIds(ids == null ? null : ids.stream().map(Object::toString).toList());
        // 固定正式 core 命令，保留认证与作用域；来源失败直接拒绝，不能静默使用过期空间归属。
        return CommandSupportManagerProviders.getCommandSupport(QueryPropertySpaceOrganizationBindingsCommand.SERVICE_ID)
                .switchIfEmpty(Mono.error(FinanceScope.error("project_source_unavailable", 503)))
                .flatMapMany(support -> support.execute(command))
                .timeout(java.time.Duration.ofSeconds(15))
                .map(binding -> new SimpleAssetBinding(OrgDimensionType.org,
                        binding.organizationId(), SPACE, binding.spaceId(),
                        binding.permission(), binding.relation()));
    }

    @Override
    public Flux<AssetBinding> getBindings(String dimension, Collection<String> targets, Collection<?> ids) {
        return OrgDimensionType.org.getId().equals(dimension)
                ? organizationBindings(targets, ids) : local.getBindings(dimension, targets, SPACE, ids);
    }

    @Override
    public Flux<AssetBinding> getBindings(String dimension, Collection<String> targets) {
        return OrgDimensionType.org.getId().equals(dimension)
                ? organizationBindings(targets, null) : local.getBindings(dimension, targets, SPACE);
    }

    @Override
    public Flux<AssetBinding> getBindings(Collection<String> dimensions, Collection<?> ids) {
        var others = local.getBindings(dimensions, SPACE, ids)
                .filter(binding -> !OrgDimensionType.org.isSameType(binding.getTargetType()));
        return dimensions == null || dimensions.contains(OrgDimensionType.org.getId())
                ? Flux.merge(others, organizationBindings(null, ids)) : others;
    }

    @Override
    public Flux<AssetBinding> getBindings(Collection<?> ids) {
        return Flux.merge(organizationBindings(null, ids), local.getBindings(SPACE, ids)
                .filter(binding -> !OrgDimensionType.org.isSameType(binding.getTargetType())));
    }

    @Override
    public Flux<AssetBinding> getBindings(DimensionType dimension, Collection<String> targets) {
        return getBindings(dimension.getId(), targets);
    }

    // 项目空间归属只能由基础档案维护，收费入口不得创建另一份组织授权副本。
    @Override
    public Mono<Void> bindAssets(Publisher<AssetBindRequest> requests) {
        return local.bindAssets(Flux.from(requests).map(request -> {
            if (OrgDimensionType.org.getId().equals(request.getTargetType())) throw new AccessDenyException();
            return request;
        }));
    }

    @Override
    public Mono<Void> unbindAssets(Publisher<AssetUnbindRequest> requests) {
        return local.unbindAssets(Flux.from(requests).map(request -> {
            if (OrgDimensionType.org.getId().equals(request.getTargetType())) throw new AccessDenyException();
            return request;
        }));
    }

    @Override
    public Mono<Void> changePermission(String dimension, Collection<String> targets,
            Collection<?> ids, AssetPermission... permissions) {
        if (OrgDimensionType.org.getId().equals(dimension)) return Mono.error(new AccessDenyException());
        return local.changePermission(dimension, targets, SPACE, ids, permissions);
    }

    @Override
    public Mono<Integer> unbindAllAssets(Collection<?> ids) {
        return local.unbindAllAssets(SPACE.getId(), ids);
    }
}
