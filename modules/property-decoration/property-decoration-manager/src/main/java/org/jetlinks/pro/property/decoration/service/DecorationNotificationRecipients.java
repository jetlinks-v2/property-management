package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.scope.AssetScope;
import org.jetlinks.pro.assets.scope.AssetScopeResolverManager;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.decoration.entity.DecorationNotificationEntity;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.jetlinks.sdk.server.auth.UserInfo;
import org.jetlinks.sdk.server.commons.cmd.QueryListCommand;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** 后台仅恢复冻结的指派人/收件人身份；不创建平台管理员，不以业主权限枚举员工。 */
@Service @RequiredArgsConstructor
public class DecorationNotificationRecipients {
    private final DecorationScope scope;
    private final AssetScopeResolverManager resolver;
    @Value("${decoration.directory.role-server-id:}") private String serverId = "";

    public Flux<String> roleMembers(DecorationNotificationEntity notice) {
        return withUser(notice.getDispatcherId(), notice, () -> {
            var query = QueryParamEntity.newQuery().is("status", 1)
                .and("id", "in-dimension$role", List.of(notice.getRoleId()))
                .and("id", "in-dimension$" + BusinessApplicationDimension.TYPE, List.of(notice.getApplicationId())).getParam();
            query.setPaging(false); query.setIncludes(Set.of("id"));
            return AssetsHolder.injectQueryParam(query, "user", "id").flatMap(scoped -> {
                var command = QueryListCommand.of(UserInfo.class); command.withQueryParam(scoped);
                return executeMembers(command).map(UserInfo::getId).distinct().take(10001).collectList();
            }).flatMap(ids -> ids.size() > 10000 ? Mono.error(DecorationRules.error("assignee_member_limit", 400)) : Mono.just(ids));
        }).flatMapMany(Flux::fromIterable)
            .filterWhen(user -> allowed(user, notice), 4);
    }

    public Mono<Boolean> allowed(String userId, DecorationNotificationEntity notice) {
        return withUser(userId, notice, () -> Authentication.currentReactive()
            .filter(auth -> auth.hasPermission("decoration-task", "query")
                && auth.hasPermission("decoration-task", notice.getRoleId() == null ? "handle" : "review")
                && (notice.getRoleId() == null || auth.getDimensions("role").stream().anyMatch(role -> notice.getRoleId().equals(role.getId()))))
            .flatMap(auth -> scope.employee().then(scope.canReadProject(notice.getProjectId())))
            .defaultIfEmpty(false))
            .onErrorReturn(AccessDenyException.class, false)
            .onErrorResume(org.hswebframework.web.exception.BusinessException.class, error ->
                "error.decoration.employee_required".equals(error.getMessage()) ? Mono.just(false) : Mono.error(error));
    }

    protected Mono<Authentication> authentication(String userId) {
        return ReactiveAuthenticationHolder.get(userId);
    }

    protected Flux<UserInfo> executeMembers(QueryListCommand<UserInfo> command) {
        return CommandSupportManagerProviders.getCommandSupport("authService:user")
            .as(CommandSupportManagerProviders.clusterRouter(serverId))
            .filterWhen(support -> support.commandIsSupported(command).defaultIfEmpty(false))
            .switchIfEmpty(Mono.error(DecorationRules.error("assignee_directory_unavailable", 503)))
            .flatMapMany(support -> support.execute(command));
    }

    private <T> Mono<T> withUser(String userId, DecorationNotificationEntity notice, Supplier<Mono<T>> operation) {
        // Scope 只收窄数据范围，不证明应用成员资格；恢复前显式拒绝已移除的成员。
        return Mono.justOrEmpty(userId).filter(id -> !id.isBlank()).flatMap(this::authentication)
            .filter(auth -> auth.getUser() != null && userId.equals(auth.getUser().getId())
                && auth.getDimensions(BusinessApplicationDimension.TYPE).stream().anyMatch(app ->
                    notice.getApplicationId().equals(app.getId()) && !org.jetlinks.pro.assets.scope.AssetScopeActivation.isScopeOnly(app)))
            .flatMap(auth -> resolver.resolve(AssetScope.of(BusinessApplicationDimension.TYPE, notice.getApplicationId()), auth))
            .flatMap(active -> Mono.defer(operation).contextWrite(context -> active.write().apply(context.delete(AssetsHolder.class))))
            .switchIfEmpty(Mono.error(new AccessDenyException()))
            .contextWrite(TenantHolder.makeCurrent(notice.getTenantId()));
    }
}
