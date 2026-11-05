package org.jetlinks.pro.property.base.command;

import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.entity.PropertyCustomerEntity;
import org.jetlinks.pro.property.base.entity.PropertyCustomerRelationshipEntity;
import org.jetlinks.pro.property.base.entity.PropertyHouseEntity;
import org.jetlinks.pro.property.base.house.PropertyHouseholdInfo;
import org.jetlinks.pro.property.base.service.ArchiveScope;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.stream.Collectors;

/** 基础档案唯一提供方：项目、空间权限和有效关系在此校验，消费方不跨库读表。 */
@Component
@Authorize
@RequiredArgsConstructor
@CommandService(id = QueryPropertyHouseholdsCommand.SERVICE_ID, name = "物业客户房屋目录")
public class PropertyHouseholdCommandSupport {
    private final ArchiveScope scope;
    private final PropertyDirectoryCommandSupport directory;
    private final ReactiveRepository<PropertyCustomerEntity, String> customers;
    private final ReactiveRepository<PropertyCustomerRelationshipEntity, String> relationships;
    private final ReactiveRepository<PropertyHouseEntity, String> houses;

    @CommandHandler(name = "查询有效客户房屋关系")
    public Flux<PropertyHouseholdInfo> query(QueryPropertyHouseholdsCommand command) {
        return directory.projects(new QueryPropertyProjectsCommand())
            .any(project -> project.getId().equals(command.getProjectId()))
            .flatMap(allowed -> allowed ? scope.authenticated() : Mono.error(new AccessDenyException()))
            .flatMapMany(auth -> scope.spaces(command.getProjectId(), command.getSpaceId(), CrudAssetPermission.read)
                .flatMapMany(spaces -> {
                    if (spaces.isEmpty()) return Flux.empty();
                    var names = spaces.stream().collect(Collectors.toMap(s -> s.getId(), s -> s.getFullName()));
                    var relations = relationships.createQuery().where("projectId", command.getProjectId())
                        .and("deleted", false).isNull("endTime").lte("startTime", System.currentTimeMillis())
                        .when(command.getSpaceId() != null, q -> q.and("spaceId", command.getSpaceId()))
                        .when(command.getCustomerId() != null, q -> q.and("customerId", command.getCustomerId()))
                        .in("spaceId", names.keySet()).fetch().take(20001).collectList();
                    return relations.flatMapMany(rows -> {
                        if (rows.size() > 20000) return Flux.error(ArchiveScope.error("too_many", 400));
                        if (rows.isEmpty()) return Flux.empty();
                        var people = customers.createQuery().where("projectId", command.getProjectId())
                            .and("deleted", false).in("id", rows.stream().map(PropertyCustomerRelationshipEntity::getCustomerId).distinct().toList())
                            .when(command.isMine(), q -> q.and("userId", auth.getUser().getId())).fetch()
                            .collectMap(PropertyCustomerEntity::getId);
                        var homes = houses.createQuery().where("projectId", command.getProjectId()).and("deleted", false)
                            .in("id", names.keySet()).fetch().collectMap(PropertyHouseEntity::getId);
                        return Mono.zip(people, homes).flatMapMany(tuple -> Flux.fromIterable(rows)
                            .filter(r -> tuple.getT1().containsKey(r.getCustomerId()) && tuple.getT2().containsKey(r.getSpaceId()))
                            .map(r -> {
                                var customer = tuple.getT1().get(r.getCustomerId());
                                return new PropertyHouseholdInfo(r.getProjectId(), r.getSpaceId(), names.get(r.getSpaceId()),
                                    tuple.getT2().get(r.getSpaceId()).getHouseType(), customer.getId(), customer.getUserId(),
                                    customer.getName(), customer.getTelephone(), r.getId(), r.getNature());
                            }));
                    });
                }));
    }
}
