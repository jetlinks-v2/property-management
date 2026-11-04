package org.jetlinks.pro.smartpark.repair.service;

import java.util.Map;
import java.util.List;
import java.util.Objects;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;

/** 分类下的流程配置拥有独立状态；既有持久化记录未带状态字段时保留原流程。 */
public final class RepairFlowConfiguration {
    public static final List<String> FIELDS = List.of("flowConfigured", "flowEnabled", "callbackRequired", "callbackRoleId",
        "finishTimeLimitDays", "transferLimit", "delayLimit", "delayRoleId", "closeRoleId", "levelChangeRoleId",
        "abandonRoleId", "followRoleId", "autoDispatchEnable", "autoDispatchMinutes", "autoDispatchRoleId", "remark", "flowRoleNames");
    private RepairFlowConfiguration() { }

    public static boolean configured(Map<String, Object> configuration) {
        if (configuration.get("flowConfigured") instanceof Boolean flag) return flag;
        // 合并存储的旧流程有时限/角色等参数；仅有分类、设备或默认状态不代表已有流程。
        return FIELDS.stream().filter(key -> !List.of("flowConfigured", "flowEnabled").contains(key))
            .anyMatch(configuration::containsKey);
    }

    public static boolean enabled(Map<String, Object> configuration) {
        return configured(configuration) && !Boolean.FALSE.equals(configuration.get("flowEnabled"));
    }

    /** 旧版需要启用流程才能计算时限、转派、延期和提交降级审核。 */
    public static void requireEnabled(Map<String, Object> configuration) {
        RepairFlowRules.require(enabled(configuration), "flow_disabled");
    }

    public static Map<String, Object> cleared(Map<String, Object> configuration) {
        var result = new java.util.HashMap<>(configuration);
        FIELDS.forEach(result::remove);
        result.put("flowConfigured", false); result.put("flowEnabled", true);
        return result;
    }

    /** 只重关联流程字段，不改分类本身或历史工单；父分类也不提供流程继承。 */
    static void move(RepairConfigurationEntity source, RepairConfigurationEntity target,
                     Map<String, Object> input, long now) {
        RepairFlowRules.require("category".equals(source.getKind()) && "category".equals(target.getKind()), "configuration_kind_invalid");
        RepairFlowRules.require(Objects.equals(source.getBusinessProjectId(), target.getBusinessProjectId()), "configuration_project_mismatch");
        RepairFlowRules.require(configured(source.getConfiguration()), "flow_not_configured");
        RepairFlowRules.require(!configured(target.getConfiguration()), "flow_category_occupied");
        var data = new java.util.HashMap<>(target.getConfiguration());
        FIELDS.forEach(data::remove);
        for (String field : FIELDS) {
            if (source.getConfiguration().containsKey(field)) data.put(field, source.getConfiguration().get(field));
            if (input.containsKey(field)) data.put(field, input.get(field));
        }
        data.put("flowConfigured", true);
        target.setConfiguration(data);
        RepairConfigurationRules.validate(target);
        target.setFlowCreatorName(source.getFlowCreatorName() == null ? source.getCreatorName() : source.getFlowCreatorName());
        long modified = Math.max(now, Math.max(Objects.requireNonNullElse(source.getModifyTime(), 0L),
            Objects.requireNonNullElse(target.getModifyTime(), 0L)) + 1);
        target.setFlowModifyTime(modified); target.setModifyTime(modified);
        source.setConfiguration(cleared(source.getConfiguration()));
        source.setFlowCreatorName(null); source.setFlowModifyTime(null); source.setModifyTime(modified);
    }

    /** 只由可信保存边界维护；已持久化的合并记录沿用当时已有的创建人和更新时间。 */
    static void record(RepairConfigurationEntity input, RepairConfigurationEntity previous, RepairContext context, long now) {
        if (!"category".equals(input.getKind()) || !configured(input.getConfiguration())) {
            input.setFlowCreatorName(null);
            input.setFlowModifyTime(null);
            return;
        }
        if (previous == null || !configured(previous.getConfiguration())) {
            input.setFlowCreatorName(context.userName());
            input.setFlowModifyTime(now);
            return;
        }
        input.setFlowCreatorName(previous.getFlowCreatorName() == null ? previous.getCreatorName() : previous.getFlowCreatorName());
        boolean changed = FIELDS.stream().anyMatch(key -> !Objects.equals(previous.getConfiguration().get(key), input.getConfiguration().get(key)));
        input.setFlowModifyTime(changed ? now : previous.getFlowModifyTime() == null ? previous.getModifyTime() : previous.getFlowModifyTime());
    }
}
