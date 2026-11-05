package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.sdk.server.auth.RoleInfo;
import org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.Map;

/** 公开认证命令提供的角色候选；角色和成员主档及资产过滤始终由认证服务持有。 */
@Service @RequiredArgsConstructor
public class DecorationRoleService {
    private final DecorationScope scope;
    @Value("${decoration.directory.role-server-id:}")
    private String serverId = "";
    public record Role(String id, String name) {}

    public Mono<PagerResult<Role>> query(QueryParamEntity request) {
        return scope.current().flatMap(ctx -> {
            var query = request.clone(); query.setTotal(null); query.setPaging(true);
            query.setPageIndex(Math.max(0, query.getPageIndex())); query.setPageSize(Math.min(200, Math.max(1, query.getPageSize())));
            query.toNestQuery(q -> q.and("applicationId", ctx.applicationId()));
            var command = QueryPagerCommand.of(RoleInfo.class); command.withQueryParam(query);
            return execute(command).map(page -> {
                var rows = ((java.util.List<?>) page.getData()).stream().map(value -> project(value, ctx.applicationId())).toList();
                // 提供方沿用框架越界回到有效页的语义，投影不得用原请求页码覆盖实际页码。
                query.setPageIndex(page.getPageIndex()); query.setPageSize(page.getPageSize());
                return PagerResult.of(page.getTotal(), rows, query);
            });
        });
    }
    /** 缺命令与合法空结果分别处理；保留调用者认证及应用上下文，不提升为平台身份。 */
    protected Mono<PagerResult<RoleInfo>> execute(QueryPagerCommand<RoleInfo> command) {
        return CommandSupportManagerProviders.getCommandSupport("authService:role")
            .as(CommandSupportManagerProviders.clusterRouter(serverId))
            .filterWhen(support -> support.commandIsSupported(command).defaultIfEmpty(false))
            .switchIfEmpty(Mono.error(DecorationRules.error("role_directory_unavailable", 503)))
            .flatMap(support -> support.execute(command));
    }
    public Mono<Role> require(String id) {
        return query(QueryParamEntity.newQuery().where("id", id).getParam())
            .flatMap(page -> Mono.justOrEmpty(page.getData().stream().filter(role -> id.equals(role.id())).findFirst()))
            .switchIfEmpty(Mono.error(DecorationRules.error("invalid_assignees", 403)));
    }
    private Role project(Object value, String applicationId) {
        // SDK 本地结果用扩展字段，RPC结果为扁平Map，均校验提供方的可信应用投影。
        if (value instanceof RoleInfo info) {
            if (!applicationId.equals(info.extensions().get("applicationId"))) throw new AccessDenyException.NoStackTrace();
            return new Role(info.getId(), info.getName());
        }
        var fields = (Map<?, ?>) value;
        if (!applicationId.equals(fields.get("applicationId"))) throw new AccessDenyException.NoStackTrace();
        return new Role((String) fields.get("id"), (String) fields.get("name"));
    }
}
