package org.jetlinks.pro.property.base.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 供前端及部署检查读取 SaaS 已认证的请求上下文，不承担登录和项目切换。
 * 通过平台鉴权保护，业务接口仍须独立声明资源动作和资产权限。
 */
@RestController
@RequestMapping("/property")
@Authorize
@Tag(name = "物业管理上下文")
public class PropertyContextController {

    @GetMapping("/context")
    @Operation(summary = "获取当前登录与项目上下文")
    public Mono<PropertyContext> current() {
        // 只读取平台已建立的响应式上下文，不接受请求参数指定用户或项目。
        return Authentication.currentReactive()
            .switchIfEmpty(Mono.error(UnAuthorizedException.NoStackTrace::new))
            .flatMap(authentication -> TenantHolder.currentId()
                .map(projectId -> new PropertyContext(authentication.getUser().getId(),
                                                     authentication.getUser().getUsername(), projectId))
                .switchIfEmpty(Mono.fromSupplier(() -> new PropertyContext(
                    authentication.getUser().getId(), authentication.getUser().getUsername(), null))));
    }
}
