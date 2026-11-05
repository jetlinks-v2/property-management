package org.jetlinks.pro.property.finance.standard;

/** 项目自定义变量维护契约；没有可覆盖系统变量或固定数值的字段。 */
public record VariableInput(
        String variableCode,
        String variableName,
        String description,
        Integer sort,
        Boolean enabled,
        Long version) {}
