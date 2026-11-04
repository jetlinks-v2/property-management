package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.jetlinks.pro.smartpark.repair.api.RepairActionRequest;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** 有偿费用及双侧评价；由工单事务调用，登记员工始终取可信上下文。 */
@Service
@AllArgsConstructor
public class RepairCommerceService {
    private final RepairConfigurationService configurations;
    private final RepairConfigurationManager configurationManager;

    public Mono<Void> prepare(String action, RepairOrderEntity order, RepairActionRequest input, RepairContext context) {
        return switch (action) {
            case "toPaid" -> { order.setPaid(true); yield Mono.empty(); }
            case "quote" -> quote(order, input).then(reply("finish", order, input, context));
            case "sign" -> {
                requireOwner(input);
                RepairFlowRules.require(input.getSignatureFileId() != null && !input.getSignatureFileId().isBlank(), "signature_file_required");
                order.setSigned(true);
                order.setSignatureFileId(input.getSignatureFileId());
                order.setSignatureOwnerName(input.getOwnerName());
                order.setSignatureOwnerPhone(input.getOwnerPhone());
                order.setSignatureRecorderId(context.userId());
                order.setSignatureRecorderName(context.userName());
                order.setSignatureTime(System.currentTimeMillis());
                yield Mono.empty();
            }
            case "evaluate", "ownerEvaluate" -> evaluate(action, order, input, context);
            case "callback" -> satisfaction(order, input, context).then();
            case "follow", "finish", "close" -> reply(action, order, input, context);
            default -> Mono.empty();
        };
    }

    private Mono<Void> quote(RepairOrderEntity order, RepairActionRequest input) {
        RepairFlowRules.require(input.getMaterialAmount() != null && input.getArtificialAmount() != null, "fees_required");
        // 配置只从当前应用和业务项目获取；保留快照，后续改抽佣比例不重算历史费用。
        return configurations.createQuery()
            .where("applicationId", order.getApplicationId()).and("businessProjectId", order.getBusinessProjectId())
            .and("kind", "commission").and("enabled", true)
            .orderBy(org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.desc("modifyTime"),
                org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.asc("id"))
            .fetchOne().map(row -> row.getConfiguration() == null ? Map.<String,Object>of() : row.getConfiguration())
            .defaultIfEmpty(Map.of()).doOnNext(config -> applyQuote(order, input, config)).then();
    }

    static void applyQuote(RepairOrderEntity order, RepairActionRequest input, Map<String,Object> configuration) {
        BigDecimal material = input.getMaterialAmount();
        BigDecimal artificial = input.getArtificialAmount();
        order.setMaterialAmount(material);
        order.setArtificialAmount(artificial);
        order.setTotalAmount(material.add(artificial));
        order.setCommissionSnapshot(configuration);
        order.setPersonAmount(commission(material, artificial, configuration, "person"));
        order.setProviderAmount(commission(material, artificial, configuration, "provider"));
        order.setPropertyAmount(commission(material, artificial, configuration, "property"));
        order.setSignatureRequested(true);
        order.setSigned(false);
    }

    /** 与旧版一致：百分比先除100保留4位 HALF_UP，再乘费用相加，不提前按分截断。 */
    static BigDecimal commission(BigDecimal material, BigDecimal artificial, Map<String,Object> config, String recipient) {
        return material.multiply(ratio(config, "materialVo", recipient))
            .add(artificial.multiply(ratio(config, "artificialVo", recipient)));
    }

    private static BigDecimal ratio(Map<String,Object> config, String kind, String recipient) {
        if (config.isEmpty()) return BigDecimal.ZERO;
        Map<?,?> rates = (Map<?,?>) config.get(kind);
        return decimal(rates.get(recipient)).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    static void validateCommission(Map<String,Object> config) {
        for (String kind : List.of("materialVo", "artificialVo")) {
            RepairFlowRules.require(config.get(kind) instanceof Map<?,?>, "commission_invalid");
            Map<?,?> rates = (Map<?,?>) config.get(kind);
            BigDecimal total = BigDecimal.ZERO;
            for (String recipient : List.of("person", "provider", "property")) {
                BigDecimal value = decimal(rates.get(recipient));
                RepairFlowRules.require(value.signum() >= 0 && value.compareTo(BigDecimal.valueOf(100)) <= 0, "commission_invalid");
                total = total.add(value);
            }
            RepairFlowRules.require(total.compareTo(BigDecimal.valueOf(100)) <= 0, "commission_invalid");
        }
    }

    private static BigDecimal decimal(Object value) {
        RepairFlowRules.require(value instanceof Number, "commission_invalid");
        return new BigDecimal(value.toString());
    }

    private Mono<Void> evaluate(String action, RepairOrderEntity order, RepairActionRequest input, RepairContext context) {
        boolean owner = "ownerEvaluate".equals(action);
        if (owner) requireOwner(input);
        return satisfaction(order, input, context).flatMap(rating -> {
            List<String> labels = input.getEvaluationLabelIds() == null ? List.of() : input.getEvaluationLabelIds();
            return Flux.fromIterable(labels).distinct()
                .concatMap(id -> configurationManager.enabled(id, "evaluationLabel", order.getBusinessProjectId(), context)
                    .doOnNext(label -> RepairConfigurationRules.evaluationLabel(label, rating.getId())))
                .map(org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity::getName).collectList()
                .doOnNext(names -> {
                    input.setEvaluationLabelNames(names);
                    if (owner) {
                        order.setOwnerEvaluated(true);
                        order.setOwnerSatisfaction(input.getSatisfaction());
                        order.setOwnerSatisfactionId(rating.getId());
                        order.setOwnerSatisfactionName(rating.getName());
                        order.setEvaluationOwnerName(input.getOwnerName());
                        order.setEvaluationOwnerPhone(input.getOwnerPhone());
                        order.setOwnerEvaluationRecorderId(context.userId());
                        order.setOwnerEvaluationRecorderName(context.userName());
                        order.setOwnerEvaluationTime(System.currentTimeMillis());
                    } else {
                        order.setStaffEvaluated(true);
                        order.setStaffSatisfaction(input.getSatisfaction());
                        order.setStaffSatisfactionId(rating.getId());
                        order.setStaffSatisfactionName(rating.getName());
                    }
                }).then();
        });
    }

    /** 星形控件的序号与配置分值不同；所有展示快照均由服务端读取可信字典。 */
    private Mono<org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity> satisfaction(
            RepairOrderEntity order, RepairActionRequest input, RepairContext context) {
        RepairFlowRules.require(input.getSatisfactionId() != null && !input.getSatisfactionId().isBlank(), "satisfaction_required");
        return configurationManager.enabled(input.getSatisfactionId(), "satisfaction", order.getBusinessProjectId(), context)
            .doOnNext(row -> {
                long score = RepairConfigurationRules.score(row.getConfiguration().get("score"));
                RepairFlowRules.require(input.getSatisfaction() == null || input.getSatisfaction() == score, "satisfaction_score_mismatch");
                input.setSatisfaction(score); input.setSatisfactionName(row.getName());
            });
    }

    private Mono<Void> reply(String action, RepairOrderEntity order, RepairActionRequest input, RepairContext context) {
        // 完成/非关沿用旧表单的必填回复语；配置ID在当前项目与节点内解析可信正文。
        if (input.getReplyId() == null || input.getReplyId().isBlank()) {
            RepairFlowRules.require("follow".equals(action), "reply_required");
            input.setReply(null);
            return Mono.empty();
        }
        int node = "follow".equals(action) ? 5 : "finish".equals(action) ? 1 : 7;
        return configurationManager.enabled(input.getReplyId(), "reply", order.getBusinessProjectId(), context)
            .doOnNext(row -> {
                Map<String,Object> data = row.getConfiguration();
                RepairFlowRules.require(data != null && data.get("staticNode") instanceof Number
                    && ((Number)data.get("staticNode")).intValue() == node, "reply_node_invalid");
                input.setReply(java.util.Objects.toString(data.get("content"), row.getName()));
            }).then();
    }

    private static void requireOwner(RepairActionRequest input) {
        RepairFlowRules.require(input.getOwnerName() != null && !input.getOwnerName().isBlank()
            && input.getOwnerPhone() != null && !input.getOwnerPhone().isBlank(), "owner_information_required");
    }
}
