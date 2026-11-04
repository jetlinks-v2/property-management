package org.jetlinks.pro.property.quality.asset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.property.quality.service.*;
import org.jetlinks.pro.property.quality.entity.*;
import reactor.core.publisher.Flux;
import java.util.*;
/** AssetsHolder 从真实业务实体取得租户、组织与应用维度，禁止仅凭客户端 ID 授权。 */
@Component @RequiredArgsConstructor
public class QualityAssetSupplier implements AssetSupplier {
 private final QualityConfigurationService configurations;
 private final QualityTaskService tasks;
 private final QualityWorkOrderService orders;
 public List<? extends AssetType> getTypes(){return List.of(QualityAssetType.values());}
 public Flux<? extends Asset> getAssets(AssetType type,Collection<?> ids){
  if(ids.isEmpty())return Flux.empty();
  if(type.isSameType("qualityConfiguration"))return configurations.createQuery().in("id",ids).fetch().map(r->new DefaultAsset(r.getId(),r.getName(),type).withEntity(r));
  if(type.isSameType("qualityTask"))return tasks.createQuery().in("id",ids).fetch().map(r->new DefaultAsset(r.getId(),r.getTaskName(),type).withEntity(r));
  if(type.isSameType("qualityWorkOrder"))return orders.createQuery().in("id",ids).fetch().map(r->new DefaultAsset(r.getId(),r.getTaskName(),type).withEntity(r));
  return Flux.empty();
 }
}
