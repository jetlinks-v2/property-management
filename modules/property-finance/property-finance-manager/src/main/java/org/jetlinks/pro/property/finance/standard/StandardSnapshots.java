package org.jetlinks.pro.property.finance.standard;

import com.alibaba.fastjson.JSON;

import java.util.*;

/** 显式快照字段不依赖JSON库对record的反射构造行为。 */
public final class StandardSnapshots {
    private StandardSnapshots() {}

    public static String variables(List<VariableDefinition> definitions) {
        return JSON.toJSONString(
                definitions.stream()
                        .map(
                                variable -> {
                                    var map = new LinkedHashMap<String, Object>();
                                    map.put("id", variable.id());
                                    map.put("projectId", variable.projectId());
                                    map.put("variableCode", variable.variableCode());
                                    map.put("variableName", variable.variableName());
                                    map.put("scopeType", variable.scopeType());
                                    map.put("valueType", variable.valueType());
                                    map.put("description", variable.description());
                                    map.put("enabled", variable.enabled());
                                    map.put("sort", variable.sort());
                                    map.put("version", variable.version());
                                    return map;
                                })
                        .toList());
    }

    public static List<VariableDefinition> variables(String json) {
        return JSON.parseArray(json).stream()
                .map(
                        value -> {
                            var object = (com.alibaba.fastjson.JSONObject) value;
                            return new VariableDefinition(
                                    object.getString("id"),
                                    object.getString("projectId"),
                                    object.getString("variableCode"),
                                    object.getString("variableName"),
                                    object.getString("scopeType"),
                                    object.getString("valueType"),
                                    object.getString("description"),
                                    object.getBooleanValue("enabled"),
                                    object.getIntValue("sort"),
                                    object.getLongValue("version"));
                        })
                .toList();
    }
}
