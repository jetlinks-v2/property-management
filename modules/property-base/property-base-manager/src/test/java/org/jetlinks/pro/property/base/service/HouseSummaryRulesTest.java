package org.jetlinks.pro.property.base.service;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.pro.property.base.entity.PropertyDictionaryItemEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

class HouseSummaryRulesTest {
    @Test
    void dynamicStatesIncludeZeroAndReferencedDisabledAndIncompleteWithoutLosingRooms() {
        var saved = new PropertyHouseService.HouseView();
        saved.setCompleted(true);
        saved.setHouseState("custom");
        saved.setBillingArea(new BigDecimal("123.45"));
        saved.setBuildingArea(new BigDecimal("140.01"));
        var pending = new PropertyHouseService.HouseView();
        pending.setCompleted(false);
        var active = new PropertyDictionaryItemEntity();
        active.setValue("vacant");
        active.setText("空置");
        active.setEnabled(true);
        var retired = new PropertyDictionaryItemEntity();
        retired.setValue("custom");
        retired.setText("自定义已停用房态");
        retired.setEnabled(false);
        var unused = new PropertyDictionaryItemEntity();
        unused.setValue("unused");
        unused.setText("已停用");
        unused.setEnabled(false);
        var result =
                HouseSummaryRules.summarize(
                        List.of(saved, pending), List.of(active, retired, unused));
        assertEquals(2, result.houseCount());
        assertEquals(1, result.incompleteCount());
        assertEquals("123.45", result.billingArea());
        assertEquals("140.01", result.buildingArea());
        assertEquals(
                List.of("vacant", "custom", ""),
                result.states().stream().map(s -> s.value()).toList());
        assertEquals(List.of(0L, 1L, 1L), result.states().stream().map(s -> s.count()).toList());
        assertEquals("自定义已停用房态", result.states().get(1).text());
    }
}
