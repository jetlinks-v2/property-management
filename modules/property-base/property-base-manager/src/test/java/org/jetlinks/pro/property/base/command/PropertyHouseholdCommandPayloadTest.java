package org.jetlinks.pro.property.base.command;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** RPC 重建保留项目、房屋、客户及本人筛选，避免只在本地命令实例中生效。 */
class PropertyHouseholdCommandPayloadTest {
    @Test void filtersSurvivePayloadReconstruction() {
        var source = new QueryPropertyHouseholdsCommand();
        source.setProjectId("project"); source.setSpaceId("house");
        source.setCustomerId("customer"); source.setMine(true);
        var restored = new QueryPropertyHouseholdsCommand();
        source.readable().forEach(restored::with);
        assertEquals("project", restored.getProjectId());
        assertEquals("house", restored.getSpaceId());
        assertEquals("customer", restored.getCustomerId());
        assertTrue(restored.isMine());
        assertFalse(new QueryPropertyHouseholdsCommand().isMine());
    }
}
