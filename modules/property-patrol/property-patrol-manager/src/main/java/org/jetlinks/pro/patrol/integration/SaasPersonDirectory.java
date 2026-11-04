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
import org.jetlinks.sdk.server.auth.cmd.QueryUserDetailPageCommand;
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

/** 人员目录沿用 SaaS SDK 和 user 资产权限；本人资格取自 SaaS 成员维度，不接入园区员工或业主表。 */
@Component
public class SaasPersonDirectory {
    private final PatrolScope scope;
    private final Supplier<Mono<CommandSupport>> commandSupport;

    @Autowired
    public SaasPersonDirectory(PatrolScope scope) {
        this(
                scope,
                () ->
                        CommandServices.getReactive(
                                SdkServices.authService, AuthCommandSupportTypes.user));
    }

    SaasPersonDirectory(PatrolScope scope, Supplier<Mono<CommandSupport>> commandSupport) {
        this.scope = scope;
        this.commandSupport = commandSupport;
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
        return scope.current()
                .flatMap(
                        current -> {
                            parameter
                                    .toNestQuery()
                                    .and("status", 1)
                                    .and(
                                            "id",
                                            "in-dimension$" + BusinessApplicationDimension.TYPE,
                                            List.of(current.applicationId()));
                            return AssetsHolder.injectQueryParam(parameter, "user", "id");
                        })
                .flatMap(
                        parameterWithScope -> {
                            var command = new QueryUserDetailPageCommand();
                            command.withQueryParam(parameterWithScope);
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
                                ? Mono.<Void>empty() : Mono.error(new AccessDenyException.NoStackTrace()));
                    }
                    return assertDirectoryMembers(ids);
                });
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
