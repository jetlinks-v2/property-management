package org.jetlinks.pro.smartpark.handover.asset;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.smartpark.handover.entity.HandoverResponsibilityEntity;
import org.jetlinks.pro.smartpark.handover.service.*;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.List;

/** 查询、详情、计数及导出共享项目、实时外协资格和平台组织资产边界。 */
@Component
@RequiredArgsConstructor
public class HandoverProblemAccess {
    private final HandoverScope scope;
    private final HandoverDirectory directory;
    private final HandoverResponsibilityRepository responsibilities;

    public Mono<QueryParamEntity> selector(HandoverQuery input) {
        return scope.current()
                .flatMap(
                        context ->
                                scope.query(
                                        input.parameter("description"),
                                        context,
                                        input.getProjectId()))
                .flatMap(
                        query ->
                                directory
                                        .currentEmployee()
                                        .hasElement()
                                        .flatMap(
                                                employee -> {
                                                    if (employee
                                                            && !"construction"
                                                                    .equals(input.getView()))
                                                        return Mono.just(query);
                                                    return constructionOrganizations(
                                                                    input.getProjectId())
                                                            .map(
                                                                    ids -> {
                                                                        if (ids.isEmpty())
                                                                            query.toQuery()
                                                                                    .isNull("id")
                                                                                    .notNull("id");
                                                                        else
                                                                            query.toQuery()
                                                                                    .in(
                                                                                            "organizationId",
                                                                                            ids);
                                                                        return query;
                                                                    });
                                                }));
    }

    private Mono<List<String>> constructionOrganizations(String projectId) {
        return directory
                .directOrganizations()
                .flatMap(
                        direct -> {
                            if (direct.isEmpty()) return Mono.just(List.of());
                            // 组织取消外协标记后，旧配置不再授予施工访问资格。
                            return directory
                                    .organizations(projectId)
                                    .filter(org -> direct.contains(org.getId()))
                                    .map(org -> org.getId())
                                    .distinct()
                                    .take(201)
                                    .collectList()
                                    .flatMap(
                                            external -> {
                                                HandoverRules.require(
                                                        external.size() <= 200,
                                                        "organization_limit");
                                                if (external.isEmpty()) return Mono.just(List.of());
                                                return scope.current()
                                                        .flatMap(
                                                                context ->
                                                                        AssetsHolder.filterAssets(
                                                                                        responsibilities
                                                                                                .createQuery()
                                                                                                .setParam(
                                                                                                        scope
                                                                                                                .mandatory(
                                                                                                                        new QueryParamEntity(),
                                                                                                                        context,
                                                                                                                        projectId))
                                                                                                .in(
                                                                                                        "organizationId",
                                                                                                        external)
                                                                                                .and(
                                                                                                        "enabled",
                                                                                                        true)
                                                                                                .fetch(),
                                                                                        AssetType
                                                                                                .of(
                                                                                                        "organization"),
                                                                                        HandoverResponsibilityEntity
                                                                                                ::getOrganizationId,
                                                                                        CrudAssetPermission
                                                                                                .read)
                                                                                .map(
                                                                                        HandoverResponsibilityEntity
                                                                                                ::getOrganizationId)
                                                                                .distinct()
                                                                                .take(201)
                                                                                .collectList());
                                            });
                        });
    }
}
