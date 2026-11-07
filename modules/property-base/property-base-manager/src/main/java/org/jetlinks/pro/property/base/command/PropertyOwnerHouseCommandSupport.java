package org.jetlinks.pro.property.base.command;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.assets.AssetManager;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.base.entity.*;
import org.jetlinks.pro.property.base.organization.OrganizationType;
import org.jetlinks.pro.property.base.service.ArchiveScope;
import org.jetlinks.pro.tenant.assets.TenantAssetsHolder;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/** 本人客户资源边界，与员工目录权限独立；身份由认证上下文取得，不接受客户端指定他人。 */
@Component
@Authorize
@RequiredArgsConstructor
@CommandService(id = QueryCurrentPropertyHousesCommand.SERVICE_ID, name = "物业本人房屋服务")
public class PropertyOwnerHouseCommandSupport {
    private final ReactiveRepository<PropertyUserEntity, String> users;
    private final ReactiveRepository<PropertyCustomerEntity, String> customers;
    private final ReactiveRepository<PropertyCustomerRelationshipEntity, String> relationships;
    private final ReactiveRepository<PropertyHouseEntity, String> houses;
    private final ReactiveRepository<PropertySpaceEntity, String> spaces;
    private final ReactiveRepository<PropertySpaceBindingEntity, String> bindings;
    private final ReactiveRepository<PropertyOrganizationEntity, String> organizations;
    private final AssetManager assetManager;

    @CommandHandler(name = "查询本人有效房屋")
    public Mono<List<PropertyOwnerHouse>> current(QueryCurrentPropertyHousesCommand command) {
        return Authentication.currentReactive()
                .switchIfEmpty(Mono.error(UnAuthorizedException.NoStackTrace::new))
                .flatMap(
                        auth ->
                                users.createQuery()
                                        .where("id", auth.getUser().getId())
                                        .and("deleted", false)
                                        .and("status", 1)
                                        .fetchOne()
                                        .flatMap(user -> currentCustomers(user.getId(), command)))
                .defaultIfEmpty(List.of());
    }

    private Mono<List<PropertyOwnerHouse>> currentCustomers(
            String user, QueryCurrentPropertyHousesCommand command) {
        var query = customers.createQuery().where("userId", user).and("deleted", false);
        if (command.getProjectId() != null) query.and("projectId", command.getProjectId());
        // 单个账号的异常大关系集显式失败，绝不返回截断的授权列表。
        return query.fetch()
                .take(1001)
                .collectList()
                .flatMap(
                        rows -> {
                            if (rows.size() > 1000)
                                return Mono.error(ArchiveScope.error("too_many", 400));
                            if (rows.isEmpty()) return Mono.just(List.of());
                            return TenantHolder.currentId()
                                    .switchIfEmpty(Mono.error(new AccessDenyException()))
                                    .flatMap(
                                            tenant ->
                                                    new TenantAssetsHolder(
                                                                    List.of(tenant), assetManager)
                                                            .filter(
                                                                    organizations
                                                                            .createQuery()
                                                                            .in(
                                                                                    "id",
                                                                                    rows.stream()
                                                                                            .map(
                                                                                                    PropertyCustomerEntity
                                                                                                            ::getProjectId)
                                                                                            .distinct()
                                                                                            .toList())
                                                                            .and("deleted", false)
                                                                            .and(
                                                                                    "organizationType",
                                                                                    OrganizationType
                                                                                            .project)
                                                                            .fetch(),
                                                                    ArchiveScope.ORGANIZATION,
                                                                    PropertyOrganizationEntity
                                                                            ::getId,
                                                                    false,
                                                                    CrudAssetPermission.read)
                                                            .collectMap(
                                                                    PropertyOrganizationEntity
                                                                            ::getId)
                                                            .flatMap(
                                                                    projects ->
                                                                            links(
                                                                                    rows, projects,
                                                                                    command)));
                        });
    }

    private Mono<List<PropertyOwnerHouse>> links(
            List<PropertyCustomerEntity> rows,
            Map<String, PropertyOrganizationEntity> projects,
            QueryCurrentPropertyHousesCommand command) {
        if (projects.isEmpty()) return Mono.just(List.of());
        var byId = new HashMap<String, PropertyCustomerEntity>();
        rows.forEach(c -> byId.put(c.getId(), c));
        var query =
                relationships
                        .createQuery()
                        .in("customerId", byId.keySet())
                        .in("projectId", projects.keySet())
                        .and("deleted", false)
                        .isNull("endTime")
                        .and("startTime", "lte", System.currentTimeMillis());
        if (command.getSpaceId() != null) query.and("spaceId", command.getSpaceId());
        return query.fetch()
                .take(1001)
                .collectList()
                .flatMap(
                        links -> {
                            if (links.size() > 1000)
                                return Mono.error(ArchiveScope.error("too_many", 400));
                            return Flux.fromIterable(links)
                                    .concatMap(
                                            link ->
                                                    load(
                                                            link,
                                                            byId.get(link.getCustomerId()),
                                                            projects.get(link.getProjectId())))
                                    .collectList();
                        });
    }

    private Mono<PropertyOwnerHouse> load(
            PropertyCustomerRelationshipEntity link,
            PropertyCustomerEntity customer,
            PropertyOrganizationEntity project) {
        if (!customer.getProjectId().equals(link.getProjectId())) return Mono.empty();
        return Mono.zip(
                        houses.createQuery()
                                .where("id", link.getSpaceId())
                                .and("projectId", link.getProjectId())
                                .and("deleted", false)
                                .fetchOne(),
                        spaces.createQuery()
                                .where("id", link.getSpaceId())
                                .and("deleted", false)
                                .fetchOne())
                .flatMap(
                        pair -> {
                            var house = pair.getT1();
                            var space = pair.getT2();
                            if (!house.getRootId().equals(space.getRootId())) return Mono.empty();
                            return bindings.createQuery()
                                    .where("id", space.getRootId())
                                    .and("organizationId", project.getId())
                                    .and("deleted", false)
                                    .fetchOne()
                                    .flatMap(
                                            binding ->
                                                    ancestors(space, new HashSet<>())
                                                            .map(
                                                                    path -> {
                                                                        var info =
                                                                                new PropertyHouseInfo(
                                                                                        space
                                                                                                .getId(),
                                                                                        house
                                                                                                .getProjectId(),
                                                                                        space
                                                                                                .getName(),
                                                                                        space
                                                                                                .getFullName(),
                                                                                        null,
                                                                                        null,
                                                                                        space
                                                                                                .getFullName(),
                                                                                        house
                                                                                                .getBillingArea(),
                                                                                        house
                                                                                                .getBuildingArea(),
                                                                                        house
                                                                                                .getInsideArea(),
                                                                                        house
                                                                                                .getDeliveryDate(),
                                                                                        house
                                                                                                .getVersion());
                                                                        var payer =
                                                                                new PropertyHouseCustomer(
                                                                                        link
                                                                                                .getId(),
                                                                                        customer
                                                                                                .getId(),
                                                                                        customer
                                                                                                .getUserId(),
                                                                                        customer
                                                                                                .getCustomerType(),
                                                                                        customer
                                                                                                .getName(),
                                                                                        customer
                                                                                                .getTelephone(),
                                                                                        link
                                                                                                .getNature(),
                                                                                        link
                                                                                                .getStartTime(),
                                                                                        link
                                                                                                .getVersion(),
                                                                                        customer
                                                                                                .getVersion());
                                                                        return new PropertyOwnerHouse(
                                                                                project.getName(),
                                                                                new PropertyHouseContext(
                                                                                        info,
                                                                                        List.of(
                                                                                                payer)),
                                                                                path);
                                                                    }));
                        });
    }

    private Mono<List<PropertySpaceInfo>> ancestors(PropertySpaceEntity node, Set<String> seen) {
        if (!seen.add(node.getId()) || seen.size() > 100)
            return Mono.error(ArchiveScope.error("space_not_found", 409));
        var current =
                new PropertySpaceInfo(
                        node.getId(),
                        node.getParentId(),
                        node.getRootId(),
                        node.getName(),
                        node.getFullName(),
                        null,
                        null,
                        node.getLeaf());
        if (node.getId().equals(node.getRootId())) return Mono.just(List.of(current));
        return spaces.createQuery()
                .where("id", node.getParentId())
                .and("rootId", node.getRootId())
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(ArchiveScope.error("space_not_found", 409)))
                .flatMap(parent -> ancestors(parent, seen))
                .map(
                        path -> {
                            var result = new ArrayList<>(path);
                            result.add(current);
                            return List.copyOf(result);
                        });
    }
}
