package org.jetlinks.pro.smartpark.repair.service;

import com.alibaba.fastjson.JSON;
import org.jetlinks.pro.smartpark.repair.api.CreateQualityRepairCommand;
import org.jetlinks.pro.smartpark.repair.api.CreateRepairRequest;
import org.jetlinks.pro.smartpark.repair.api.GetQualityRepairCommand;
import org.jetlinks.pro.smartpark.repair.api.QueryQualityRepairOptionsCommand;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 验证 RPC 实际使用的命令属性负载，而非同进程 DTO 字段引用。 */
class QualityRepairCommandPayloadTest {
    @Test
    void creationKeepsTargetSourceAndNestedFormAfterWireRoundTrip() {
        var source = new CreateQualityRepairCommand();
        source.setTargetApplicationId("repair-app");
        source.setSourceRecordId("record-1");
        var form = new CreateRepairRequest();
        form.setBusinessProjectId("project-a");
        form.setDescription("Blocked exit");
        form.setAttachmentIds(List.of("photo-a"));
        source.setRequest(form);
        var remote = new CreateQualityRepairCommand();
        remote.with(JSON.parseObject(JSON.toJSONString(source.asMap())));
        assertEquals("repair-app", remote.getTargetApplicationId());
        assertEquals("record-1", remote.getSourceRecordId());
        assertNotNull(remote.getRequest());
        assertEquals("project-a", remote.getRequest().getBusinessProjectId());
        assertEquals("Blocked exit", remote.getRequest().getDescription());
        assertEquals(List.of("photo-a"), remote.getRequest().getAttachmentIds());
    }

    @Test
    void optionsAndLinkedReadKeepScopeAfterWireRoundTrip() {
        var source = new QueryQualityRepairOptionsCommand();
        source.setTargetApplicationId("repair-app");
        source.setProjectId("project-a");
        var options = new QueryQualityRepairOptionsCommand();
        options.with(JSON.parseObject(JSON.toJSONString(source.asMap())));
        assertEquals("repair-app", options.getTargetApplicationId());
        assertEquals("project-a", options.getProjectId());
        var get = new GetQualityRepairCommand();
        get.setTargetApplicationId("repair-app");
        get.setSourceRecordId("record-1");
        var remote = new GetQualityRepairCommand();
        remote.with(JSON.parseObject(JSON.toJSONString(get.asMap())));
        assertEquals("repair-app", remote.getTargetApplicationId());
        assertEquals("record-1", remote.getSourceRecordId());
    }
}
