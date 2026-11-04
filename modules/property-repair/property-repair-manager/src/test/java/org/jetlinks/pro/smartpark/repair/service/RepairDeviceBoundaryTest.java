package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.command.CommandServices;
import org.jetlinks.pro.property.entity.PropertyOrganizationEntity;
import org.jetlinks.pro.property.service.PropertyExtensionService;
import org.jetlinks.pro.property.service.TenantAssetScope;
import org.jetlinks.pro.smartpark.repair.api.CreateRepairRequest;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand;
import org.jetlinks.sdk.server.device.DeviceInfo;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepairDeviceBoundaryTest {
 @Test void routePayloadPermissionsAndProjectionFollowCloudContract(){
  var contexts=mock(RepairContextProvider.class);var projects=mock(PropertyExtensionService.class);var tenant=mock(TenantAssetScope.class);
  when(contexts.current()).thenReturn(Mono.just(new RepairContext("tenant","application","employee","员工")));
  when(projects.businessProject("project-a")).thenReturn(Mono.just(new PropertyOrganizationEntity()));
  when(tenant.injectQuery(any(),argThat((AssetType type)->type!=null&&"device".equals(type.getId())),eq("id"))).thenAnswer(call->Mono.just(call.getArgument(0)));
  var support=mock(CommandSupport.class);var captured=new AtomicReference<QueryPagerCommand<?>>();
  DeviceInfo device=new DeviceInfo();device.setId("sensor-a");device.setName("水浸传感器");device.setConfiguration(Map.of("password","must-not-be-returned"));
  doAnswer(call->{captured.set(call.getArgument(0));return Mono.just(PagerResult.of(1,List.of(device)));}).when(support).execute(any(QueryPagerCommand.class));
  try(var commands=mockStatic(CommandServices.class);var assets=mockStatic(AssetsHolder.class)){
   commands.when(()->CommandServices.getReactive("deviceService","device")).thenReturn(Mono.just(support));
   assets.when(()->AssetsHolder.injectQueryParam(any(QueryParamEntity.class),argThat((AssetType type)->type!=null&&"device".equals(type.getId())),eq("id"))).thenAnswer(call->Mono.just(call.getArgument(0)));
   var service=new RepairDeviceService(contexts,projects,tenant);
   QueryParamEntity query=QueryParamEntity.newQuery().where("id","other-device").or("name","fake").doPaging(0,2000).getParam();
   StepVerifier.create(service.query("project-a",query)).assertNext(page->{assertEquals("sensor-a",page.getData().get(0).id());assertEquals(1,page.getTotal());}).verifyComplete();
   assertNotNull(captured.get());assertEquals("QueryPager",captured.get().getCommandId());
   QueryParamEntity sent=captured.get().asQueryParam();assertEquals(100,sent.getPageSize());assertEquals(2,sent.getTerms().size());
   assertEquals(2,sent.getTerms().get(0).getTerms().size());
   var project=RepairDeviceService.projectTerm("project-a");
   assertEquals(Boolean.TRUE,project.getClosure());assertEquals("org",project.getTargets().iterator().next().getType());assertEquals("project-a",project.getTargets().iterator().next().getId());
   verify(tenant).injectQuery(any(),argThat((AssetType type)->type!=null&&"device".equals(type.getId())),eq("id"));
  }
 }
 @Test void remoteFailurePropagatesWithoutUnscopedFallback(){
  var contexts=mock(RepairContextProvider.class);var projects=mock(PropertyExtensionService.class);var tenant=mock(TenantAssetScope.class);
  when(contexts.current()).thenReturn(Mono.just(new RepairContext("tenant","app","user","员工")));
  when(projects.businessProject("a")).thenReturn(Mono.just(new PropertyOrganizationEntity()));
  when(tenant.injectQuery(any(),any(),anyString())).thenAnswer(call->Mono.just(call.getArgument(0)));
  try(var commands=mockStatic(CommandServices.class);var assets=mockStatic(AssetsHolder.class)){
   commands.when(()->CommandServices.getReactive("deviceService","device")).thenReturn(Mono.error(new IllegalStateException("device service unavailable")));
   assets.when(()->AssetsHolder.injectQueryParam(any(QueryParamEntity.class),any(AssetType.class),eq("id"))).thenAnswer(call->Mono.just(call.getArgument(0)));
   StepVerifier.create(new RepairDeviceService(contexts,projects,tenant).query("a",new QueryParamEntity())).expectErrorMessage("device service unavailable").verify();
   commands.verify(()->CommandServices.getReactive("deviceService","device"),times(1));
  }
 }
 @Test void absentOrDisallowedDeviceCannotCreateUnverifiedSnapshot(){
  var service=new RepairDeviceService(null,null,null);
  var order=new RepairOrderEntity();var input=new CreateRepairRequest();var category=new RepairConfigurationEntity();
  StepVerifier.create(service.populate(order,input,category)).verifyComplete();
  input.setDeviceId("forged-device");
  assertThrows(BusinessException.class,()->service.populate(order,input,category));
  assertNull(order.getDeviceId());assertNull(order.getDeviceName());
 }
}
