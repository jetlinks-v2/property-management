package org.jetlinks.pro.smartpark.repair.asset;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.smartpark.repair.service.*;
import org.jetlinks.pro.smartpark.repair.entity.*;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Collection;
/** 暴露真实工单与配置的租户和应用信息，供平台 AssetsHolder 实施授权。 */
@Component @AllArgsConstructor
public class RepairAssetSupplier implements AssetSupplier {
 private final RepairOrderService orders;
 private final RepairConfigurationService configurations;
 @Override public List<? extends AssetType> getTypes(){return List.of(RepairAssetType.values());}
 @Override public Flux<? extends Asset> getAssets(AssetType type,Collection<?> ids){
  if(type.isSameType("repairOrder")) return orders.createQuery().in(RepairOrderEntity::getId,ids).fetch()
   .map(row->new DefaultAsset(row.getId(),row.getTitle(),RepairAssetType.repairOrder).withEntity(row));
  if(type.isSameType("repairConfiguration")) return configurations.createQuery().in(RepairConfigurationEntity::getId,ids).fetch()
   .map(row->new DefaultAsset(row.getId(),row.getName(),RepairAssetType.repairConfiguration).withEntity(row));
  return Flux.empty();
 }
}
