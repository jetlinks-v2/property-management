package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.jetlinks.pro.property.facility.entity.DeviceEntity;
import org.jetlinks.sdk.server.device.DeviceInfo;
import org.junit.jupiter.api.Test;

class FacilitySyncTest {

  @Test
  void sourceUpdatesKeepExtensionsAndConfirmedOverlappingProject() {
    var row = new DeviceEntity();
    row.setProjectId("p");
    row.setProjectConfirmed(true);
    row.setName("业务名称");
    row.setManufacturer("厂商");
    row.setInspectionEnabled(true);
    var source = new DeviceInfo();
    source.setId("cloud");
    source.setName("新Cloud名称");
    FacilitySync.mergeSource(row, source, List.of("p", "q"), 123);
    assertEquals("active", row.getSyncState());
    assertEquals("业务名称", row.getName());
    assertEquals("厂商", row.getManufacturer());
    assertTrue(row.getInspectionEnabled());
    assertEquals("新Cloud名称", row.getSourceName());
  }

  @Test
  void ambiguousAndMigratedProjectsRequireConfirmation() {
    var row = new DeviceEntity();
    row.setProjectId("p");
    var source = new DeviceInfo();
    source.setId("cloud");
    FacilitySync.mergeSource(row, source, List.of("p", "q"), 1);
    assertEquals("pending-project", row.getSyncState());
    FacilitySync.mergeSource(row, source, List.of("q"), 2);
    assertEquals("pending-project", row.getSyncState());
    assertEquals("q", row.getPendingProjectId());
    assertEquals("p", row.getProjectId());
  }
}
