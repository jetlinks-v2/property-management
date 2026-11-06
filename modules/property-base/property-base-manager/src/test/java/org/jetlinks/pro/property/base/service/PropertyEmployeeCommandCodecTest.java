package org.jetlinks.pro.property.base.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.jetlinks.pro.property.base.command.QueryPropertyEmployeePageCommand;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.junit.jupiter.api.Test;

class PropertyEmployeeCommandCodecTest {
    private final QueryPropertyEmployeePageCommand command = new QueryPropertyEmployeePageCommand();

    @Test
    void decodesRemotePageRowsBeforeExternalOrganizationChecks() {
        var row = Map.of("id", "contractor", "name", "外协员工", "organizationIds", List.of("external"));
        var raw = new PagerResult<>(7, List.of(row));
        raw.setPageIndex(2);
        raw.setPageSize(3);
        var result = command.createResponseData(raw);
        assertEquals("contractor", result.getData().get(0).id());
        assertEquals(List.of("external"), result.getData().get(0).organizationIds());
        assertEquals(7, result.getTotal());
        assertEquals(2, result.getPageIndex());
        assertEquals(3, result.getPageSize());
    }

    @Test
    void decodesMapEnvelopeAndRetainsAlreadyTypedLocalRows() {
        var local = new PropertyEmployeeInfo("staff", "内部员工", null, List.of("internal"));
        var result = command.createResponseData(Map.of("total", 1, "pageIndex", 0, "pageSize", 10, "data", List.of(local)));
        assertSame(local, result.getData().get(0));
        assertEquals(List.of("internal"), result.getData().get(0).organizationIds());
        assertTrue(command.createResponseData(new PagerResult<>(0, List.of())).getData().isEmpty());
    }
}
