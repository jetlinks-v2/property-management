package org.jetlinks.pro.property.quality.asset;
import lombok.Getter;
import lombok.AllArgsConstructor;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import java.util.*;
/** 配置、任务和工单独立授权；检查项、草稿、整改与导出继承其所属工单的范围。 */
@Getter @AllArgsConstructor
public enum QualityAssetType implements EnumAssetType {
 qualityConfiguration("品质配置",Arrays.asList(CrudAssetPermission.values()),List.of("quality-configuration")),
 qualityTask("品质任务",Arrays.asList(CrudAssetPermission.values()),List.of("quality-task")),
 qualityWorkOrder("品质工单",Arrays.asList(CrudAssetPermission.values()),List.of("quality-work-order"));
 private final String name; private final List<AssetPermission> permissions; private final List<String> resourceId;
 public String getId(){return name();}
}
