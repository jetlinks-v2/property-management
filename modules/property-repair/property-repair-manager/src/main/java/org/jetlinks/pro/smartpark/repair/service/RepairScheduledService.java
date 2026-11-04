package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairProcessEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 集群调度专用写入边界，不接受用户输入；系统动作与工单版本、过程记录同一事务。 */
@Service
@AllArgsConstructor
public class RepairScheduledService {
    private final RepairOrderService orders;
    private final RepairConfigurationService configurations;
    private final RepairProcessService processes;
    private final RepairCandidateService candidates;

    @Transactional
    public Mono<Boolean> execute(String id, String kind, long now) {
        return orders.findById(id).flatMap(order -> switch (kind) {
            case "dispatch" -> autoDispatch(order, now);
            case "evaluation" -> {
                if (!evaluationDue(order, now)) yield Mono.just(false);
                order.setOwnerEvaluated(true);
                order.setOwnerSatisfaction(5L);
                order.setOwnerSatisfactionName("系统默认好评");
                order.setOwnerEvaluationRecorderId("system");
                order.setOwnerEvaluationRecorderName("系统默认评价");
                order.setOwnerEvaluationTime(now);
                yield persist(order, "autoEvaluate", "完结七天未评价，系统默认好评", now);
            }
            case "timeout" -> {
                if (!timeoutDue(order, now)) yield Mono.just(false);
                order.setTimedOut(true);
                yield persist(order, "timeout", "工单超过完成时限", now);
            }
            default -> Mono.error(new IllegalArgumentException("Unknown scheduled repair operation"));
        }).defaultIfEmpty(false);
    }

    private Mono<Boolean> autoDispatch(RepairOrderEntity order, long now) {
        if (order.getStatus() != 0) return Mono.just(false);
        return configurations.findById(order.getCategoryId())
            // 后台可跨租户扫描，但每笔只使用该工单同租户、应用、业务项目的已启用分类。
            .filter(category -> sameConfigurationScope(order, category))
            .flatMap(category -> dispatchToPool(order, category, now)).defaultIfEmpty(false);
    }

    private Mono<Boolean> dispatchToPool(RepairOrderEntity order, RepairConfigurationEntity category, long now) {
        Map<String, Object> flow = category.getConfiguration() == null ? Map.of() : category.getConfiguration();
        if (!dispatchDue(order, flow, now)) return Mono.just(false);
        String role = flow.get("autoDispatchRoleId").toString();
        RepairContext context = new RepairContext(Objects.toString(order.getExtension("tenantId")),
            order.getApplicationId(), "system", "系统");
        return candidates.available(role, order, context).flatMap(available -> {
            // 无候选人保持待分派，后续人员授权补齐后再扫描，不能随机选择责任人。
            if (!available) return Mono.just(false);
            RepairCandidateService.setPool(order, role, "autoDispatch");
            return persist(order, "autoDispatch", "超时自动分派至角色待接单池", now);
        });
    }

    static boolean sameConfigurationScope(RepairOrderEntity order, RepairConfigurationEntity category) {
        return Boolean.TRUE.equals(category.getEnabled()) && "category".equals(category.getKind())
            && Objects.equals(order.getApplicationId(), category.getApplicationId())
            && Objects.equals(order.getBusinessProjectId(), category.getBusinessProjectId())
            && order.getExtension("tenantId") != null
            && Objects.equals(order.getExtension("tenantId"), category.getExtension("tenantId"));
    }

    static boolean dispatchDue(RepairOrderEntity order, Map<String, Object> flow, long now) {
        if (!RepairFlowConfiguration.enabled(flow) || !Boolean.TRUE.equals(flow.get("autoDispatchEnable")) || order.getCreateTime() == null
            || !(flow.get("autoDispatchMinutes") instanceof Number minutes)
            || minutes.longValue() < 1 || Objects.toString(flow.get("autoDispatchRoleId"), "").isBlank()
            || !Objects.toString(flow.get("followRoleId"), "").isBlank()) return false;
        return (now - order.getCreateTime()) / 60_000 >= minutes.longValue();
    }

    static boolean evaluationDue(RepairOrderEntity order, long now) {
        return Set.of(2, 5, 8).contains(order.getStatus()) && !Boolean.TRUE.equals(order.getOwnerEvaluated())
            && order.getFinishTime() != null && order.getFinishTime() <= now - 7 * 86_400_000L;
    }

    static boolean timeoutDue(RepairOrderEntity order, long now) {
        return order.getStatus() == 1 && !Boolean.TRUE.equals(order.getTimedOut())
            && order.getFinishDeadline() != null && order.getFinishDeadline() < now;
    }

    private Mono<Boolean> persist(RepairOrderEntity order, String action, String description, long now) {
        long beforeVersion = order.getVersion();
        int beforeStatus = "autoDispatch".equals(action) ? 0 : order.getStatus();
        order.setVersion(beforeVersion + 1);
        order.setModifyTime(now);
        // 与人工接单/评价竞争同一个版本；失败者不增加过程，后续一轮重新读取。
        return orders.createUpdate().set(order).where(RepairOrderEntity::getId, order.getId())
            .and(RepairOrderEntity::getVersion, beforeVersion).execute()
            .flatMap(changed -> changed == 1 ? record(order, action, description, beforeStatus, now).thenReturn(true) : Mono.just(false));
    }

    private Mono<Void> record(RepairOrderEntity order, String action, String description, int beforeStatus, long now) {
        RepairProcessEntity process = new RepairProcessEntity();
        process.setId(IDGenerator.MD5.generate());
        process.setOrderId(order.getId());
        process.setTenantId(Objects.toString(order.getExtension("tenantId")));
        process.setApplicationId(order.getApplicationId());
        process.setActorId("system");
        process.setActorName("系统");
        process.setAction(action);
        process.setRequestId(action + "-" + order.getVersion());
        process.setBeforeStatus(beforeStatus);
        process.setAfterStatus(order.getStatus());
        process.setOrderVersion(order.getVersion());
        process.setDescription(description);
        if (order.getCandidateRoleId() != null) process.setDetails(Map.of("candidateRoleId", order.getCandidateRoleId(), "candidateSource", order.getCandidateSource()));
        process.setAttachmentIds(List.of());
        process.setCreateTime(now);
        return processes.insert(process).then();
    }
}
