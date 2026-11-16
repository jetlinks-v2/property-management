package org.jetlinks.pro.property.decoration.service;

import org.jetlinks.pro.property.base.house.PropertyHouseholdInfo;
import org.jetlinks.pro.property.decoration.model.DecorationModels;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;

import java.util.List;
import java.util.stream.Collectors;

import static org.jetlinks.pro.property.decoration.service.DecorationRules.require;

/** 装修自检配置的服务端校验；正文、汇总结果与申请快照在同一创建事务内核对。 */
final class DecorationSelfInspectionForm {
    private DecorationSelfInspectionForm() { }

    private record Item(String key, String text) { }
    private record Group(String title, List<Item> items) { }
    private static final List<DecorationModels.SelfInspectionDefinition> DEFAULTS = List.of(
        definition("phoneLine", "finish", "检测项目是否完好", "是否完好", "电话线", 1), definition("cableTvLine", "finish", "检测项目是否完好", "是否完好", "闭路线", 2),
        definition("broadbandLine", "finish", "检测项目是否完好", "是否完好", "宽带线路", 3), definition("videoIntercom", "finish", "检测项目是否完好", "是否完好", "可视分机", 4),
        definition("doorMagnet", "finish", "检测项目是否完好", "是否完好", "门磁", 5), definition("other", "finish", "检测项目是否完好", "是否完好", "其他", 6),
        definition("kitchenDrainage", "drainage", "排水是否通畅", "排水是否通畅", "厨房排水", 101), definition("masterBathroomDrainage", "drainage", "排水是否通畅", "排水是否通畅", "主卫排水", 102), definition("balconyDrainage", "drainage", "排水是否通畅", "排水是否通畅", "生活阳台排水", 103));
    private static DecorationModels.SelfInspectionDefinition definition(String id,String group,String groupTitle,String optionTitle,String name,int sort){var d=new DecorationModels.SelfInspectionDefinition();d.setId(id);d.setGroup(group);d.setGroupTitle(groupTitle);d.setOptionTitle(optionTitle);d.setName(name);d.setSort(sort);return d;}
    private static List<DecorationModels.SelfInspectionDefinition> definitions(List<DecorationModels.SelfInspectionDefinition> input){return input==null||input.isEmpty()?DEFAULTS:input.stream().filter(DecorationModels.SelfInspectionDefinition::isEnabled).sorted(java.util.Comparator.comparingInt(DecorationModels.SelfInspectionDefinition::getSort)).toList();}
    private static List<Group> groups(List<DecorationModels.SelfInspectionDefinition> input){var groups=new java.util.LinkedHashMap<String,Group>();for(var d:definitions(input)){var group=groups.get(d.getGroup());if(group==null){group=new Group(d.getGroupTitle(),new java.util.ArrayList<>());groups.put(d.getGroup(),group);}group.items().add(new Item(d.getId(),d.getName()));}return List.copyOf(groups.values());}

    static List<DecorationModels.SelfInspectionItem> snapshot(DecorationRequests.Create input, List<DecorationModels.SelfInspectionDefinition> configured) {
        var items = input.getSelfInspectionItems();
        var expected = definitions(configured).stream().map(DecorationModels.SelfInspectionDefinition::getId).toList();
        require(items.size() == expected.size()
            && items.stream().map(DecorationModels.SelfInspectionItem::getKey).collect(Collectors.toSet()).equals(java.util.Set.copyOf(expected)),
            "invalid_self_inspection");
        var answers = items.stream().collect(Collectors.toMap(DecorationModels.SelfInspectionItem::getKey, DecorationModels.SelfInspectionItem::getValue));
        return expected.stream().map(key -> {
            var item = new DecorationModels.SelfInspectionItem();
            item.setKey(key); item.setValue(answers.get(key));
            return item;
        }).toList();
    }
    static List<DecorationModels.SelfInspectionItem> snapshot(DecorationRequests.Create input) { return snapshot(input, DEFAULTS); }

    static int status(List<DecorationModels.SelfInspectionItem> items) {
        return items.stream().anyMatch(item -> !item.getValue()) ? 1 : 0;
    }

    static String content(DecorationRequests.Create input, PropertyHouseholdInfo home, List<DecorationModels.SelfInspectionDefinition> configured) {
        var items = snapshot(input, configured);
        var answers = items.stream().collect(Collectors.toMap(DecorationModels.SelfInspectionItem::getKey, DecorationModels.SelfInspectionItem::getValue));
        var lines = new java.util.ArrayList<>(List.of("装修自检表", "房屋：" + home.spaceName(), "业主：" + home.customerName(), "联系电话：" + input.getContactPhone(), ""));
        for (var group : groups(configured)) {
            lines.add(group.title());
            group.items().forEach(item -> lines.add(item.text() + "：" + (answers.get(item.key()) ? "是" : "否")));
            lines.add("");
        }
        lines.add("处理状态：" + (status(items) == 1 ? "有问题" : "没问题"));
        return String.join("\n", lines);
    }
    static String content(DecorationRequests.Create input, PropertyHouseholdInfo home) { return content(input, home, DEFAULTS); }
}
