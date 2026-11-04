package org.jetlinks.pro.property.base.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 命令通过 readable 参数传输，不能仅在原 Java 实例字段上保持正确。 */
class PropertyDirectoryCommandPayloadTest {
    @Test
    void spaceProjectSurvivesCommandPayloadReconstruction() {
        var request = new QueryPropertySpacesCommand();
        request.setProjectId("project-a");
        var restored = new QueryPropertySpacesCommand();
        request.readable().forEach(restored::with);
        assertEquals("project-a", restored.getProjectId());
    }

    @Test
    void userIdsAndEmployeeFilterSurviveCommandPayloadReconstruction() {
        var request = new QueryPropertyUsersCommand();
        request.setIds(List.of("worker-a"));
        request.setEmployeesOnly(true);
        var restored = new QueryPropertyUsersCommand();
        request.readable().forEach(restored::with);
        assertEquals(List.of("worker-a"), restored.getIds());
        assertTrue(restored.isEmployeesOnly());
        assertEquals(List.of(), new QueryPropertyUsersCommand().getIds());
        assertFalse(new QueryPropertyUsersCommand().isEmployeesOnly());
    }
}
