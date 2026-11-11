package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import org.jetlinks.pro.property.facility.entity.DeviceEntity;
import org.jetlinks.sdk.server.device.DeviceInfo;
import org.junit.jupiter.api.Test;
import java.util.List;

class DeviceAllocationRulesTest {
  @Test void gasAllocationPreservesEvidenceUntilTheEnergyTypeChanges() {
    DeviceAllocationRules.type("gas");
    var row = new DeviceEntity(); row.setProjectId("p"); row.setSpaceId("s"); row.setMeterType("gas"); row.setAllocationSince(100L);
    DeviceAllocationRules.changed(row, "p", "s", "gas", 200L);
    assertEquals(100L, row.getAllocationSince());
    DeviceAllocationRules.changed(row, "p", "s", "water", 200L);
    assertEquals(200L, row.getAllocationSince());
  }
  @Test void allocationKeepsEvidenceOnlyWhenProjectSpaceAndKindAreUnchanged() {
    var row = new DeviceEntity();
    row.setProjectId("p"); row.setSpaceId("s"); row.setMeterType("water");
    row.setAllocationSince(100L);
    DeviceAllocationRules.changed(row, "p", "s", "water", 200L);
    assertEquals(100L, row.getAllocationSince());
    DeviceAllocationRules.changed(row, "p", "other", "water", 200L);
    assertEquals(200L, row.getAllocationSince());
    assertThrows(RuntimeException.class, () -> DeviceAllocationRules.type("unknown"));
  }
  @Test void syncDoesNotOverwriteConfirmedBusinessProject() {
    var row = new DeviceEntity(); row.setProjectId("business-project");
    row.setProjectConfirmed(true); row.setMeterType("electricity");
    FacilitySync.mergeSource(row, new DeviceInfo(), List.of("cloud-project"), 500L);
    assertEquals("active", row.getSyncState());
    assertEquals("business-project", row.getProjectId());
    assertEquals("electricity", row.getMeterType());
  }
}
