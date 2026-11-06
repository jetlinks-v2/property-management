package org.jetlinks.pro.property.base.command;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void chargingParametersSurviveTheActualCommandPayloadBoundary() {
        var original = new QueryPropertyHousesCommand();
        original.setProjectId("p");
        original.setSpaceId("s");
        original.setKeyword("101");
        original.setPageIndex(3);
        original.setPageSize(20);
        var copy = new QueryPropertyHousesCommand();
        original.readable().forEach(copy::with);
        assertEquals("p", copy.getProjectId());
        assertEquals("s", copy.getSpaceId());
        assertEquals("101", copy.getKeyword());
        assertEquals(3, copy.getPageIndex());
        assertEquals(20, copy.getPageSize());
        var context = new QueryPropertyHouseContextCommand();
        context.setProjectId("p");
        context.setSpaceId("s");
        context.setForUpdate(true);
        var restored = new QueryPropertyHouseContextCommand();
        context.readable().forEach(restored::with);
        assertEquals("p", restored.getProjectId());
        assertEquals("s", restored.getSpaceId());
        assertTrue(restored.isForUpdate());
        var project = new QueryPropertyProjectCommand();
        project.setProjectId("p");
        var restoredProject = new QueryPropertyProjectCommand();
        project.readable().forEach(restoredProject::with);
        assertEquals("p", restoredProject.getProjectId());
        var dictionary = new QueryPropertyDictionaryOptionsCommand();
        dictionary.setTypeCode("payment-method");
        var restoredDictionary = new QueryPropertyDictionaryOptionsCommand();
        dictionary.readable().forEach(restoredDictionary::with);
        assertEquals("payment-method", restoredDictionary.getTypeCode());
        var spaces = new QueryPropertySpaceNodesCommand();
        spaces.setProjectId("p");
        var restoredSpaces = new QueryPropertySpaceNodesCommand();
        spaces.readable().forEach(restoredSpaces::with);
        assertEquals("p", restoredSpaces.getProjectId());
        assertEquals(12, new QueryPropertyHousesCommand().getPageSize());
        assertFalse(new QueryPropertyHouseContextCommand().isForUpdate());
    }
}
