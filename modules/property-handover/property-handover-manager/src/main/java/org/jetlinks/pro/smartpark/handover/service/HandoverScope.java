package org.jetlinks.pro.smartpark.handover.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.AssetManager;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.smartpark.handover.entity.HandoverApplicationEntity;
import org.jetlinks.pro.tenant.assets.TenantAssetsHolder;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

/** Tenant, application and project boundaries always intersect with the platform AssetsHolder. */
@Component
@RequiredArgsConstructor
public class HandoverScope {
    private final AssetManager assets;

    public Mono<HandoverContext> current() {
        return Mono.zip(
                        TenantHolder.currentId(),
                        AssetScopeContext.currentReactive()
                                .flatMap(
                                        s ->
                                                Mono.justOrEmpty(
                                                        s.getScope(
                                                                BusinessApplicationDimension
                                                                        .TYPE))),
                        Authentication.currentReactive())
                .switchIfEmpty(Mono.error(new AccessDenyException()))
                .map(
                        t ->
                                new HandoverContext(
                                        t.getT1(),
                                        t.getT2().id(),
                                        t.getT3().getUser().getId(),
                                        t.getT3().getUser().getName()))
                .flatMap(
                        c ->
                                new TenantAssetsHolder(List.of(c.tenantId()), assets)
                                        .assertPermission0(
                                                AssetType.of("business_application"),
                                                List.of(c.applicationId()),
                                                CrudAssetPermission.read)
                                        .thenReturn(c));
    }

    public Mono<Void> project(String projectId, CrudAssetPermission permission) {
        HandoverRules.require(HandoverRules.hasText(projectId), "project_required");
        return AssetsHolder.assertPermission(AssetType.of("organization"), projectId, permission)
                .then();
    }

    public QueryParamEntity mandatory(
            QueryParamEntity input, HandoverContext context, String projectId) {
        QueryParamEntity query =
                input.clone()
                        .toNestQuery()
                        .and("tenantId", context.tenantId())
                        .and("applicationId", context.applicationId())
                        .and("deleted", false)
                        .getParam();
        if (projectId != null) query.toQuery().and("projectId", projectId);
        return query;
    }

    public Mono<QueryParamEntity> query(
            QueryParamEntity input, HandoverContext context, String projectId) {
        // 独立查验库没有认证服务 s_organization 表，不能把组织资产 SQL 条件跨库展开。
        // 项目已固定，先通过真实资产检查再追加不可绕过的项目条件即可。
        return project(projectId, CrudAssetPermission.read)
                .thenReturn(mandatory(input, context, projectId));
    }

    public void owned(HandoverApplicationEntity row, HandoverContext context) {
        if (!Objects.equals(row.getTenantId(), context.tenantId())
                || !Objects.equals(row.getApplicationId(), context.applicationId())
                || Boolean.TRUE.equals(row.getDeleted())) throw new AccessDenyException();
    }

    public void initialize(HandoverApplicationEntity row, HandoverContext context) {
        row.setTenantId(context.tenantId());
        row.setApplicationId(context.applicationId());
        row.setDeleted(false);
        row.setVersion(0);
        row.setCreatorId(context.userId());
        row.setCreatorName(context.userName());
        row.setCreateTime(System.currentTimeMillis());
        row.setModifyTime(row.getCreateTime());
    }
}
