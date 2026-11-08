package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.scope.AssetScope;
import org.jetlinks.pro.assets.scope.AssetScopeResolverManager;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.command.auth.GetAuthenticationCommand;
import org.jetlinks.sdk.server.SdkServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import static org.jetlinks.pro.property.decoration.service.DecorationRules.*;

/** 被指派人必须是当前应用内有任务办理权限和目标项目资产范围的有效员工。 */
@Service
@RequiredArgsConstructor
public class DecorationAssigneeService {
    private final DecorationScope directory;
    private final AssetScopeResolverManager resolver;
    @Value("${decoration.directory.role-server-id:}")
    private String serverId = "";
    public Mono<PropertyEmployeeInfo> eligible(String userId, String projectId, DecorationScope.Context ctx) {
        return directory.employees(QueryParamEntity.newQuery().where("id", userId).getParam())
            .flatMap(page -> Mono.justOrEmpty(page.getData().stream().filter(p -> userId.equals(p.id())).findFirst()))
            .switchIfEmpty(Mono.error(error("employee_required", 403)))
            .flatMap(person -> authentication(userId)
                .flatMap(auth -> resolver.resolve(AssetScope.of(BusinessApplicationDimension.TYPE, ctx.applicationId()), auth))
                .filter(active -> active.authentication().hasPermission("decoration-task", "handle"))
                .switchIfEmpty(Mono.error(error("assignee_application_permission_required", 403)))
                // 组织主档及其 Scope 成员关系由基础服务持有，不能在装修库求值。
                // 只在此次只读命令内切换为候选人；清除调用者显式 holder，防止误用指派人的数据范围。
                .flatMap(active -> directory.projects().any(project -> projectId.equals(project.getId()))
                    .contextWrite(context -> active.write().apply(context.delete(AssetsHolder.class))))
                .filter(Boolean.TRUE::equals)
                .switchIfEmpty(Mono.error(error("assignee_project_permission_required", 403))).thenReturn(person));
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
