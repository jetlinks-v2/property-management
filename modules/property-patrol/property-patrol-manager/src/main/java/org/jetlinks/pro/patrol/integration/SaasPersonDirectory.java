package org.jetlinks.pro.patrol.integration;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.crud.query.QueryHelper;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.core.command.CommandUtils;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.command.CommandServices;
import org.jetlinks.sdk.server.SdkServices;
import org.jetlinks.sdk.server.auth.AuthCommandSupportTypes;
import org.jetlinks.sdk.server.auth.UserDetail;
import org.jetlinks.pro.property.base.command.QueryPropertyEmployeePageCommand;
import org.jetlinks.pro.property.base.command.GetCurrentPropertyEmployeeCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** 人员目录复用物业有效员工；SaaS 应用成员及 user 资产范围继续保留，本人检查无需用户维护权限。 */
@Component
public class SaasPersonDirectory {
    private final PatrolScope scope;
    private final Supplier<Mono<CommandSupport>> commandSupport;
    private final Supplier<Mono<CommandSupport>> accountSupport;

    @Autowired
    public SaasPersonDirectory(PatrolScope scope) {
        this(
                scope,
                () ->
                        org.jetlinks.pro.command.CommandSupportManagerProviders.getCommandSupport(QueryPropertyEmployeePageCommand.SERVICE_ID),
                () -> org.jetlinks.pro.command.CommandSupportManagerProviders.getCommandSupport("authService:user"));
    }

    SaasPersonDirectory(PatrolScope scope, Supplier<Mono<CommandSupport>> commandSupport,
                        Supplier<Mono<CommandSupport>> accountSupport) {
        this.scope = scope;
        this.commandSupport = commandSupport;
        this.accountSupport = accountSupport;
    }

    public record Person(String id, String name) {}

    public Mono<PagerResult<Person>> query(QueryParamEntity request) {
        return QueryHelper.transformPageResult(queryDetails(request), users -> Flux.fromIterable(users)
                .map(user -> new Person(user.getId(), user.getName())).collectList());
    }

    /** 日历只补充已授权排班人员的联系方式；没有系统用户查询权限时保留排班，联系方式留空。 */
    public Mono<Map<String, String>> telephones(Collection<String> ids) {
        if (ids.isEmpty()) return Mono.just(Map.of());
        return ReactiveAuthenticationHolder.get()
                .filter(auth -> auth.hasPermission("user", "query"))
                .flatMapMany(auth -> Flux.fromIterable(ids).distinct().buffer(200)
                        .concatMap(batch -> {
                            var query = QueryParamEntity.newQuery().in("id", batch).getParam();
                            query.setPageSize(200);
                            // 与人员选择共用当前应用成员和 user 资产读取边界，不凭排班读取权扩张人员权限。
                            return queryDetails(query).flatMapMany(page -> Flux.fromIterable(page.getData()));
                        }))
                .filter(user -> user.getTelephone() != null && !user.getTelephone().isBlank())
                .collectMap(UserDetail::getId, UserDetail::getTelephone);
    }

    private Mono<PagerResult<UserDetail>> queryDetails(QueryParamEntity request) {
        QueryParamEntity parameter = request.clone();
        parameter.setPageSize(Math.min(200, Math.max(1, parameter.getPageSize())));
        parameter.setPaging(true);
        parameter.setPageIndex(Math.max(0, parameter.getPageIndex()));
        return scope.current().zipWith(PatrolProjectContext.current())
                .flatMap(
                        contexts -> {
                            var current = contexts.getT1();
                            var projects = contexts.getT2();
                            parameter.toNestQuery().and("status", 1);
                            if (projects.employeeOrganizations().isEmpty()) parameter.toNestQuery(q -> q.isNull("id").notNull("id"));
                            else parameter.toNestQuery(q -> q.and("id", "property-user-org",
                                    Map.of("orgId$in", projects.employeeOrganizations())));
                            // 应用成员关系只在认证服务中解析；不能把 in-dimension SQL 发送到物业基础数据库。
                            return applicationMemberIds(current.applicationId()).map(ids -> {
                                if (ids.isEmpty()) parameter.toNestQuery(q -> q.isNull("id").notNull("id"));
                                else parameter.toNestQuery(q -> q.in("id", ids));
                                return parameter;
                            });
                        })
                .flatMap(
                        parameterWithScope -> {
                            var command = new QueryPropertyEmployeePageCommand();
                            command.setQuery(parameterWithScope);
                            return commandSupport
                                    .get()
                                    .switchIfEmpty(
                                            Mono.error(
                                                    new BusinessException(
                                                            "error.patrol.person_service_unavailable")))
                            .flatMap(support -> support.execute(command));
                        })
                .map(
                        page ->
                                PagerResult.of(
                                        page.getTotal(),
                                        // RPC 分页元素可能是 Map，先按未知元素读取，再交给平台转换器。
                                        ((List<?>) page.getData()).stream().map(this::toDetail).toList(),
                                        parameter));
    }

    private Mono<List<String>> applicationMemberIds(String applicationId) {
        var query = QueryParamEntity.newQuery().is("status", 1)
                .and("id", "in-dimension$" + BusinessApplicationDimension.TYPE, List.of(applicationId)).getParam();
        query.setPaging(false);
        query.setIncludes(Set.of("id"));
        return AssetsHolder.injectQueryParam(query, "user", "id").flatMap(scoped -> {
            var command = org.jetlinks.sdk.server.commons.cmd.QueryListCommand.of(org.jetlinks.sdk.server.auth.UserInfo.class);
            command.withQueryParam(scoped);
            return accountSupport.get().switchIfEmpty(Mono.error(new BusinessException("error.patrol.person_service_unavailable")))
                    .flatMapMany(support -> support.execute(command))
                    .map(org.jetlinks.sdk.server.auth.UserInfo::getId).distinct().take(10001).collectList()
                    .flatMap(ids -> ids.size() > 10000
                            ? Mono.error(new BusinessException("error.patrol.person_directory_limit")) : Mono.just(ids));
        });
    }

    private UserDetail toDetail(Object value) {
        return (UserDetail) CommandUtils.convertData(ResolvableType.forClass(UserDetail.class), value);
    }

    public Mono<Void> assertMembers(Collection<String> ids) {
        if (ids.isEmpty()) return Mono.empty();
        return ReactiveAuthenticationHolder.get()
                .switchIfEmpty(Mono.error(new AccessDenyException.NoStackTrace()))
                .flatMap(auth -> {
                    if (ids.size() == 1 && ids.contains(auth.getUser().getId())) {
                        // SaaS 认证维度由应用成员提供者构建并随撤权失效；入口 applicationScope 不能作为成员凭据。
                        // 本人执行无需“系统用户查询”权限，任务资产、岗位和排班仍由原执行边界校验。
                        return scope.current().flatMap(current -> auth
                                .getDimensions(BusinessApplicationDimension.TYPE).stream()
                                .anyMatch(dimension -> current.applicationId().equals(dimension.getId()))
                                ? assertCurrentEmployee() : Mono.error(new AccessDenyException.NoStackTrace()));
                    }
                    return assertDirectoryMembers(ids);
                });
    }

    /** 本人身份来自基础服务，停用/移除员工身份后旧岗位和旧幂等键均不能继续执行。 */
    private Mono<Void> assertCurrentEmployee() {
        return commandSupport.get().switchIfEmpty(Mono.error(new BusinessException("error.patrol.base_unavailable")))
                .flatMap(support -> support.execute(new GetCurrentPropertyEmployeeCommand()))
                .switchIfEmpty(Mono.error(new AccessDenyException.NoStackTrace())).then();
    }

    private Mono<Void> assertDirectoryMembers(Collection<String> ids) {
        QueryParamEntity parameter = QueryParamEntity.newQuery().in("id", ids).getParam();
        parameter.setPageSize(200);
        return query(parameter)
                .flatMap(
                        page -> {
                            Set<String> found = new HashSet<>();
                            page.getData().forEach(user -> found.add(user.id()));
                            return found.containsAll(ids)
                                    ? Mono.empty()
                                    : Mono.error(new AccessDenyException.NoStackTrace());
                        });
    }
}
