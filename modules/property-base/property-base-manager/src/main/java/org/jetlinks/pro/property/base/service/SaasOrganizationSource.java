package org.jetlinks.pro.property.base.service;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.sdk.server.auth.OrganizationInfo;
import org.jetlinks.sdk.server.auth.cmd.QueryOrganizationTreeCommand;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.List;

/**
 * 固定、可信 SaaS 集群的全量来源；不接受来自 HTTP 的来源地址或筛选条件。
 * 组织类型维护权限在请求入口校验；此处始终执行同一系统快照，避免个人资产范围造成误删除。
 */
@Component
public class SaasOrganizationSource {
    public Mono<List<OrganizationInfo>> fetchAll() {
        return Mono.defer(
                        () -> {
                            var command = new QueryOrganizationTreeCommand();
                            command.withQueryParam(new QueryParamEntity().noPaging());
                            return CommandSupportManagerProviders.getCommandSupport(
                                            "authService:organization")
                                    .switchIfEmpty(
                                            Mono.error(
                                                    new BusinessException.NoStackTrace(
                                                            "error.property_org_source_unavailable",
                                                            503)))
                                    .flatMapMany(support -> support.execute(command))
                                    .take(OrganizationSnapshot.MAX_NODES + 1L)
                                    .collectList()
                                    .timeout(Duration.ofSeconds(30));
                            // 该服务只被内部同步器调用：不把用户的租户、资产筛选或数据库事务传给全量来源。
                        })
                .contextWrite(ignored -> Context.empty());
    }
}
