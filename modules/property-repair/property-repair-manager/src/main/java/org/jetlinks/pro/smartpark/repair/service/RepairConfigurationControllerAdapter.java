package org.jetlinks.pro.smartpark.repair.service;
import lombok.RequiredArgsConstructor;import org.springframework.stereotype.Service;import org.jetlinks.pro.smartpark.repair.api.QualityRepairOption;import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;import org.jetlinks.pro.assets.AssetsHolder;import org.jetlinks.pro.smartpark.repair.asset.RepairAssetType;import org.jetlinks.pro.property.service.TenantAssetScope;import reactor.core.publisher.Flux;
/** 公开命令读取配置复用报修目录和 AssetsHolder 范围，不返回私有流程管理能力。 */
@Service @RequiredArgsConstructor public class RepairConfigurationControllerAdapter {
 private final RepairConfigurationService configs;private final RepairContextProvider contexts;private final RepairDirectory directory;private final TenantAssetScope tenants;
 public Flux<QualityRepairOption> options(String project){return contexts.current().flatMap(c->directory.project(project).then(AssetsHolder.injectQueryParam(QueryParamEntity.newQuery().where("applicationId",c.applicationId()).and("businessProjectId",project).and("enabled",true).getParam(),RepairAssetType.repairConfiguration,"id"))).flatMap(q->tenants.injectQuery(q,RepairAssetType.repairConfiguration,"id"))
  .flatMapMany(q->configs.createQuery().setParam(q).in("kind",java.util.List.of("category","channel","label","formOptions")).fetch()).map(r->new QualityRepairOption(r.getId(),r.getName(),r.getKind(),r.getParentId(),r.getConfiguration()));}
}
