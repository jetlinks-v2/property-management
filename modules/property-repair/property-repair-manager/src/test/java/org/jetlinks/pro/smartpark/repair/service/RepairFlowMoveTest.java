package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RepairFlowMoveTest {
    private RepairConfigurationEntity category(String name, boolean configured) {
        var row = new RepairConfigurationEntity(); row.setId(name); row.setName(name);
        row.setKind("category"); row.setBusinessProjectId("project"); row.setEnabled(true);
        row.setCreatorName(name + "创建人"); row.setModifyTime(10L);
        var data = new HashMap<String, Object>(Map.of("deviceEnabled", true, "flowConfigured", configured));
        if (configured) data.putAll(Map.of("finishTimeLimitDays", 2, "closeRoleId", "close",
            "delayRoleId", "delay", "levelChangeRoleId", "level", "flowEnabled", true));
        row.setConfiguration(data); return row;
    }

    @Test void onlyWorkflowMovesAndCategoryMetadataAndDeviceValuesStayWithEachCategory() {
        var source = category("source", true); var target = category("target", false);
        source.setFlowCreatorName("流程创建人"); source.setFlowModifyTime(8L);
        target.setEnabled(false); target.getConfiguration().put("deviceEnabled", false);
        RepairFlowConfiguration.move(source, target, Map.of("finishTimeLimitDays", 7, "deviceEnabled", true), 20L);
        assertEquals(7, target.getConfiguration().get("finishTimeLimitDays"));
        assertEquals(false, target.getConfiguration().get("deviceEnabled")); assertFalse(target.getEnabled());
        assertEquals("target", target.getName()); assertEquals("target创建人", target.getCreatorName());
        assertEquals("流程创建人", target.getFlowCreatorName()); assertEquals(20L, target.getFlowModifyTime());
        assertEquals(true, source.getConfiguration().get("deviceEnabled"));
        assertFalse(RepairFlowConfiguration.configured(source.getConfiguration()));
        assertFalse(source.getConfiguration().containsKey("closeRoleId"));
        assertNull(source.getFlowCreatorName()); assertNull(source.getFlowModifyTime());
    }

    @Test void occupiedTargetAndCrossProjectAreRejectedBeforeSourceIsChanged() {
        var source = category("source", true); var target = category("target", true);
        assertEquals("error.repair.flow_category_occupied", assertThrows(BusinessException.class,
            () -> RepairFlowConfiguration.move(source, target, Map.of(), 20L)).getMessage());
        assertTrue(RepairFlowConfiguration.configured(source.getConfiguration()));
        var destination = category("target", false); destination.setBusinessProjectId("other");
        assertEquals("error.repair.configuration_project_mismatch", assertThrows(BusinessException.class,
            () -> RepairFlowConfiguration.move(source, destination, Map.of(), 20L)).getMessage());
    }

    @Test void invalidFlowInputDoesNotClearTheSource() {
        var source = category("source", true); var target = category("target", false);
        assertThrows(BusinessException.class, () -> RepairFlowConfiguration.move(source, target, Map.of("closeRoleId", ""), 20L));
        assertEquals("close", source.getConfiguration().get("closeRoleId"));
    }

    @Test void oldFlowMetadataAndMonotonicClockSurviveReassociation() {
        var source = category("source", true); var target = category("target", false);
        source.setModifyTime(100L); target.setModifyTime(200L);
        RepairFlowConfiguration.move(source, target, Map.of(), 20L);
        assertEquals("source创建人", target.getFlowCreatorName());
        assertEquals(201L, target.getFlowModifyTime()); assertEquals(201L, source.getModifyTime());
    }

    @Test void ordinaryOldCategoriesDoNotCreatePhantomFlows() {
        assertFalse(RepairFlowConfiguration.configured(Map.of("deviceEnabled", true, "flowEnabled", true)));
        assertFalse(RepairFlowConfiguration.enabled(Map.of()));
        assertTrue(RepairFlowConfiguration.configured(Map.of("finishTimeLimitDays", 1)));
        assertTrue(RepairFlowConfiguration.configured(Map.of("flowConfigured", true)));
        assertFalse(RepairFlowConfiguration.configured(Map.of("flowConfigured", false, "finishTimeLimitDays", 1)));
    }
}
