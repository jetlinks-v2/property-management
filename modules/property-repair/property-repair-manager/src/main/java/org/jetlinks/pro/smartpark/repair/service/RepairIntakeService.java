package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.jetlinks.pro.smartpark.repair.api.CreateRepairRequest;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Objects;

/** 报事表单关联校验与名称快照，所有选项均限定到同一业务项目。 */
@Service
@AllArgsConstructor
public class RepairIntakeService {
    private final RepairConfigurationManager configurations;
    private final RepairDeviceService devices;
    private final RepairDirectory directory;

    public Mono<RepairOrderEntity> populate(RepairOrderEntity order, CreateRepairRequest request,
                                            RepairConfigurationEntity category, RepairContext context,RepairFormOptions options) {
        return configurations.categoryDepth(category, context, 1, null)
            .doOnNext(depth -> RepairFlowRules.require(depth == 3, "category_leaf_required"))
            .thenMany(Flux.fromIterable(request.getLabelIds()).distinct()
                .concatMap(id -> configurations.enabled(id, "label", request.getBusinessProjectId(), context)
                    .doOnNext(label -> RepairFlowRules.require(Objects.equals(label.getParentId(), category.getId()), "label_category_mismatch"))))
            .collectList() // 输入最多 50 个标签，由 DTO 限制。
            .flatMap(labels -> validateDescriptions(request, context,options).then(devices.populate(order,request,category))
                .then(registrationPhone(order,context)).thenReturn(labels))
            .map(labels -> {
                applyFields(order, request);
                order.setLabelIds(labels.stream().map(RepairConfigurationEntity::getId).toList());
                order.setLabelNames(labels.stream().map(RepairConfigurationEntity::getName).toList());
                return order;
            });
    }

    /** 从同一基础目录读取当前登记人电话，不额外要求 Cloud 用户管理菜单权限。 */
    Mono<Void> registrationPhone(RepairOrderEntity order,RepairContext context) {
        return directory.user(context.userId())
            .filter(user -> context.userId().equals(user.getId()))
            .doOnNext(user -> order.setRegisterPhone(user.getTelephone()))
            .then();
    }

    private Mono<Void> validateDescriptions(CreateRepairRequest request, RepairContext context,RepairFormOptions options) {
        List<String> ids = request.getDescriptionChoiceIds();
        return Flux.fromIterable(ids==null?List.<String>of():ids).distinct()
            .concatMap(id -> configurations.enabled(id, "description", request.getBusinessProjectId(), context)
                .doOnNext(choice -> RepairFlowRules.require(request.getLabelIds().contains(choice.getParentId()), "description_label_mismatch")))
            .map(choice->Objects.toString(choice.getConfiguration().get("content"),choice.getName()))
            .collectList().doOnNext(choices->request.setDescription(options.description(request.getDescription(),choices))).then();
    }

    /** 报事人与登记人分别保存；旧表单的来源不能被渠道名代替。 */
    static void applyFields(RepairOrderEntity order, CreateRepairRequest request) {
        order.setReportSource(request.getReportSource());
        order.setSource(request.getReportSource() == 1 ? "物业自查" : "业主报事");
        order.setPositionType(request.getPositionType());
        order.setUrgency(request.getUrgency());
        order.setTitle(request.getDescription().substring(0, Math.min(80, request.getDescription().length())));
        order.setDescription(request.getDescription());
        order.setReporterName(request.getContactName());
        order.setContactName(request.getContactName());
        order.setContactPhone(request.getContactPhone());
        order.setDescriptionChoiceIds(request.getDescriptionChoiceIds());
    }
}
