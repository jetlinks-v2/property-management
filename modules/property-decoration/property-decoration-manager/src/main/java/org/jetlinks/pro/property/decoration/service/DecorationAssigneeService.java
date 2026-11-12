package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.scope.AssetScope;
import org.jetlinks.pro.assets.scope.AssetScopeResolverManager;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.command.auth.GetAuthenticationCommand;
import org.jetlinks.sdk.server.SdkServices;
import org.jetlinks.sdk.server.auth.UserInfo;
import org.jetlinks.sdk.server.commons.cmd.QueryListCommand;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

import static org.jetlinks.pro.property.decoration.service.DecorationRules.*;

/** 验收候选取当前应用的有效员工成员，并核验候选人自己的项目资产范围。 */
@Service
@RequiredArgsConstructor
public class DecorationAssigneeService {
    private final DecorationScope directory;
    private final AssetScopeResolverManager resolver;
    @Value("${decoration.directory.role-server-id:}")
    private String serverId = "";
    private static final int SCAN_PAGE_SIZE = 200;
    private static final int MAX_CANDIDATES = 1000;

    private static final int MAX_APPLICATION_MEMBERS = 10000;

    /** 应用成员关系由认证服务求值，基础服务只接收授权成员 ID，不执行跨库维度条件。 */
    private QueryParamEntity employeeQuery(QueryParamEntity input, List<String> members) {
        var query = input.clone();
        query.setTotal(null);
        query.setPaging(true);
        query.setIncludes(Set.of());
        query.setExcludes(Set.of());
        // 强制范围置于调用方 OR 条件之外；有效员工及 user 资产权限继续由基础命令校验。
        query.toNestQuery(nest -> nest.in("id", members));
        if (query.getSorts().stream().noneMatch(sort -> "id".equals(sort.getName()))) {
            query.toQuery().orderByAsc("id");
        }
        return query;
    }

    protected Mono<List<String>> applicationMemberIds(String applicationId, List<String> selectedIds) {
        var query = QueryParamEntity.newQuery().is("status", 1)
            .and("id", "in-dimension$" + BusinessApplicationDimension.TYPE, List.of(applicationId)).getParam();
        if (!selectedIds.isEmpty()) query.toNestQuery(nest -> nest.in("id", selectedIds));
        query.setPaging(false);
        query.setIncludes(Set.of("id"));
        // 与巡更共用公开用户命令和 user 资产过滤，不切换为平台账号或写入应用成员。
        return AssetsHolder.injectQueryParam(query, "user", "id").flatMap(scoped -> {
            var command = QueryListCommand.of(UserInfo.class);
            command.withQueryParam(scoped);
            return executeMembers(command).map(UserInfo::getId).distinct()
                .take(MAX_APPLICATION_MEMBERS + 1L).collectList();
        }).flatMap(ids -> ids.size() > MAX_APPLICATION_MEMBERS
            ? Mono.error(error("assignee_member_limit", 400)) : Mono.just(ids));
    }

    /** 沿用公开认证节点，成员目录故障直接传播，不回退到全量员工。 */
    protected Flux<UserInfo> executeMembers(QueryListCommand<UserInfo> command) {
        return CommandSupportManagerProviders.getCommandSupport("authService:user")
            .as(CommandSupportManagerProviders.clusterRouter(serverId))
            .filterWhen(support -> support.commandIsSupported(command).defaultIfEmpty(false))
            .switchIfEmpty(Mono.error(error("assignee_directory_unavailable", 503)))
            .flatMapMany(support -> support.execute(command));
    }

    public Mono<PagerResult<PropertyEmployeeInfo>> query(String projectId, QueryParamEntity input,
                                                        DecorationScope.Context ctx) {
        var output = input.clone();
        output.setPageIndex(Math.max(0, output.getPageIndex()));
        output.setPageSize(Math.max(1, Math.min(100, output.getPageSize())));
        return applicationMemberIds(ctx.applicationId(), List.of()).flatMap(members -> {
            if (members.isEmpty()) return Mono.just(PagerResult.of(0, List.of(), output));
            return queryEmployees(projectId, employeeQuery(input, members), output, ctx);
        });
    }

    private Mono<PagerResult<PropertyEmployeeInfo>> queryEmployees(String projectId, QueryParamEntity query,
                                                                  QueryParamEntity output, DecorationScope.Context ctx) {
        query.setPageIndex(0);
        query.setPageSize(SCAN_PAGE_SIZE);
        return directory.employees(query).flatMap(first -> {
            if (first.getTotal() > MAX_CANDIDATES) {
                return Mono.error(error("assignee_search_too_broad", 400));
            }
            int pages = (int) ((first.getTotal() + SCAN_PAGE_SIZE - 1) / SCAN_PAGE_SIZE);
            // 项目权限属于每位候选人；必须过滤后分页，不能返回过滤前的 total。
            return Flux.concat(Flux.fromIterable(first.getData()), Flux.range(1, Math.max(0, pages - 1))
                    .concatMap(index -> {
                        var next = query.clone(); next.setPageIndex(index);
                        return directory.employees(next).flatMapMany(page -> {
                            if (page.getTotal() > MAX_CANDIDATES) {
                                return Flux.error(error("assignee_search_too_broad", 400));
                            }
                            return Flux.fromIterable(page.getData());
                        });
                    }))
                .distinct(PropertyEmployeeInfo::id)
                .flatMapSequential(person -> canReadProject(person.id(), projectId, ctx)
                    .filter(Boolean.TRUE::equals).map(allowed -> person), 4)
                .collectList().map(matches -> {
                    int start = (int) Math.min(matches.size(), (long) output.getPageIndex() * output.getPageSize());
                    return PagerResult.of(matches.size(), matches.subList(start,
                        Math.min(matches.size(), start + output.getPageSize())), output);
                });
        });
    }

    public Mono<PropertyEmployeeInfo> eligible(String userId, String projectId, DecorationScope.Context ctx) {
        // 提交时实时重查单个人的成员身份，不能依赖此前下拉结果或入口 applicationScope。
        return applicationMemberIds(ctx.applicationId(), List.of(userId))
            .filter(members -> members.contains(userId))
            .switchIfEmpty(Mono.error(error("assignee_employee_scope_required", 403)))
            .flatMap(members -> directory.employees(employeeQuery(
                QueryParamEntity.newQuery().where("id", userId).getParam(), List.of(userId))))
            .flatMap(page -> Mono.justOrEmpty(page.getData().stream().filter(p -> userId.equals(p.id())).findFirst()))
            .switchIfEmpty(Mono.error(error("assignee_employee_scope_required", 403)))
            .flatMap(person -> canReadProject(userId, projectId, ctx).filter(Boolean.TRUE::equals)
                .switchIfEmpty(Mono.error(error("assignee_project_permission_required", 403))).thenReturn(person));
    }

    private Mono<Boolean> canReadProject(String userId, String projectId, DecorationScope.Context ctx) {
        return authentication(userId)
            .flatMap(auth -> resolver.resolve(AssetScope.of(BusinessApplicationDimension.TYPE, ctx.applicationId()), auth))
            // 只在此次只读命令内切换候选人并移除指派人的 holder；基础服务求值项目权限。
            .flatMap(active -> directory.canReadProject(projectId)
                .contextWrite(context -> active.write().apply(context.delete(AssetsHolder.class))))
            .defaultIfEmpty(false);
    }

    /** 与角色目录选择同一个认证节点；公开命令先校验调用者的 user 资产读权限。
     * 不使用集群散列选取认证节点或其故障缓存，也不把指派人的认证当作候选人的认证。
     */
    protected Mono<Authentication> authentication(String userId) {
        var command = new GetAuthenticationCommand().setUserId(userId);
        return CommandSupportManagerProviders.getCommandSupport(SdkServices.authService)
            .as(CommandSupportManagerProviders.clusterRouter(serverId))
            .filterWhen(support -> support.commandIsSupported(command).defaultIfEmpty(false))
            .switchIfEmpty(Mono.error(error("assignee_directory_unavailable", 503)))
            // 该公开命令声明普通 Authentication，远程代理异步返回，使用显式命令的响应式适配。
            .flatMap(support -> support.executeToMono(command)).cast(Authentication.class)
            .filter(auth -> auth.getUser() != null && userId.equals(auth.getUser().getId()))
            .switchIfEmpty(Mono.error(new AccessDenyException()));
    }
}
