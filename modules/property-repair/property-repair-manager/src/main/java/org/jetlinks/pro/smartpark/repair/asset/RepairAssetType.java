package org.jetlinks.pro.smartpark.repair.asset;

import lombok.Getter;
import lombok.AllArgsConstructor;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import java.util.List;
import java.util.Arrays;
/** 工单与配置独立注册资产；工单通过组织和应用字段直接关联对应维度。 */
@Getter @AllArgsConstructor
public enum RepairAssetType implements EnumAssetType {
 repairOrder("报事工单",Arrays.asList(CrudAssetPermission.values()),List.of("repair-order")),
 repairConfiguration("报事配置",Arrays.asList(CrudAssetPermission.values()),List.of("repair-configuration"));
 private final String name;
 private final List<AssetPermission> permissions;
 private final List<String> resourceId;
 @Override public String getId(){return name();}
}
