package org.jetlinks.pro.patrol.asset;

import org.hswebframework.ezorm.core.Conditional;
import org.hswebframework.ezorm.core.NestConditional;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.patrol.integration.PatrolProjectContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 请求级 AssetsHolder 收窄扩展；保留平台应用、凭证及动作授权，再与基础数据可见项目相交。
 * 所有巡更入口原有 assert/filter/query 都经过同一 owner，不在各个 Controller 手写组织条件。
 * 绑定仍委托原 holder；历史独立授权不能突破项目范围，项目选择头也不能扩大基础树。
 */
public final class PatrolProjectAssetsHolder extends ProxyAssetsHolder {
    private final AssetsHolder original;
    private final PatrolProjectContext context;
    private final PatrolAssetSupplier supplier;

    public PatrolProjectAssetsHolder(AssetsHolder original, PatrolProjectContext context, PatrolAssetSupplier supplier) {
        super(original);
        this.original = original;
        this.context = context;
        this.supplier = supplier;
    }

    private PatrolAssetType type(String id) {
        String canonical = AssetType.of(id).getId();
        for (var value : PatrolAssetType.values()) if (value.getId().equals(canonical)) return value;
        return null;
    }

    @Override public Mono<AssetPermissionState> getPermissionState(AssetType type) {
        return this.type(type.getId()) == null ? original.getPermissionState(type) : Mono.just(AssetPermissionState.UNKNOWN);
    }
    @Override public Mono<Boolean> isEnabled(AssetType type) {
        return this.type(type.getId()) == null ? original.isEnabled(type) : Mono.just(true);
    }

    @Override public Flux<?> filter(String assetType, Collection<?> ids, boolean ignoreMissing, AssetPermission... permissions) {
        var type = type(assetType);
        if (type == null) return original.filter(assetType, ids, ignoreMissing, permissions);
        if (ids.isEmpty()) return Flux.empty();
        return original.filter(assetType, ids, ignoreMissing, permissions).collectList().flatMapMany(allowed -> {
            if (allowed.isEmpty()) return Flux.empty();
            var query = QueryParamEntity.newQuery().in("id", allowed).and("scopeKey", context.scopeKey()).getParam();
            query.setPaging(false);
            return supplier.service(type).query(query)
                    .filter(entity -> context.canUse(entity.getProjectId(), entity.getOwnerOrganizationId()))
                    .map(entity -> entity.getId());
        });
    }

    @Override public Mono<Boolean> hasPermission(String type, Collection<?> ids, boolean allowMissing, AssetPermission... permissions) {
        if (type(type) == null) return original.hasPermission(type, ids, allowMissing, permissions);
        var expected = new HashSet<>(ids);
        return filter(type, ids, allowMissing, permissions).collectList().map(actual -> new HashSet<>(actual).containsAll(expected));
    }
    @Override public Mono<Boolean> hasPermission(String type, Collection<?> ids, AssetPermission... permissions) {
        return hasPermission(type, ids, false, permissions);
    }
    @Override public Mono<Boolean> hasPermission(AssetType type, Collection<?> ids, boolean allowMissing, AssetPermission... permissions) {
        return hasPermission(type.getId(), ids, allowMissing, permissions);
    }
    @Override public Mono<Boolean> hasPermission(AssetType type, Collection<?> ids, AssetPermission... permissions) {
        return hasPermission(type.getId(), ids, false, permissions);
    }
    private Mono<Void> check(String type, Collection<?> ids, boolean allowMissing, AssetPermission... permissions) {
        return hasPermission(type, ids, allowMissing, permissions).filter(Boolean.TRUE::equals)
                .switchIfEmpty(Mono.error(new AccessDenyException.NoStackTrace())).then();
    }
    @Override public Mono<Void> assertPermission0(AssetType type, Collection<?> ids, AssetPermission... permissions) {
        return check(type.getId(), ids, false, permissions);
    }
    @Override public Mono<Void> assetPermission(AssetType type, Collection<?> ids, AssetPermission... permissions) {
        return check(type.getId(), ids, false, permissions);
    }
    @Override public Mono<Void> assertPermission(AssetType type, Collection<?> ids, boolean allowMissing, AssetPermission... permissions) {
        return check(type.getId(), ids, allowMissing, permissions);
    }
    @Override public Mono<Void> assertPermission(String type, Collection<?> ids, boolean allowMissing, AssetPermission... permissions) {
        return check(type, ids, allowMissing, permissions);
    }
    @Override public Mono<Void> assertPermission(String type, Collection<?> ids, AssetPermission... permissions) {
        return check(type, ids, false, permissions);
    }

    @Override public <E> Flux<E> filter(Flux<E> source, AssetType type, Function<E, ?> ids, boolean ignoreMissing, AssetPermission... permissions) {
        if (type(type.getId()) == null) return original.filter(source, type, ids, ignoreMissing, permissions);
        return source.buffer(200).concatMap(rows -> filter(type.getId(), rows.stream().map(ids).toList(), ignoreMissing, permissions)
                .collectList().flatMapMany(allowed -> Flux.fromIterable(rows).filter(row -> allowed.contains(ids.apply(row)))));
    }
    @Override public <E> Flux<E> filter(Flux<E> source, AssetType type, Function<E, ?> ids, AssetPermission... permissions) {
        return filter(source, type, ids, false, permissions);
    }

    @Override public Mono<QueryParamEntity> injectQuery(QueryParamEntity source, String type, String idProperty, AssetPermission... permissions) {
        return original.injectQuery(source, type, idProperty, permissions).map(query -> {
            if (type(type) != null) query.toNestQuery(q -> q.and(idProperty, term(type), expression()));
            return query;
        });
    }
    @Override public <T extends Conditional<T>> Mono<NestConditional<T>> injectConditional(
            NestConditional<T> conditional, String type, String idProperty, AssetPermission... permissions) {
        // 把原 holder 的 OR 整体封进一组，再 AND 项目约束，避免 OR 分支绕过组织范围。
        return original.injectConditional(conditional, type, idProperty, permissions).map(result -> {
            if (type(type) != null) result.group().and(idProperty, term(type), expression());
            return result;
        });
    }
    @Override public <T extends Conditional<T>> Mono<T> injectConditional(T conditional, String type, String idProperty, AssetPermission... permissions) {
        return original.injectConditional(conditional, type, idProperty, permissions).map(result -> {
            if (type(type) != null) result.group().and(idProperty, term(type), expression());
            return result;
        });
    }

    private String term(String type) { return "patrol-project-" + type(type).getId(); }
    private List<org.hswebframework.ezorm.core.param.Term> expression() {
        // 空授权集合使用互斥条件，绝不能让 ORM 忽略空 IN 后变成全量。
        var query = QueryParamEntity.newQuery().and("scopeKey", context.scopeKey());
        if (context.projects().isEmpty() && context.organizations().isEmpty())
            return query.isNull("id").notNull("id").getParam().getTerms();
        var ownership = query.nest();
        if (!context.projects().isEmpty()) ownership.in("projectId", context.projects());
        if (!context.organizations().isEmpty()) ownership.orNest().isNull("projectId")
                .in("ownerOrganizationId", context.organizations()).end();
        return ownership.end().getParam().getTerms();
    }
}
