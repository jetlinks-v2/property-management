package org.jetlinks.pro.property.base.command;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.property.base.customer.PropertyCustomerInfo;
import org.jetlinks.pro.property.base.customer.RelationshipView;
import org.jetlinks.pro.property.base.customer.CustomerView;
import org.jetlinks.pro.property.base.service.PropertyCustomerService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 保留测试环境已发布的客户目录命令；权限与有效关系过滤由客户服务统一拥有。 */
@Component @Authorize @RequiredArgsConstructor
@CommandService(id = QueryPropertyCustomersCommand.SERVICE_ID, name = "物业客户目录")
public class PropertyCustomerCommandSupport {
    private final PropertyCustomerService customers;

    @CommandHandler(name = "查询有权客户")
    public Flux<PropertyCustomerInfo> query(QueryPropertyCustomersCommand command) {
        return customers.export(command.getProjectId(), command.getSpaceId(), QueryParamEntity.newQuery().noPaging().getParam())
            .flatMapMany(rows -> directory(command.getProjectId(), rows));
    }

    private Flux<PropertyCustomerInfo> directory(String projectId, List<CustomerView> rows) {
        // 客户列表现按空间关系分页，但已发布目录仍是一客一条，且只聚合服务已授权的关系。
        Map<String, PropertyCustomerInfo> customersById = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> spacesByCustomer = new LinkedHashMap<>();
        for (var row : rows) {
            if (row.getRelationships() == null || row.getRelationships().isEmpty()) continue;
            customersById.computeIfAbsent(row.getId(), id -> {
                var info = new PropertyCustomerInfo();
                info.setId(id);
                info.setProjectId(projectId);
                info.setUserId(row.getUserId());
                info.setName(row.getName());
                info.setTelephone(row.getTelephone());
                return info;
            });
            var spaceIds = spacesByCustomer.computeIfAbsent(row.getId(), id -> new LinkedHashSet<>());
            row.getRelationships().stream().map(RelationshipView::getSpaceId).forEach(spaceIds::add);
        }
        customersById.forEach((id, customer) -> customer.setSpaceIds(List.copyOf(spacesByCustomer.get(id))));
        return Flux.fromIterable(customersById.values());
    }
}
