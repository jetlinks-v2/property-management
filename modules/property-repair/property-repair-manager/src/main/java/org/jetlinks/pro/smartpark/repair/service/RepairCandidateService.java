package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.jetlinks.pro.property.entity.PropertyPersonEntity;
import org.jetlinks.pro.property.service.PropertyPersonService;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;

/** 角色待接单池。角色只表达候选资格，接单成功才产生唯一责任人。 */
@Service
@AllArgsConstructor
public class RepairCandidateService {
    private final PropertyPersonService people;
    private final RepairAssigneeService assignees;
    private final RepairOrderService orders;

    public Mono<RepairOrderEntity> initialize(RepairOrderEntity order, RepairConfigurationEntity category, RepairContext context) {
        Map<String, Object> flow = category.getConfiguration() == null ? Map.of() : category.getConfiguration();
        if (!RepairFlowConfiguration.enabled(flow)) return Mono.just(order);
        String role = Objects.toString(flow.get("followRoleId"), "");
        if (role.isBlank()) return Mono.just(order);
        // 工单已在当前事务插入并绑定资产，候选资格才能按其真实组织/应用授权判断。
        return available(role, order, context)
            .doOnNext(exists -> RepairFlowRules.require(exists, "role_has_no_eligible_users"))
            .flatMap(ignore -> {
                setPool(order, role, "followRole");
                return orders.updateById(order.getId(), order).thenReturn(order);
            });
    }

    public Mono<Boolean> available(String role, RepairOrderEntity order, RepairContext context) {
        return people.createQuery().where(PropertyPersonEntity::getTenantId, context.tenantId())
            .and(PropertyPersonEntity::getEmployee, true).and(PropertyPersonEntity::getSourceStatus, 1).fetch()
            .filterWhen(person -> assignees.canHandle(person, order, context, role), 8).hasElements();
    }

    public Mono<Void> accept(RepairOrderEntity order, RepairContext context) {
        if (order.getCandidateRoleId() == null) return Mono.empty();
        return people.findById(context.userId())
            .flatMap(person -> assignees.canHandle(person, order, context, order.getCandidateRoleId()))
            .defaultIfEmpty(false)
            .doOnNext(allowed -> RepairFlowRules.require(allowed, "candidate_not_eligible"))
            .doOnNext(ignore -> {
                order.setAssignedUserId(context.userId());
                order.setAssignedUserName(context.userName());
                order.setCandidateRoleId(null);
                order.setCandidateSource(null);
            }).then();
    }

    static void setPool(RepairOrderEntity order, String role, String source) {
        order.setStatus(6);
        order.setCandidateRoleId(role);
        order.setCandidateSource(source);
        order.setAssignedUserId(null);
        order.setAssignedUserName(null);
    }
}
