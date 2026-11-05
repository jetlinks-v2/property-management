package org.jetlinks.pro.property.finance.service;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.QueryPropertyProjectCommand;
import org.jetlinks.pro.property.base.organization.PropertyProjectInfo;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/** 财务项目使用 SaaS organization 资产；基础有效性只通过 core 命令查询，不读基础表。 */
@Component
public class FinanceScope {
    private static final AssetType ORGANIZATION = AssetType.of("organization");

    public Mono<Authentication> authenticated() {
        return Authentication.currentReactive()
                .switchIfEmpty(Mono.error(UnAuthorizedException.NoStackTrace::new));
    }

    public Mono<PropertyProjectInfo> project(String id, CrudAssetPermission permission) {
        if (id == null || id.isBlank()) return Mono.error(error("project_required", 400));
        return authenticated()
                .then(AssetsHolder.assertPermission(ORGANIZATION, id, permission))
                .then(
                        Mono.defer(
                                () -> {
                                    var command = new QueryPropertyProjectCommand();
                                    command.setProjectId(id);
                                    // 保留当前认证、资产和命令上下文；来源失败不能当作本地合法项目。
                                    return CommandSupportManagerProviders.getCommandSupport(
                                                    QueryPropertyProjectCommand.SERVICE_ID)
                                            .switchIfEmpty(
                                                    Mono.error(
                                                            error(
                                                                    "project_source_unavailable",
                                                                    503)))
                                            .flatMap(support -> support.execute(command));
                                }));
    }

    public static BusinessException error(String suffix, int status) {
        return new BusinessException.NoStackTrace("error.property_finance_" + suffix, status);
    }
}
