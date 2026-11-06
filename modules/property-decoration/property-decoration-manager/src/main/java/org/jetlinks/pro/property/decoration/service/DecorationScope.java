package org.jetlinks.pro.property.decoration.service;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.core.command.Command;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.*;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.base.house.PropertyHouseholdInfo;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Objects;

/** 唯一业务数据边界：可信应用+租户，以及基础提供方已经过 AssetsHolder 过滤的项目交集。 */
@Component
public class DecorationScope {
    // 本机联调可明确选择测试公共基础节点，避免误选同集群的其他开发实例。
    @org.springframework.beans.factory.annotation.Value("${decoration.directory.server-id:}")
    private String directoryServerId = "";
    public record Context(String tenantId, String applicationId, String userId, String userName, Authentication authentication) {}
    public Mono<Context> current() {
        // 应用由平台 AssetScopeWebFilter 激活并授权；平台直属应用可无租户。
        // 租户仅作为已建立的技术隔离键，不能再用 TenantAssetsHolder 重复判定应用归属。
        return Mono.zip(TenantHolder.currentId().defaultIfEmpty(""), Authentication.currentReactive(),
            AssetScopeContext.currentReactive().flatMap(scope -> Mono.justOrEmpty(scope.getScope(BusinessApplicationDimension.TYPE))))
            .switchIfEmpty(Mono.error(new AccessDenyException()))
            .map(tuple -> new Context(tuple.getT1(), tuple.getT3().id(), tuple.getT2().getUser().getId(), tuple.getT2().getUser().getName(), tuple.getT2()));
    }
    public Flux<PropertyOrganizationInfo> projects() {
        // 项目选择和列表范围使用同一份已授权组织树，仅识别显式标注为项目的节点。
        // 不另走旧目录命令的强制租户过滤，避免平台应用的树与列表范围不一致。
        return tree().expand(node -> Flux.fromIterable(node.getChildren() == null ? List.of() : node.getChildren()))
            .filter(node -> node.getOrganizationType() == org.jetlinks.pro.property.base.organization.OrganizationType.project);
    }
    public Mono<PropertyOrganizationInfo> project(String id) {
        return projects().filter(p -> p.getId().equals(id)).next().switchIfEmpty(Mono.error(new AccessDenyException()));
    }
    public Flux<PropertyOrganizationInfo> tree() {
        var command = new QueryPropertyOrganizationTreeCommand();
        return support(QueryPropertyOrganizationTreeCommand.SERVICE_ID, command).flatMapMany(s -> s.execute(command));
    }
    public Flux<PropertyHouseholdInfo> households(String projectId, boolean mine) {
        var command = new QueryPropertyHouseholdsCommand(); command.setProjectId(projectId); command.setMine(mine);
        return support(QueryPropertyHouseholdsCommand.SERVICE_ID, command).flatMapMany(s -> s.execute(command));
    }
    /** 导入只接受公共主档中的启用编码；未部署字典命令时保持503，不相信客户端字典快照。 */
    public Flux<org.jetlinks.pro.property.base.dictionary.PropertyDictionaryItemInfo> dictionaryItems(String typeCode) {
        var command = new QueryPropertyDictionaryItemsCommand(); command.setTypeCode(typeCode);
        return support(QueryPropertyDictionaryItemsCommand.SERVICE_ID, command).flatMapMany(s -> s.execute(command));
    }
    public Mono<PropertyHouseholdInfo> household(String projectId, String spaceId, String customerId, boolean mine) {
        return households(projectId, mine).filter(h -> Objects.equals(h.spaceId(), spaceId) && Objects.equals(h.customerId(), customerId))
            .next().switchIfEmpty(Mono.error(DecorationRules.error("household_mismatch", 403)));
    }
    public Mono<PropertyEmployeeInfo> employee() {
        var command = new GetCurrentPropertyEmployeeCommand();
        return support(GetCurrentPropertyEmployeeCommand.SERVICE_ID, command).flatMap(s -> s.execute(command))
            .switchIfEmpty(Mono.error(DecorationRules.error("employee_required", 403)));
    }
    /** 公开候选沿用基础员工分页命令，不复制员工主档。 */
    public Mono<org.hswebframework.web.api.crud.entity.PagerResult<PropertyEmployeeInfo>> employees(QueryParamEntity query) {
        var command = new QueryPropertyEmployeePageCommand(); command.setQuery(query);
        return support(QueryPropertyEmployeePageCommand.SERVICE_ID, command).flatMap(s -> s.execute(command));
    }
    public Mono<QueryParamEntity> query(QueryParamEntity input, Context context) {
        var query = input.clone(); query.setTotal(null); query.setPaging(true);
        query.setPageIndex(Math.max(0, query.getPageIndex())); query.setPageSize(Math.max(1, Math.min(100, query.getPageSize())));
        query.setIncludes(java.util.Set.of()); query.setExcludes(java.util.Set.of());
        if (query.getSorts().isEmpty()) query.toQuery().orderByDesc("createTime");
        if (query.getSorts().stream().noneMatch(sort -> "id".equals(sort.getName()))) query.toQuery().orderByDesc("id");
        // 权限在基础服务执行，装修库没有 s_organization，不能将它的资产 SQL 子查询复制到业务库。
        return projects().map(PropertyOrganizationInfo::getId).collectList().map(ids -> {
            query.toNestQuery(q -> {
                q.and("applicationId", context.applicationId()).and("tenantId", context.tenantId());
                if (ids.isEmpty()) q.isNull("projectId").notNull("projectId"); else q.in("projectId", ids);
            });
            return query;
        });
    }
    private Mono<CommandSupport> support(String id, Command<?> command) {
        return CommandSupportManagerProviders.getCommandSupport(id)
            .as(CommandSupportManagerProviders.clusterRouter(directoryServerId))
            // 集群代理存在不代表目标节点已安装此命令；缺失不能被 RPC 空结果伪装成无房屋。
            .filterWhen(support -> support.commandIsSupported(command).defaultIfEmpty(false))
            .switchIfEmpty(Mono.error(DecorationRules.error("directory_unavailable", 503)));
    }
}
