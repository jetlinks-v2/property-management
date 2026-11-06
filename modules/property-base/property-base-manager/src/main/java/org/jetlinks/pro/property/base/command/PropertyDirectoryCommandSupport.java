package org.jetlinks.pro.property.base.command;

import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.assets.AssetManager;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.property.base.entity.PropertyOrganizationEntity;
import org.jetlinks.pro.property.base.entity.PropertyUserEntity;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.base.service.ArchiveScope;
import org.jetlinks.pro.property.base.service.PropertyUserScope;
import org.jetlinks.pro.property.base.space.PropertySpaceInfo;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import org.jetlinks.pro.tenant.assets.TenantAssetsHolder;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;

/** 报修等消费方的只读边界：基础标注只从 pm 表读取，按调用者资产与当前租户/应用范围取交集。 */
@Component @Authorize @RequiredArgsConstructor
@CommandService(id = QueryPropertyUsersCommand.SERVICE_ID, name = "物业基础目录", description = "项目、身份与空间归属的只读公共契约")
public class PropertyDirectoryCommandSupport {
    private final ReactiveRepository<PropertyOrganizationEntity, String> organizations;
    private final ReactiveRepository<PropertyUserEntity, String> users;
    private final ArchiveScope archive;
    private final AssetManager assetManager;
    private final PropertyUserScope userScope;

    @CommandHandler(name = "查询物业项目")
    public Flux<PropertyOrganizationInfo> projects(QueryPropertyProjectsCommand command) {
        return readableProjects(null).map(row -> {
            var info = new PropertyOrganizationInfo();
            info.setId(row.getId()); info.setName(row.getName()); info.setParentId(row.getParentId());
            info.setCode(row.getCode()); info.setSortIndex(row.getSortIndex());
            info.setOrganizationType(row.getOrganizationType()); info.setChildren(List.of());
            return info;
        });
    }

    /**
     * pm 组织镜像不含 Cloud 的 s_organization，不能在镜像 SQL 中展开 organization 资产条件。
     * 通过资产服务批量过滤后再与可信租户取交集；命令只返回授权目录，不在消费方复制基础表。
     */
    private Flux<PropertyOrganizationEntity> readableProjects(String projectId) {
        var query = QueryParamEntity.newQuery().where("deleted", false).and("organizationType", "project")
            .when(projectId != null, q -> q.and("id", projectId))
            .orderByAsc("name").orderByAsc("id").noPaging().getParam();
        return archive.authenticated()
            .then(TenantHolder.currentId().switchIfEmpty(Mono.error(new AccessDenyException())))
            .flatMapMany(tenant -> {
                Flux<PropertyOrganizationEntity> candidates = organizations.createQuery().setParam(query).fetch()
                    .take(20_001).collectList().flatMapMany(rows -> rows.size() > 20_000
                        ? Flux.error(ArchiveScope.error("too_many", 400)) : Flux.fromIterable(rows));
                return new TenantAssetsHolder(List.of(tenant), assetManager).filter(
                    AssetsHolder.filterAssets(candidates, ArchiveScope.ORGANIZATION,
                        PropertyOrganizationEntity::getId, CrudAssetPermission.read),
                    ArchiveScope.ORGANIZATION, PropertyOrganizationEntity::getId, false, CrudAssetPermission.read);
            });
    }

    @CommandHandler(name = "查询物业用户身份")
    public Flux<PropertyUserInfo> users(QueryPropertyUsersCommand command) {
        if (command.getIds() == null || command.getIds().size() > 20_000)
            return Flux.error(ArchiveScope.error("too_many", 400));
        var query = QueryParamEntity.newQuery().where("deleted", false)
            .when(!command.getIds().isEmpty(), q -> q.in("id", command.getIds()))
            .when(command.isEmployeesOnly(), q -> q.and("employee", true).and("status", 1))
            .orderByAsc("id").noPaging().getParam();
        return Flux.deferContextual(context -> {
                String tenant = TenantHolder.readTenantId(context);
                if (tenant == null && !AssetScopeContext.current(context).contains("business_application"))
                    return Flux.error(new AccessDenyException());
                // 应用入口没有显式租户；userScope 仍由平台 Holder 校验应用 Scope 与原 user 权限。
                // 显式选择了租户时继续叠加租户边界，不从应用 ID 或操作者推断租户。
                return userScope.query(query).flatMapMany(q -> {
                    var readable = users.createQuery().setParam(q).fetch();
                    return tenant == null ? readable : new TenantAssetsHolder(List.of(tenant), assetManager)
                        .filter(readable, AssetType.of("user"), PropertyUserEntity::getId,
                            false, CrudAssetPermission.read);
                });
            })
            .take(20_001).collectList().flatMapMany(rows -> {
                if (rows.size() > 20_000) return Flux.error(ArchiveScope.error("too_many", 400));
                return Flux.fromIterable(rows).map(row -> {
                    var info = new PropertyUserInfo(); info.setId(row.getId()); info.setName(row.getName());
                    info.setUsername(row.getUsername()); info.setTelephone(row.getTelephone()); info.setStatus(row.getStatus());
                    info.setIdentities(row.getIdentities().stream().map(identity -> identity.getValue()).toList());
                    return info;
                });
            });
    }

    @CommandHandler(name = "查询项目空间")
    public Flux<PropertySpaceInfo> spaces(QueryPropertySpacesCommand command) {
        // 组织归属由基础模块唯一计算；调用方不能用旧的逐节点标签扩展绑定范围。
        return readableProjects(command.getProjectId()).next()
            .switchIfEmpty(Mono.error(new AccessDenyException()))
            .then(archive.spaces(command.getProjectId(), null, CrudAssetPermission.read))
            .flatMapMany(Flux::fromIterable).map(row -> {
                var info = new PropertySpaceInfo(); info.setId(row.getId()); info.setName(row.getName());
                info.setParentId(row.getParentId()); info.setRootId(row.getRootId());
                info.setOrganizationId(command.getProjectId()); info.setFullName(row.getFullName()); info.setLeaf(row.getLeaf());
                return info;
            });
    }
}
