package org.jetlinks.pro.smartpark.repair.service;

import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 二维码配置的存储契约。分类与空间均保存真实ID，不接受旧系统外部字典值。 */
public final class RepairQrRules {
    private RepairQrRules() { }

    public static void validate(RepairConfigurationEntity input) {
        Map<String,Object> data = input.getConfiguration() == null ? Map.of() : input.getConfiguration();
        String code = Objects.toString(data.get("code"), "");
        RepairFlowRules.require(code.matches("[A-Za-z0-9_-]{1,60}") && input.getName().length() <= 40, "qr_settings_invalid");
        String welcome = Objects.toString(data.get("welcomeText"), "");
        RepairFlowRules.require(!welcome.isBlank() && welcome.length() <= 800
            && !Objects.toString(data.get("channelId"), "").isBlank(), "qr_settings_invalid");
        for (String key : List.of("categoryIds", "spaceRootIds")) {
            RepairFlowRules.require(!ids(data,key).isEmpty() && ids(data,key).size() <= 200, "qr_settings_invalid");
        }
        RepairFlowRules.require(ids(data,"publicAreaRootIds").size() <= 200, "qr_settings_invalid");
        if (Boolean.TRUE.equals(data.get("enableCampus"))) {
            RepairFlowRules.require(!areas(data).isEmpty() && areas(data).size() <= 100, "qr_settings_invalid");
            for (Map<String,Object> area : areas(data)) {
                RepairFlowRules.require(!Objects.toString(area.get("name"), "").isBlank()
                    && !ids(area,"spaceRootIds").isEmpty(), "qr_settings_invalid");
            }
        }
        input.setQrCode(code);
    }

    public static List<String> ids(Map<String,Object> data, String key) {
        Object value = data.get(key);
        if (value == null) return List.of();
        RepairFlowRules.require(value instanceof Collection<?>, "qr_settings_invalid");
        return ((Collection<?>) value).stream().map(item -> {
            RepairFlowRules.require(item instanceof String && !((String)item).isBlank()
                && ((String)item).length() <= 64, "qr_settings_invalid");
            return (String)item;
        }).distinct().toList();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String,Object>> areas(Map<String,Object> data) {
        Object value = data.get("campusOptions");
        if (value == null) return List.of();
        RepairFlowRules.require(value instanceof List<?> && ((List<?>)value).stream().allMatch(Map.class::isInstance), "qr_settings_invalid");
        return (List<Map<String,Object>>)value;
    }
}
