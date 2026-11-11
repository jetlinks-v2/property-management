package org.jetlinks.pro.property.facility.service;

import java.util.Objects;
import java.util.Set;
import org.jetlinks.pro.property.facility.entity.DeviceEntity;

/** Business allocation is distinct from source Cloud organization membership. */
public final class DeviceAllocationRules {
  private DeviceAllocationRules() {}
  public static String type(String type) {
    FacilityScope.require(Set.of("device", "water", "electricity", "gas").contains(Objects.toString(type, "")), "allocation_invalid");
    return type;
  }
  public static void changed(DeviceEntity row, String project, String space, String type, long now) {
    type(type);
    if (row.getAllocationSince() == null || !Objects.equals(project, row.getProjectId()) ||
      !Objects.equals(space, row.getSpaceId()) || !Objects.equals(type, row.getMeterType())) row.setAllocationSince(now);
  }
}
