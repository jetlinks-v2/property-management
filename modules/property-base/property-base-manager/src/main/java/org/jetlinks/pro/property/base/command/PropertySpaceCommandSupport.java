package org.jetlinks.pro.property.base.command;

import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.assets.AssetsHolder;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.authorize.OrgDimensionType;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.archive.PropertySpaceInfo;
import org.jetlinks.pro.property.base.service.ArchiveScope;
import org.jetlinks.pro.property.base.service.PropertySpaceService;
import org.jetlinks.pro.property.base.space.PropertySpaceOrganizationBinding;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
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
    private final PropertySpaceService spaces;

    /** 从基础服务的统一 Holder 读取实时归属；不把消费方数据库里的旧绑定当成授权来源。 */
    @CommandHandler(name = "查询授权空间组织绑定")
    public Flux<PropertySpaceOrganizationBinding>
            queryOrganizationBindings(QueryPropertySpaceOrganizationBindingsCommand command) {
        var ids = command.getSpaceIds();
        var organizations = command.getOrganizationIds();
        if (ids != null && (ids.size() > 20000 || ids.stream().anyMatch(id -> id == null || id.isBlank()))
                || organizations != null && (organizations.size() > 20000
                || organizations.stream().anyMatch(id -> id == null || id.isBlank())))
            return Flux.error(ArchiveScope.error("too_many", 400));
        return scope.authenticated()
                .then(AssetsHolder.currentReactive())
                .switchIfEmpty(Mono.error(new AccessDenyException()))
                .flatMapMany(holder -> ids == null || ids.isEmpty()
                        ? holder.getBindings(ArchiveScope.SPACE)
                        : holder.getBindings(ArchiveScope.SPACE, ids))
                .filter(binding -> OrgDimensionType.org
                        .isSameType(binding.getTargetType()))
                .filter(binding -> organizations == null || organizations.isEmpty()
                        || organizations.contains(binding.getTargetId()))
                .take(20001).collectList().flatMapMany(bindings -> {
                    if (bindings.size() > 20000)
                        return Flux.error(ArchiveScope.error("too_many", 400));
                    return Flux.fromIterable(bindings)
                            .map(binding -> new PropertySpaceOrganizationBinding(
                                    binding.getTargetId(), binding.getAssetId(),
                                    binding.getPermissionValue(), binding.getRelation()));
                });
    }

    @CommandHandler(name = "查询授权项目空间", description = "返回全部来源层级，不按类型或深度限制")
    public Mono<List<PropertySpaceInfo>> querySpaces(QueryPropertySpaceNodesCommand command) {
        if (command.isCustomerBound()) {
            return spaces.customerTree(command.getProjectId())
                    .map(nodes -> flatten(nodes).toList());
        }
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

    private static java.util.stream.Stream<PropertySpaceInfo> flatten(
            List<PropertySpaceService.SpaceView> nodes) {
        return nodes.stream().flatMap(node -> java.util.stream.Stream.concat(
                java.util.stream.Stream.of(new PropertySpaceInfo(node.getId(), node.getParentId(),
                        node.getRootId(), node.getName(), node.getFullName(), node.getSpaceTypeId(),
                        node.getSpaceTypeName(), node.getLeaf(), node.getCustomerBound())),
                flatten(node.getChildren())));
    }
}
