package org.jetlinks.pro.smartpark.repair.service;

import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 配置树的唯一规则：三级分类、分类下的标签、标签下的描述选项。 */
public final class RepairConfigurationRules {
    private RepairConfigurationRules() { }
    public static final Set<String> KINDS = Set.of("channel", "category", "label", "description", "level",
        "satisfaction", "evaluationLabel", "reply", "commission", "qr", "intakeOptions");

    public static void validate(RepairConfigurationEntity input) {
        RepairFlowRules.require(KINDS.contains(input.getKind()), "configuration_kind_invalid");
        Map<String, Object> data = input.getConfiguration() == null ? Map.of() : input.getConfiguration();
        RepairFlowRules.require(!Set.of("label","satisfaction").contains(input.getKind())||input.getEnabled()!=null,"configuration_status_required");
        input.setFormOptionsProject("intakeOptions".equals(input.getKind())?input.getBusinessProjectId():null);
        input.setCommissionProject("commission".equals(input.getKind())?input.getBusinessProjectId():null);
        if("intakeOptions".equals(input.getKind())) {
            RepairFormOptions.from(data);
            RepairFlowRules.require(Boolean.TRUE.equals(input.getEnabled()),"form_options_invalid");
        }
        if ("satisfaction".equals(input.getKind())) {
            input.setSatisfactionScore(score(data.get("score")));
            input.setSatisfactionLevel(input.getName());
        } else {
            input.setSatisfactionScore(null); input.setSatisfactionLevel(null);
        }
        if ("evaluationLabel".equals(input.getKind())) {
            RepairFlowRules.require(!Objects.toString(data.get("satisfactionId"), "").isBlank(), "satisfaction_required");
        }
        if (Set.of("reply", "description").contains(input.getKind())) {
            String content = Objects.toString(data.get("content"), "");
            RepairFlowRules.require(!content.isBlank() && content.length() <= ("reply".equals(input.getKind()) ? 50 : 800), "configuration_content_invalid");
        }
        if ("reply".equals(input.getKind())) {
            RepairFlowRules.require(data.get("staticNode") instanceof Number && Set.of(1, 5, 7).contains(((Number)data.get("staticNode")).intValue()), "reply_node_invalid");
        }
        if ("commission".equals(input.getKind())) RepairCommerceService.validateCommission(data);
        if ("qr".equals(input.getKind())) RepairQrRules.validate(input);
        else input.setQrCode(null);
        if ("level".equals(input.getKind())) {
            RepairFlowRules.require(input.getName() != null && !input.getName().isBlank() && input.getName().length() <= 64, "level_name_invalid");
            RepairFlowRules.require(!Objects.toString(data.get("color"), "").isBlank(), "level_color_required");
            input.setLevelCoefficient(levelCoefficient(data.get("coefficient")));
            for (String flag : java.util.List.of("auditRequired", "thirdParty")) {
                RepairFlowRules.require(!data.containsKey(flag) || data.get(flag) == null || data.get(flag) instanceof Boolean, "level_flag_invalid");
            }
            RepairFlowRules.require(!Boolean.TRUE.equals(data.get("auditRequired"))
                || !Objects.toString(data.get("auditRoleId"), "").isBlank(), "audit_role_required");
            var normalized = new java.util.HashMap<>(data);
            normalized.put("auditRequired", Boolean.TRUE.equals(data.get("auditRequired")));
            normalized.put("thirdParty", Boolean.TRUE.equals(data.get("thirdParty")));
            if (!Boolean.TRUE.equals(data.get("auditRequired"))) {
                normalized.remove("auditRoleId"); normalized.remove("auditRoleName");
            }
            input.setConfiguration(normalized);
        } else input.setLevelCoefficient(null);
        input.setCategoryParentKey("category".equals(input.getKind())?categoryParent(input.getParentId()):null);
        input.setCategoryNameKey("category".equals(input.getKind())?input.getName():null);
        if ("category".equals(input.getKind())) {
            boolean automatic = Boolean.TRUE.equals(data.get("autoDispatchEnable"));
            boolean followRole = !Objects.toString(data.get("followRoleId"), "").isBlank();
            RepairFlowRules.require(!(automatic && followRole), "dispatch_modes_conflict");
            if (automatic) {
                RepairFlowRules.require(data.get("autoDispatchMinutes") instanceof Number
                    && ((Number) data.get("autoDispatchMinutes")).longValue() >= 1, "dispatch_minutes_required");
                RepairFlowRules.require(!Objects.toString(data.get("autoDispatchRoleId"), "").isBlank(), "dispatch_role_required");
            }
            long days = ((Number) data.getOrDefault("finishTimeLimitDays", 1)).longValue();
            RepairFlowRules.require(days >= 0, "finish_days_invalid");
            RepairFlowRules.require(Objects.toString(data.get("remark"), "").length() <= 200, "flow_remark_too_long");
            if (Boolean.TRUE.equals(data.get("flowConfigured"))) {
                for (String role : java.util.List.of("closeRoleId", "delayRoleId", "levelChangeRoleId")) {
                    RepairFlowRules.require(!Objects.toString(data.get(role), "").isBlank(), "flow_roles_required");
                }
                RepairFlowRules.require(!Boolean.TRUE.equals(data.get("callbackRequired"))
                    || !Objects.toString(data.get("callbackRoleId"), "").isBlank(), "callback_role_required");
            }
        }
    }

    /** 不截断小数或默认补1，保持原等级表单的必填整数范围。 */
    public static int levelCoefficient(Object value) {
        try {
            int coefficient = new java.math.BigDecimal(Objects.toString(value, "")).intValueExact();
            RepairFlowRules.require(coefficient >= 1 && coefficient <= 999, "level_coefficient_invalid");
            return coefficient;
        } catch (NumberFormatException | ArithmeticException error) {
            throw new org.hswebframework.web.exception.BusinessException("error.repair.level_coefficient_invalid", 400);
        }
    }

    static String categoryParent(String id) { return id == null || id.isBlank() ? "" : id; }

    public static long score(Object value) {
        try {
            return new java.math.BigDecimal(Objects.toString(value, "")).longValueExact();
        } catch (NumberFormatException | ArithmeticException error) {
            throw new org.hswebframework.web.exception.BusinessException("error.repair.satisfaction_score_invalid", 400);
        }
    }

    public static void evaluationLabel(RepairConfigurationEntity label, String satisfactionId) {
        RepairFlowRules.require(label.getConfiguration() != null
            && Objects.equals(label.getConfiguration().get("satisfactionId"), satisfactionId), "evaluation_label_mismatch");
    }

    public static void parent(RepairConfigurationEntity child, RepairConfigurationEntity parent) {
        RepairFlowRules.require(child.getId() == null || !Objects.equals(child.getId(), parent.getId()), "configuration_parent_invalid");
        RepairFlowRules.require(Objects.equals(child.getBusinessProjectId(), parent.getBusinessProjectId()), "configuration_project_mismatch");
        String expected = "description".equals(child.getKind()) ? "label" : "category";
        RepairFlowRules.require(expected.equals(parent.getKind()), "configuration_parent_invalid");
    }
}
