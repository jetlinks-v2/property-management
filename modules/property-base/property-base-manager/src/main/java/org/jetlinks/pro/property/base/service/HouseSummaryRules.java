package org.jetlinks.pro.property.base.service;

import org.jetlinks.pro.property.base.archive.PropertyHouseSummary;
import org.jetlinks.pro.property.base.entity.PropertyDictionaryItemEntity;

import java.math.BigDecimal;
import java.util.*;

/** 只汇总已通过档案权限过滤的末级空间；不把面积缺失当作房屋不存在。 */
final class HouseSummaryRules {
    private HouseSummaryRules() {}

    static PropertyHouseSummary summarize(
            List<PropertyHouseService.HouseView> rows,
            List<PropertyDictionaryItemEntity> dictionary) {
        BigDecimal building = BigDecimal.ZERO, billing = BigDecimal.ZERO;
        long incomplete = 0;
        var counts = new LinkedHashMap<String, Long>();
        for (var row : rows) {
            boolean completed = Boolean.TRUE.equals(row.getCompleted());
            if (!completed) incomplete++;
            if (completed && row.getBuildingArea() != null)
                building = building.add(row.getBuildingArea());
            if (completed && row.getBillingArea() != null)
                billing = billing.add(row.getBillingArea());
            var value = completed ? Objects.toString(row.getHouseState(), "") : "";
            counts.merge(value, 1L, Long::sum);
        }
        var states = new ArrayList<PropertyHouseSummary.StateCount>();
        for (var item : dictionary) {
            long count = counts.getOrDefault(item.getValue(), 0L);
            if (Boolean.TRUE.equals(item.getEnabled()) || count > 0)
                states.add(
                        new PropertyHouseSummary.StateCount(
                                item.getValue(), item.getText(), count));
            counts.remove(item.getValue());
        }
        // 非字典历史值不能丢掉；空值使用独立未设置项，展示名称由消费端国际化。
        counts.forEach(
                (value, count) -> {
                    if (!value.isBlank())
                        states.add(new PropertyHouseSummary.StateCount(value, value, count));
                });
        states.add(new PropertyHouseSummary.StateCount("", "", counts.getOrDefault("", 0L)));
        return new PropertyHouseSummary(
                rows.size(),
                incomplete,
                building.setScale(2).toPlainString(),
                billing.setScale(2).toPlainString(),
                states);
    }
}
