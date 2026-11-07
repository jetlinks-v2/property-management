package org.jetlinks.pro.property.finance.standard;

import org.jetlinks.pro.property.finance.entity.FormulaVariableEntity;

import java.util.List;

/** 可冻结到公式快照中的变量定义；系统注册表与旧初始化脚本一致。 */
public record VariableDefinition(
        String id,
        String projectId,
        String variableCode,
        String variableName,
        String scopeType,
        String valueType,
        String description,
        boolean enabled,
        int sort,
        long version) {
    public static final List<VariableDefinition> SYSTEM =
            List.of(
                    system("STANDARD_PRICE", "收费标准_单价", "收费标准配置的单价", 10),
                    system("BILLING_AREA", "计费面积", "后续计费环节提供", 20),
                    system("BILLING_PERSON", "计费人数", "后续计费环节提供", 30),
                    system("PERIOD_MONTHS", "起止时间（月）", "由计费起止日期计算", 40),
                    system("CONTRACT_COUNT", "合同约定次数", "后续计费环节提供", 50),
                    system("WORKLOAD", "工程量数值", "后续计费环节提供", 60));

    private static VariableDefinition system(String code, String name, String desc, int sort) {
        return new VariableDefinition(
                "SYSTEM:" + code, null, code, name, "SYSTEM", "DECIMAL", desc, true, sort, 0);
    }

    public static VariableDefinition of(FormulaVariableEntity entity) {
        return new VariableDefinition(
                entity.getId(),
                entity.getProjectId(),
                entity.getVariableCode(),
                entity.getVariableName(),
                "PROJECT",
                "DECIMAL",
                entity.getDescription(),
                Boolean.TRUE.equals(entity.getEnabled()),
                entity.getSort(),
                entity.getVersion());
    }
}
