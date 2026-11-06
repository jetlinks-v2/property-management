package org.jetlinks.pro.property.facility.asset;

import java.util.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;

/** 设施业务资产在组织及应用维度独立授权，子记录关联设备或任务校验。 */
@Getter
@AllArgsConstructor
public enum FacilityAssetType implements EnumAssetType {
  facilityDevice(
    "设施Device",
    Arrays.asList(CrudAssetPermission.values()),
    List.of("facility-device")
  ),
  facilityGroup(
    "设施Group",
    Arrays.asList(CrudAssetPermission.values()),
    List.of("facility-group")
  ),
  facilityStandard(
    "设施Standard",
    Arrays.asList(CrudAssetPermission.values()),
    List.of("facility-standard")
  ),
  facilityPlan(
    "设施Plan",
    Arrays.asList(CrudAssetPermission.values()),
    List.of("facility-plan")
  ),
  facilityTask(
    "设施Task",
    Arrays.asList(CrudAssetPermission.values()),
    List.of("facility-task")
  ),
  facilityFault(
    "设施Fault",
    Arrays.asList(CrudAssetPermission.values()),
    List.of("facility-fault")
  ),
  facilityMapping(
    "设施Mapping",
    Arrays.asList(CrudAssetPermission.values()),
    List.of("facility-mapping")
  ),
  facilityRule(
    "设施Rule",
    Arrays.asList(CrudAssetPermission.values()),
    List.of("facility-rule")
  ),
  facilityTarget(
    "设施EnergyTarget",
    Arrays.asList(CrudAssetPermission.values()),
    List.of("facility-energy-target")
  );

  private final String name;
  private final List<AssetPermission> permissions;
  private final List<String> resourceId;

  public String getId() {
    return name();
  }
}
