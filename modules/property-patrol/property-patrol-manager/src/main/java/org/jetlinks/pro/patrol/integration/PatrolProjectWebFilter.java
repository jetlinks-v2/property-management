package org.jetlinks.pro.patrol.integration;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.AssetsHolderProvider;
import org.jetlinks.pro.assets.scope.AssetScopeWebFilter;
import org.jetlinks.pro.patrol.asset.PatrolAssetSupplier;
import org.jetlinks.pro.patrol.asset.PatrolProjectAssetsHolder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.util.List;

/** 在平台完成认证及应用 Scope 后装配巡更请求级数据权限；不修改共享基础服务的授权。 */
@Component
@Order(AssetScopeWebFilter.ORDER + 1)
@RequiredArgsConstructor
public class PatrolProjectWebFilter implements WebFilter {
    private final PropertyDirectory directory;
    private final PatrolScope scope;
    private final PatrolAssetSupplier assets;

    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith("/park/patrol/")) return chain.filter(exchange);
        return Authentication.currentReactive().switchIfEmpty(Mono.error(UnAuthorizedException.NoStackTrace::new))
                .flatMap(auth -> Mono.zip(directory.organizations().collectList(), scope.current(),
                        AssetsHolder.currentReactive().defaultIfEmpty(AssetsHolderProvider.compose(List.of()))))
                .flatMap(data -> {
                    var context = new PatrolProjectContext(data.getT1(),
                            exchange.getRequest().getHeaders().getFirst(PatrolProjectContext.HEADER), data.getT2().key());
                    context.attachmentOwner(exchange.getRequest().getHeaders().getFirst("X-Patrol-Owner"));
                    var holder = new PatrolProjectAssetsHolder(data.getT3(), context, assets);
                    // 历史归属入口仅处理未归属数据，沿用原资产授权；业务接口没有此豁免。
                    boolean migration = exchange.getRequest().getPath().value().startsWith("/park/patrol/project-migrations/");
                    return chain.filter(exchange).contextWrite(ctx -> ctx.put(PatrolProjectContext.class, context)
                            .put(AssetsHolder.class, migration ? data.getT3() : holder));
                });
    }
}
