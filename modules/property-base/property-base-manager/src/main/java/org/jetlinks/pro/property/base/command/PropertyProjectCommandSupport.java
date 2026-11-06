package org.jetlinks.pro.property.base.command;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.organization.PropertyProjectInfo;
import org.jetlinks.pro.property.base.service.ArchiveScope;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * 项目只读公共边界，财务/巡更只依赖 core；沿用命令调用者 Authentication 与 AssetsHolder。
 *
 * @see QueryPropertyProjectCommand
 * @since 2.20.0
 */
@Component
@Authorize
@RequiredArgsConstructor
@CommandService(id = QueryPropertyProjectCommand.SERVICE_ID, name = "物业项目服务")
public class PropertyProjectCommandSupport {
    private final ArchiveScope scope;

    @CommandHandler(name = "查询物业项目", description = "按标注及资产权限校验项目后返回稳定标识")
    public Mono<PropertyProjectInfo> queryProject(QueryPropertyProjectCommand command) {
        return scope.project(command.getProjectId(), CrudAssetPermission.read)
                .map(project -> new PropertyProjectInfo(project.getId(), project.getName()));
    }
}
