package org.jetlinks.pro.patrol.integration;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.core.command.CommandUtils;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.command.CommandServices;
import org.jetlinks.pro.tenant.TenantConstants;
import org.jetlinks.sdk.server.SdkServices;
import org.jetlinks.sdk.server.auth.AuthCommandSupportTypes;
import org.jetlinks.sdk.server.auth.UserDetail;
import org.jetlinks.sdk.server.auth.cmd.QueryUserDetailPageCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** 人员主数据只读公开 SDK；项目成员、应用成员及 user 资产权限必须同时满足。 */
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
        QueryParamEntity parameter = request.clone();
        parameter.setPageSize(Math.min(200, Math.max(1, parameter.getPageSize())));
        parameter.setPaging(true);
        parameter.setPageIndex(Math.max(0, parameter.getPageIndex()));
        return scope.current()
                .flatMap(
                        current -> {
                            parameter
                                    .toNestQuery()
                                    .and(TenantConstants.Fields.tenantId, current.projectId())
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
                                        page.getData().stream().map(this::toPerson).toList(),
                                        parameter));
    }

    private Person toPerson(Object value) {
        UserDetail user =
                (UserDetail)
                        CommandUtils.convertData(ResolvableType.forClass(UserDetail.class), value);
        return new Person(user.getId(), user.getName());
    }

    public Mono<Void> assertMembers(Collection<String> ids) {
        if (ids.isEmpty()) return Mono.empty();
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
