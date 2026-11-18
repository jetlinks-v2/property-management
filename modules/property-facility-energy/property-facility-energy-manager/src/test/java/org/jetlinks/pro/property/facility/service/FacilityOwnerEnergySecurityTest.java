package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.simple.*;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.impl.DynamicCustomDimensionAssetsHolder;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.base.command.QueryCurrentPropertyHousesCommand;
import org.jetlinks.pro.property.facility.entity.DeviceEntity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** 真实AssetsHolder授权对：设备库查询必须在当前应用项目权限及本人房屋交集之后。 */
class FacilityOwnerEnergySecurityTest {
  final FacilityScope scope=mock(FacilityScope.class);
  final DeviceStore devices=mock(DeviceStore.class);
  final FacilityOwnerEnergy service=new FacilityOwnerEnergy(scope,devices,mock(MappingStore.class),mock(FacilityReadings.class));
  final CommandSupport support=mock(CommandSupport.class);
  Context access(boolean readable) {
    var bindings=mock(AssetBindManager.class);
    when(bindings.getBindings(anyString(),anyCollection(),any(AssetType.class),anyCollection())).thenAnswer(call -> readable
      ? Flux.just(new SimpleAssetBinding(SimpleDimensionType.of("test"),"owner-role",AssetType.of("organization"),"project-a",CrudAssetPermission.read.getMask(),null))
      : Flux.empty());
    return Context.of(AssetsHolder.class,new DynamicCustomDimensionAssetsHolder(mock(AssetManager.class),bindings,"organization",
      type -> Flux.just(SimpleDimension.of("owner-role","Owner",SimpleDimensionType.of("test"),Map.of()))));
  }
  PropertyOwnerHouse house(String space) {
    return new PropertyOwnerHouse("项目",new PropertyHouseContext(new PropertyHouseInfo(space,"project-a",space,"项目/"+space,
      null,null,null,null,null,null,null,0L),List.of(new PropertyHouseCustomer("link","customer","owner",null,"业主",null,"owner",1L,0L,0L))),List.of());
  }
  @Test void commandUsesExactOwnerRoomAndTenantAndNeverQueriesForeignRoom() {
    when(scope.current()).thenReturn(Mono.just(new FacilityContext("tenant-a","charge-app","owner","业主")));
    when(support.execute(any(QueryCurrentPropertyHousesCommand.class))).thenAnswer(call -> {
      QueryCurrentPropertyHousesCommand c=call.getArgument(0);assertEquals("project-a",c.getProjectId());assertEquals("room-a",c.getSpaceId());
      return Mono.just(List.of(house("room-a")));
    });
    @SuppressWarnings("unchecked") ReactiveQuery<DeviceEntity> query=mock(ReactiveQuery.class,RETURNS_SELF);
    when(devices.createQuery()).thenReturn(query);when(query.fetch()).thenReturn(Flux.empty());
    try(var commands=mockStatic(CommandSupportManagerProviders.class)) {
      commands.when(()->CommandSupportManagerProviders.getCommandSupport(QueryCurrentPropertyHousesCommand.SERVICE_ID)).thenReturn(Mono.just(support));
      String month=YearMonth.now(ZoneId.of("Asia/Shanghai")).toString();
      StepVerifier.create(service.query("project-a","room-a",month).contextWrite(access(true))).expectNext(List.of()).verifyComplete();
      verify(query).where("tenantId","tenant-a");verify(query).and("projectId","project-a");verify(query).and("spaceId","room-a");
      verify(query).and("syncState","active");verify(query).and("energyEnabled",true);
      clearInvocations(devices,support);
      StepVerifier.create(service.query("project-a","room-a",month).contextWrite(access(false))).expectError(AccessDenyException.class).verify();
      verifyNoInteractions(devices,support);
      when(support.execute(any(QueryCurrentPropertyHousesCommand.class))).thenReturn(Mono.just(List.of(house("different-room"))));
      StepVerifier.create(service.query("project-a","room-a",month).contextWrite(access(true))).expectError(AccessDenyException.class).verify();
      verifyNoInteractions(devices);
    }
  }
}
