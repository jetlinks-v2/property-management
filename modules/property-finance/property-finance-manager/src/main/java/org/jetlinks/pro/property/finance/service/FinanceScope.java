package org.jetlinks.pro.property.finance.service;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.QueryPropertyProjectCommand;
import org.jetlinks.pro.property.base.command.QueryPropertyUsersCommand;
import org.jetlinks.pro.property.base.organization.PropertyProjectInfo;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/** 财务项目使用 SaaS organization 资产；基础有效性只通过 core 命令查询，不读基础表。 */
@Component
public class FinanceScope {
    private static final AssetType ORGANIZATION = AssetType.of("organization");

    public Mono<Authentication> authenticated() {
        return Authentication.currentReactive()
                .switchIfEmpty(Mono.error(UnAuthorizedException.NoStackTrace::new));
    }

    /** 审计姓名保存操作当时的显示名；账号仅作缺失姓名时的兜底，授权始终使用用户 ID。 */
    static String operatorName(Authentication auth) {
        var user = auth.getUser();
        return user.getName() == null || user.getName().isBlank()
                ? user.getUsername()
                : user.getName();
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

    /** 按来源操作人批量解析姓名；沿用调用者租户和用户资产权限，不扩大目录可见范围。 */
    public Flux<PropertyUserInfo> users(List<String> ids) {
        if (ids.isEmpty()) return Flux.empty();
        var command = new QueryPropertyUsersCommand();
        command.setIds(ids);
        return CommandSupportManagerProviders.getCommandSupport(
                        QueryPropertyUsersCommand.SERVICE_ID)
                .switchIfEmpty(Mono.error(error("project_source_unavailable", 503)))
                .flatMapMany(support -> support.execute(command));
    }

    public static BusinessException error(String suffix, int status) {
        return new BusinessException.NoStackTrace("error.property_finance_" + suffix, status);
    }
}
