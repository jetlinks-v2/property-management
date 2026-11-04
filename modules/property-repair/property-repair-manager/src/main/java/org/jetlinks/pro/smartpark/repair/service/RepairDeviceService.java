package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.DimensionInfo;
import org.jetlinks.pro.assets.impl.terms.DimensionAssetsTerm;
import org.jetlinks.pro.assets.impl.terms.DimensionAssetsTermBuilder;
import org.jetlinks.pro.command.CommandServices;
import org.jetlinks.pro.property.service.PropertyExtensionService;
import org.jetlinks.pro.property.service.TenantAssetScope;
import org.jetlinks.pro.smartpark.repair.api.RepairDeviceOption;
import org.jetlinks.pro.smartpark.repair.api.CreateRepairRequest;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.jetlinks.sdk.server.SdkServices;
import org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand;
import org.jetlinks.sdk.server.device.DeviceCommandSupportTypes;
import org.jetlinks.sdk.server.device.DeviceInfo;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.List;

/**
 * 只读调用 Cloud deviceService/device 的公开分页命令，不依赖物联网服务内部实现。
 * 查询取调用人设备权限、当前租户和业务项目组织及下级组织分配范围的交集。
 * 平台命令失败按原错误传播；不回退到全部设备。仅返回无敏感配置的投影。
 */
@Service @AllArgsConstructor
public class RepairDeviceService {
 private static final AssetType DEVICE=AssetType.of("device");
 private final RepairContextProvider contexts;
 private final PropertyExtensionService projects;
 private final TenantAssetScope tenantScope;

 public Mono<PagerResult<RepairDeviceOption>> query(String businessProjectId,QueryParamEntity query){
  return contexts.current().flatMap(context->projects.businessProject(businessProjectId)
   .then(scope(businessProjectId,query))
   .flatMap(this::execute)
   .map(page->{
    PagerResult<RepairDeviceOption> result=PagerResult.of(page.getTotal(),page.getData().stream().map(device->
     new RepairDeviceOption(device.getId(),device.getName(),device.getProductId(),device.getProductName())).toList());
    result.setPageIndex(page.getPageIndex());result.setPageSize(page.getPageSize());return result;
   }));
 }

 private Mono<QueryParamEntity> scope(String businessProjectId,QueryParamEntity query){
  DimensionAssetsTerm project=projectTerm(businessProjectId);
  // 必须追加到新的AND层，客户端OR和超管的全部数据权限均不能移除业务项目范围。
  QueryParamEntity scoped=query.clone().toNestQuery().accept("id",DimensionAssetsTermBuilder.termType,project).getParam();
  scoped.setPaging(true);scoped.setPageSize(Math.min(100,Math.max(1,scoped.getPageSize())));
  scoped.setPageIndex(Math.max(0,scoped.getPageIndex()));
  return project.applyContext().then(AssetsHolder.injectQueryParam(scoped,DEVICE,"id"))
   .flatMap(authorized->tenantScope.injectQuery(authorized,DEVICE,"id"));
 }

 static DimensionAssetsTerm projectTerm(String businessProjectId){
  DimensionAssetsTerm term=DimensionAssetsTerm.of(List.of(DimensionInfo.of("org",businessProjectId)),"device");
  // 使用平台实时维度闭包，组织调动后不依赖本地共享目录中的旧父子快照。
  term.setClosure(true);return term;
 }

 private Mono<PagerResult<DeviceInfo>> execute(QueryParamEntity scoped){
  return CommandServices.getReactive(SdkServices.deviceService,DeviceCommandSupportTypes.device)
   .flatMap(support->{
    QueryPagerCommand<DeviceInfo> command=QueryPagerCommand.of(DeviceInfo.class);
    command.withQueryParam(scoped);return support.execute(command);
   });
 }

 /** 创建事务落库前重新查询设备；客户端提供的名称/产品字段均不可信。 */
 public Mono<Void> populate(RepairOrderEntity order,CreateRepairRequest input,RepairConfigurationEntity category){
  if(input.getDeviceId()==null||input.getDeviceId().isBlank())return Mono.empty();
  RepairFlowRules.require(category.getConfiguration()!=null&&Boolean.TRUE.equals(category.getConfiguration().get("deviceEnabled")),"device_category_disabled");
  QueryParamEntity query=QueryParamEntity.newQuery().where("id",input.getDeviceId()).doPaging(0,1).getParam();
  return query(input.getBusinessProjectId(),query).flatMap(page->Mono.justOrEmpty(page.getData().stream()
    .filter(device->input.getDeviceId().equals(device.id())).findFirst()))
   .switchIfEmpty(Mono.error(new BusinessException("error.repair.device_not_available",400)))
   .doOnNext(device->{order.setDeviceId(device.id());order.setDeviceName(device.name());order.setDeviceProductId(device.productId());order.setDeviceProductName(device.productName());}).then();
 }
}
