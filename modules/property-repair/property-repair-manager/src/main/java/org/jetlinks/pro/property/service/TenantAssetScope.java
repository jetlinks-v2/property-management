package org.jetlinks.pro.property.service;

import lombok.AllArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.AssetManager;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.tenant.assets.TenantAssetsHolder;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 请求租户是组织/角色授权之外的强制边界，使用平台租户 AssetsHolder 与现有权限取交集。
 * 避免多组织授权或创建人权限使请求跨越当前 Cloud 项目。
 */
@Component
@AllArgsConstructor
public class TenantAssetScope {
    private final AssetManager assetManager;

    private Mono<TenantAssetsHolder> current() {
        return TenantHolder.currentId()
            .switchIfEmpty(Mono.error(new AccessDenyException()))
            .map(tenantId -> new TenantAssetsHolder(List.of(tenantId), assetManager));
    }

    public Mono<QueryParamEntity> injectQuery(QueryParamEntity query, AssetType type, String property) {
        return current().flatMap(holder -> holder.injectQuery(
            query.clone().toNestQuery().getParam(), type.getId(), property, CrudAssetPermission.read));
    }

    public Mono<Void> assertOwned(AssetType type, String id) {
        return current().flatMap(holder -> holder.assertPermission0(
            type, List.of(id), CrudAssetPermission.read));
    }
}
