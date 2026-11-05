package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.scope.AssetScope;
import org.jetlinks.pro.assets.scope.AssetScopeResolverManager;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.List;
import static org.jetlinks.pro.property.decoration.service.DecorationRules.*;

/** 被指派人必须是当前应用内有任务办理权限和目标项目资产范围的有效员工。 */
@Service
@RequiredArgsConstructor
public class DecorationAssigneeService {
    private final DecorationScope directory;
    private final AssetScopeResolverManager resolver;
    public Mono<PropertyEmployeeInfo> eligible(String userId, String projectId, DecorationScope.Context ctx) {
        return directory.employees(QueryParamEntity.newQuery().where("id", userId).getParam())
            .flatMap(page -> Mono.justOrEmpty(page.getData().stream().filter(p -> userId.equals(p.id())).findFirst()))
            .switchIfEmpty(Mono.error(error("employee_required", 403)))
            .flatMap(person -> ReactiveAuthenticationHolder.get(userId)
                .flatMap(auth -> resolver.resolve(AssetScope.of(BusinessApplicationDimension.TYPE, ctx.applicationId()), auth))
                .filter(active -> active.authentication().hasPermission("decoration-task", "handle"))
                .flatMap(active -> AssetsHolder.fromAuth(active.authentication(), active.context()))
                .flatMap(holder -> holder.hasPermission(AssetType.of("organization"), List.of(projectId), CrudAssetPermission.read))
                .filter(Boolean.TRUE::equals).switchIfEmpty(Mono.error(new AccessDenyException())).thenReturn(person));
    }
}
