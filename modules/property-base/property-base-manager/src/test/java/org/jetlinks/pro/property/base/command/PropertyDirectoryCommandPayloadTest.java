package org.jetlinks.pro.property.base.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 命令通过 readable 参数传输，不能仅在原 Java 实例字段上保持正确。 */
class PropertyDirectoryCommandPayloadTest {
    @Test
    void publishedCustomerDirectoryFiltersSurviveRpcPayload() {
        var request = new QueryPropertyCustomersCommand();
        request.setProjectId("project-a"); request.setSpaceId("house-a");
        var restored = new QueryPropertyCustomersCommand();
        request.readable().forEach(restored::with);
        assertEquals("project-a", restored.getProjectId());
        assertEquals("house-a", restored.getSpaceId());
        assertEquals("propertyService:customer", QueryPropertyCustomersCommand.SERVICE_ID);
    }
    @Test
    void householdAndSealFiltersSurviveRpcPayload() {
        var command = new QueryPropertyHouseholdsCommand(); command.setProjectId("project");
        command.setSpaceId("house"); command.setCustomerId("customer"); command.setMine(true);
        var restored = new QueryPropertyHouseholdsCommand(); command.readable().forEach(restored::with);
        assertEquals("project", restored.getProjectId()); assertEquals("house", restored.getSpaceId());
        assertEquals("customer", restored.getCustomerId()); assertTrue(restored.isMine());
        var seal = new GetPropertyProjectSealCommand(); seal.setProjectId("project");
        var copy = new GetPropertyProjectSealCommand(); seal.readable().forEach(copy::with);
        assertEquals("project", copy.getProjectId());
    }
    @Test
    void dictionaryTypeSurvivesCommandPayloadReconstruction() {
        var request = new QueryPropertyDictionaryItemsCommand(); request.setTypeCode("house-type");
        var restored = new QueryPropertyDictionaryItemsCommand(); request.readable().forEach(restored::with);
        assertEquals("house-type", restored.getTypeCode());
    }
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
