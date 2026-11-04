package org.jetlinks.pro.smartpark.repair.service;

import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RepairFlowMetadataTest {
    private final RepairContext editor = new RepairContext("tenant", "app", "editor", "流程编辑人");
    private RepairConfigurationEntity row(boolean configured) {
        var row = new RepairConfigurationEntity();
        row.setKind("category");row.setCreatorName("分类创建人");row.setModifyTime(10L);
        row.setConfiguration(new HashMap<>(Map.of("flowConfigured",configured,"finishTimeLimitDays",1)));
        return row;
    }
    @Test void newFlowUsesAuthenticatedCreatorInsteadOfSubmittedMetadata() {
        var input=row(true);input.setFlowCreatorName("伪造创建人");input.setFlowModifyTime(-1L);
        RepairFlowConfiguration.record(input,row(false),editor,20L);
        assertEquals("流程编辑人",input.getFlowCreatorName());assertEquals(20L,input.getFlowModifyTime());
    }
    @Test void categoryOnlyEditPreservesSeparateFlowClockAndCreator() {
        var previous=row(true);previous.setFlowCreatorName("流程原创建人");previous.setFlowModifyTime(5L);
        var input=row(true);input.setName("修改分类名称");input.getConfiguration().put("deviceEnabled",true);
        RepairFlowConfiguration.record(input,previous,editor,20L);
        assertEquals("流程原创建人",input.getFlowCreatorName());assertEquals(5L,input.getFlowModifyTime());
    }
    @Test void flowEditUpdatesClockWithoutChangingItsCreator() {
        var previous=row(true);previous.setFlowCreatorName("流程原创建人");previous.setFlowModifyTime(5L);
        var input=row(true);input.getConfiguration().put("flowEnabled",false);
        RepairFlowConfiguration.record(input,previous,editor,20L);
        assertEquals("流程原创建人",input.getFlowCreatorName());assertEquals(20L,input.getFlowModifyTime());
    }
    @Test void persistedCombinedRecordRetainsItsKnownHistoricalValues() {
        var input=row(true);RepairFlowConfiguration.record(input,row(true),editor,20L);
        assertEquals("分类创建人",input.getFlowCreatorName());assertEquals(10L,input.getFlowModifyTime());
    }
    @Test void unconfiguredCategoryAndOtherDictionariesDoNotRetainForgedFlowMetadata() {
        for(var input:java.util.List.of(row(false),row(true))){
            if(Boolean.TRUE.equals(input.getConfiguration().get("flowConfigured")))input.setKind("channel");
            input.setFlowCreatorName("伪造");input.setFlowModifyTime(999L);
            RepairFlowConfiguration.record(input,null,editor,20L);
            assertNull(input.getFlowCreatorName());assertNull(input.getFlowModifyTime());
        }
    }
}
