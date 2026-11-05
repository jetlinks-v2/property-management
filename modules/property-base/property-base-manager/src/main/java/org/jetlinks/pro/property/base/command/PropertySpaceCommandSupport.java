package org.jetlinks.pro.property.base.command;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.archive.PropertySpaceInfo;
import org.jetlinks.pro.property.base.service.ArchiveScope;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 供消费模块按真实空间身份配置范围；保留调用者的项目及space资产权限。
 *
 * @since 2.20.0
 * @see QueryPropertySpaceNodesCommand
 */
@Component
@Authorize
@RequiredArgsConstructor
@CommandService(id = QueryPropertySpaceNodesCommand.SERVICE_ID, name = "物业空间资料服务")
public class PropertySpaceCommandSupport {
    private final ArchiveScope scope;

    @CommandHandler(name = "查询授权项目空间", description = "返回全部来源层级，不按类型或深度限制")
    public Mono<List<PropertySpaceInfo>> querySpaces(QueryPropertySpaceNodesCommand command) {
        return scope.spaces(command.getProjectId(), null, CrudAssetPermission.read)
                .map(
                        nodes ->
                                nodes.stream()
                                        .map(
                                                node ->
                                                        new PropertySpaceInfo(
                                                                node.getId(),
                                                                node.getParentId(),
                                                                node.getRootId(),
                                                                node.getName(),
                                                                node.getFullName(),
                                                                node.getSpaceTypeId(),
                                                                node.getSpaceTypeName(),
                                                                node.getLeaf()))
                                        .toList());
    }
}
