package org.jetlinks.pro.property.facility.asset;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.property.facility.service.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** 由真实持久化记录解析组织、租户及应用维度，不信任客户端声明的归属。 */
@Component
@RequiredArgsConstructor
public class FacilityAssetSupplier implements AssetSupplier {

  private final DeviceStore device;
  private final GroupStore group;
  private final StandardStore standard;
  private final PlanStore plan;
  private final TaskStore task;
  private final FaultStore fault;
  private final MappingStore mapping;
  private final RuleStore rule;
  private final EnergyTargetStore energyTarget;

  public List<? extends AssetType> getTypes() {
    return List.of(FacilityAssetType.values());
  }

  public Flux<? extends Asset> getAssets(AssetType type, Collection<?> ids) {
    if (ids.isEmpty()) return Flux.empty();
    if (type.isSameType("facilityDevice")) return device
      .createQuery()
      .in("id", ids)
      .fetch()
      .map(r -> new DefaultAsset(r.getId(), r.getName(), type).withEntity(r));
    if (type.isSameType("facilityGroup")) return group
      .createQuery()
      .in("id", ids)
      .fetch()
      .map(r -> new DefaultAsset(r.getId(), r.getName(), type).withEntity(r));
    if (type.isSameType("facilityStandard")) return standard
      .createQuery()
      .in("id", ids)
      .fetch()
      .map(r -> new DefaultAsset(r.getId(), r.getName(), type).withEntity(r));
    if (type.isSameType("facilityPlan")) return plan
      .createQuery()
      .in("id", ids)
      .fetch()
      .map(r -> new DefaultAsset(r.getId(), r.getName(), type).withEntity(r));
    if (type.isSameType("facilityTask")) return task
      .createQuery()
      .in("id", ids)
      .fetch()
      .map(r -> new DefaultAsset(r.getId(), r.getName(), type).withEntity(r));
    if (type.isSameType("facilityFault")) return fault
      .createQuery()
      .in("id", ids)
      .fetch()
      .map(r -> new DefaultAsset(r.getId(), r.getName(), type).withEntity(r));
    if (type.isSameType("facilityMapping")) return mapping
      .createQuery()
      .in("id", ids)
      .fetch()
      .map(r -> new DefaultAsset(r.getId(), r.getName(), type).withEntity(r));
    if (type.isSameType("facilityRule")) return rule
      .createQuery()
      .in("id", ids)
      .fetch()
      .map(r -> new DefaultAsset(r.getId(), r.getName(), type).withEntity(r));
    if (type.isSameType("facilityTarget")) return energyTarget
      .createQuery()
      .in("id", ids)
      .fetch()
      .map(r -> new DefaultAsset(r.getId(), r.getName(), type).withEntity(r));
    return Flux.empty();
  }
}
