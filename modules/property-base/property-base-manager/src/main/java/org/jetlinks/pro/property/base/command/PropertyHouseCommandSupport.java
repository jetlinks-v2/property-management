package org.jetlinks.pro.property.base.command;

import static org.jetlinks.pro.property.base.service.ArchiveScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.archive.PropertyHouseContext;
import org.jetlinks.pro.property.base.archive.PropertyHouseCustomer;
import org.jetlinks.pro.property.base.archive.PropertyHouseInfo;
import org.jetlinks.pro.property.base.archive.PropertyHousePage;
import org.jetlinks.pro.property.base.customer.CustomerView;
import org.jetlinks.pro.property.base.customer.RelationshipView;
import org.jetlinks.pro.property.base.service.ArchiveScope;
import org.jetlinks.pro.property.base.service.PropertyCustomerService;
import org.jetlinks.pro.property.base.service.PropertyHouseService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 房屋和当前客户公共边界；复用档案范围校验，消费模块仅依赖core契约。
 *
 * @see QueryPropertyHousesCommand
 * @see QueryPropertyHouseContextCommand
 * @since 2.20.0
 */
@Component
@Authorize
@RequiredArgsConstructor
@CommandService(id = QueryPropertyHousesCommand.SERVICE_ID, name = "物业房屋资料服务")
public class PropertyHouseCommandSupport {
    private final PropertyHouseService houses;
    private final PropertyCustomerService customers;
    private final ArchiveScope scope;

    @CommandHandler(name = "查询可用房屋", description = "返回当前授权项目及空间范围中已完善资料的末端房屋分页")
    public Mono<PropertyHousePage> queryHouses(QueryPropertyHousesCommand command) {
        return Mono.defer(
                () -> {
                    if (command.getPageIndex() < 0
                            || command.getPageSize() < 1
                            || command.getPageSize() > 200
                            || (command.getKeyword() != null
                                    && command.getKeyword().length() > 200))
                        return Mono.error(error("invalid_query", 400));
                    var query = new QueryParamEntity();
                    query.setPageIndex(command.getPageIndex());
                    query.setPageSize(command.getPageSize());
                    query.and("completed", "eq", true);
                    if (command.getKeyword() != null && !command.getKeyword().isBlank())
                        query.and("fullName", "like", command.getKeyword().trim());
                    query.orderBy("fullName");
                    query.orderBy("id");
                    return houses.query(command.getProjectId(), command.getSpaceId(), query)
                            .map(
                                    page ->
                                            new PropertyHousePage(
                                                    page.getTotal(),
                                                    page.getPageIndex(),
                                                    page.getPageSize(),
                                                    page.getData().stream()
                                                            .map(PropertyHouseCommandSupport::house)
                                                            .toList()));
                });
    }

    @CommandHandler(name = "查询房屋业务资料", description = "返回已完善末端房屋和本房屋当前客户，不包含已结束或逻辑删除关系")
    public Mono<PropertyHouseContext> queryHouseContext(QueryPropertyHouseContextCommand command) {
        Mono<Void> lock =
                command.isForUpdate()
                        ? scope.project(command.getProjectId(), CrudAssetPermission.read)
                                .then(TransactionSynchronizationManager.forCurrentTransaction())
                                .onErrorMap(
                                        NoTransactionException.class,
                                        failure -> error("transaction_required", 409))
                                .then(scope.lock(command.getProjectId()))
                        : Mono.empty();
        return lock.then(houses.detail(command.getProjectId(), command.getSpaceId()))
                .flatMap(
                        row -> {
                            if (!Boolean.TRUE.equals(row.getCompleted()))
                                return Mono.error(error("house_incomplete", 409));
                            return customers
                                    .export(
                                            command.getProjectId(),
                                            command.getSpaceId(),
                                            new QueryParamEntity())
                                    .map(
                                            rows ->
                                                    new PropertyHouseContext(
                                                            house(row),
                                                            currentCustomers(
                                                                    command.getSpaceId(), rows)));
                        });
    }

    private static List<PropertyHouseCustomer> currentCustomers(
            String spaceId, List<CustomerView> rows) {
        return rows.stream()
                .flatMap(
                        customer ->
                                customer.getRelationships().stream()
                                        .filter(
                                                link ->
                                                        spaceId.equals(link.getSpaceId())
                                                                && link.getEndTime() == null
                                                                && !Boolean.TRUE.equals(
                                                                        link.getDeleted()))
                                        .map(link -> customer(customer, link)))
                .toList();
    }

    private static PropertyHouseCustomer customer(CustomerView customer, RelationshipView link) {
        return new PropertyHouseCustomer(
                link.getId(),
                customer.getId(),
                customer.getUserId(),
                customer.getCustomerType(),
                customer.getName(),
                customer.getTelephone(),
                link.getNature(),
                link.getFamilyId(),
                link.getFamilyName(),
                link.getStartTime(),
                link.getVersion(),
                customer.getVersion());
    }

    private static PropertyHouseInfo house(PropertyHouseService.HouseView row) {
        return new PropertyHouseInfo(
                row.getSpaceId(),
                row.getProjectId(),
                row.getName(),
                row.getFullName(),
                row.getSpaceTypeId(),
                row.getSpaceTypeName(),
                row.getSpaceLocation(),
                row.getBillingArea(),
                row.getBuildingArea(),
                row.getInsideArea(),
                row.getDeliveryDate(),
                row.getVersion());
    }
}
