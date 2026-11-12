package org.jetlinks.pro.property.decoration.service;

import org.jetlinks.pro.property.base.house.PropertyHouseholdInfo;
import org.jetlinks.pro.property.decoration.model.DecorationModels;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;

import java.util.List;
import java.util.stream.Collectors;

import static org.jetlinks.pro.property.decoration.service.DecorationRules.require;

/** 原业主端九项自检的权威定义；正文、汇总结果与申请快照在同一创建事务内核对。 */
final class DecorationSelfInspectionForm {
    private DecorationSelfInspectionForm() { }

    private record Item(String key, String text) { }
    private record Group(String title, List<Item> items) { }
    private static final List<Group> GROUPS = List.of(
        new Group("检测项目是否完好", List.of(new Item("phoneLine", "电话线"), new Item("cableTvLine", "闭路线"),
            new Item("broadbandLine", "宽带线路"), new Item("videoIntercom", "可视分机"),
            new Item("doorMagnet", "门磁"), new Item("other", "其他"))),
        new Group("排水是否通畅", List.of(new Item("kitchenDrainage", "厨房排水"),
            new Item("masterBathroomDrainage", "主卫排水"), new Item("balconyDrainage", "生活阳台排水"))));

    static List<DecorationModels.SelfInspectionItem> snapshot(DecorationRequests.Create input) {
        var items = input.getSelfInspectionItems();
        var expected = GROUPS.stream().flatMap(group -> group.items().stream()).map(Item::key).toList();
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

    static int status(List<DecorationModels.SelfInspectionItem> items) {
        return items.stream().anyMatch(item -> !item.getValue()) ? 1 : 0;
    }

    static String content(DecorationRequests.Create input, PropertyHouseholdInfo home) {
        var items = snapshot(input);
        var answers = items.stream().collect(Collectors.toMap(DecorationModels.SelfInspectionItem::getKey, DecorationModels.SelfInspectionItem::getValue));
        var lines = new java.util.ArrayList<>(List.of("装修自检表", "房屋：" + home.spaceName(), "业主：" + home.customerName(), "联系电话：" + input.getContactPhone(), ""));
        for (var group : GROUPS) {
            lines.add(group.title());
            group.items().forEach(item -> lines.add(item.text() + "：" + (answers.get(item.key()) ? "是" : "否")));
            lines.add("");
        }
        lines.add("处理状态：" + (status(items) == 1 ? "有问题" : "没问题"));
        return String.join("\n", lines);
    }
}
