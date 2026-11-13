package org.jetlinks.pro.patrol.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.DefaultDimensionType;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.command.CommandUtils;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.patrol.entity.PatrolRole;
import org.jetlinks.pro.patrol.integration.PatrolScope;
import org.jetlinks.sdk.server.auth.RoleInfo;
import org.jetlinks.sdk.server.auth.UserInfo;
import org.jetlinks.sdk.server.commons.cmd.QueryListCommand;
import org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Set;

/** 巡更直接读取认证服务的当前应用角色；不持有角色 Repository 或角色及成员副本。 */
@Service
@RequiredArgsConstructor
public class PatrolRoleService {
    private final PatrolScope scope;

    public Mono<PagerResult<PatrolRole>> queryPager(QueryParamEntity request) {
        return queryPager(request, true);
    }

    /** 名称回显不读取成员；角色查询仍由认证命令校验当前调用者的角色资产权限。 */
    public Mono<PagerResult<PatrolRole>> queryPager(QueryParamEntity request, boolean includeMembers) {
        return scope.current().flatMap(current -> {
            var query = request.clone();
            query.setTotal(null);
            query.setPaging(true);
            query.setPageIndex(Math.max(0, query.getPageIndex()));
            query.setPageSize(Math.min(200, Math.max(1, query.getPageSize())));
            // 整体嵌套调用者条件，应用范围只能来自可信 Scope，不能被外部 OR 条件扩大。
            query.toNestQuery(q -> q.and("applicationId", current.applicationId()));
            var command = QueryPagerCommand.of(RoleInfo.class);
            command.withQueryParam(query);
            return execute(command).flatMap(page -> {
                List<PatrolRole> rows = ((List<?>) page.getData()).stream().map(value -> {
                    var role = toRole(value);
                    if (!current.applicationId().equals(role.getApplicationId()))
                        throw new AccessDenyException.NoStackTrace();
                    role.setScopeKey(current.key());
                    // 不暴露认证角色投影中可能携带的、未经人员资产过滤的成员快照。
                    role.setUserIds(List.of());
                    return role;
                }).toList();
                if (!includeMembers) return Mono.just(PagerResult.of(page.getTotal(), rows, query));
                return Flux.fromIterable(rows)
                        .flatMapSequential(role -> members(role, current.applicationId()), 4)
                        .collectList()
                        .map(enriched -> PagerResult.of(page.getTotal(), enriched, query));
            });
        });
    }

    /** 直接执行已公开的只读角色命令，保留调用者认证、应用 Scope 和 role/user 资产边界。 */
    protected Mono<PagerResult<RoleInfo>> execute(QueryPagerCommand<RoleInfo> command) {
        return CommandSupportManagerProviders.getCommandSupport("authService:role")
                .switchIfEmpty(Mono.error(new BusinessException("error.patrol.application_role_unavailable", 503)))
                .flatMap(support -> support.execute(command));
    }

    /** 成员关系使用现有用户命令实时解析，不依赖角色投影携带成员，也不在巡更保存成员副本。 */
    private Mono<PatrolRole> members(PatrolRole role, String applicationId) {
        var query = QueryParamEntity.newQuery().is("status", 1)
                .and("id", "in-dimension$" + DefaultDimensionType.role.getId(), List.of(role.getId()))
                .and("id", "in-dimension$" + BusinessApplicationDimension.TYPE, List.of(applicationId))
                .getParam();
        query.setPaging(false);
        query.setIncludes(Set.of("id"));
        // 维度条件在认证库执行；调用者的 user 资产和当前应用 Scope 随命令保留。
        return AssetsHolder.injectQueryParam(query, "user", "id").flatMap(scoped -> {
            var command = QueryListCommand.of(UserInfo.class);
            command.withQueryParam(scoped);
            return executeMembers(command).map(UserInfo::getId).distinct().take(10001).collectList();
        }).map(ids -> {
            if (ids.size() > 10000) throw new BusinessException("error.patrol.person_directory_limit");
            role.setUserIds(ids);
            return role;
        });
    }

    /** 仅调用已存在的 authService:user；权限不足或远程失败直接传播，不回退未过滤成员。 */
    protected Flux<UserInfo> executeMembers(QueryListCommand<UserInfo> command) {
        return CommandSupportManagerProviders.getCommandSupport("authService:user")
                .switchIfEmpty(Mono.error(new BusinessException("error.patrol.person_service_unavailable")))
                .flatMapMany(support -> support.execute(command));
    }

    private PatrolRole toRole(Object value) {
        // 本地 SDK 使用扩展字段，RPC 使用扁平 Map，统一转换为巡更只读投影。
        if (value instanceof RoleInfo info) {
            var fields = new java.util.HashMap<String, Object>(info.extensions());
            fields.put("id", info.getId());
            fields.put("name", info.getName());
            value = fields;
        }
        return (PatrolRole) CommandUtils.convertData(ResolvableType.forClass(PatrolRole.class), value);
    }

    public Flux<PatrolRole> query(QueryParamEntity query) {
        if (query.isPaging()) return queryPager(query).flatMapMany(page -> Flux.fromIterable(page.getData()));
        var first = query.clone(); first.setPageIndex(0); first.setPageSize(200);
        // 模板需完整读取角色；逐页取到框架 total，不能把第一页当作全部角色。
        return queryPager(first).expand(page -> {
            int nextIndex = page.getPageIndex() + 1;
            if ((long) nextIndex * 200 >= page.getTotal()) return Mono.empty();
            var next = query.clone(); next.setPageIndex(nextIndex); next.setPageSize(200);
            return queryPager(next);
        }).concatMapIterable(PagerResult::getData);
    }

    public Mono<PatrolRole> findById(String id) {
        return query(QueryParamEntity.newQuery().is("id", id).getParam()).next();
    }

    /** 本人执行只验证认证服务构建的有效应用角色，不要求执行人具有角色/用户维护权限。 */
    public Mono<PatrolRole> currentRole(String roleId) {
        return scope.current().zipWith(Authentication.currentReactive()).flatMap(pair -> {
            var role = pair.getT2().getDimensions(DefaultDimensionType.role).stream()
                    .filter(dimension -> roleId.equals(dimension.getId()))
                    .filter(dimension -> pair.getT1().applicationId().equals(dimension.getOptions().get("applicationId")))
                    .findFirst();
            if (role.isEmpty()) return Mono.error(new BusinessException("error.patrol.execution.unassigned", 400));
            var result = new PatrolRole();
            result.setId(roleId); result.setName(role.get().getName()); result.setEnabled(true);
            result.setScopeKey(pair.getT1().key()); result.setUserIds(List.of(pair.getT2().getUser().getId()));
            return Mono.just(result);
        });
    }
}
