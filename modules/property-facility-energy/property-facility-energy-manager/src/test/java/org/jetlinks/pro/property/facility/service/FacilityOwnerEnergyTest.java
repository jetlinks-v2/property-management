package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.command.QueryOwnerFacilityEnergyCommand;
import org.jetlinks.pro.property.facility.web.FacilityOwnerEnergySupport;
import org.jetlinks.pro.command.register.CommandServiceEndpointRegister;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class FacilityOwnerEnergyTest {
  final ZoneId zone=ZoneId.of("Asia/Shanghai");
  final YearMonth month=YearMonth.of(2026,10);
  final long from=month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
  final long to=month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();

  ReadingEntity reading(long time,String value,long epoch) {
    var row=new ReadingEntity(); row.setId("r"+time); row.setDeviceId("meter"); row.setPropertyId("total");
    row.setObservedTime(time); row.setCreateTime(time); row.setValue(new BigDecimal(value)); row.setMetricKind("counter");
    row.setStatus("accepted"); row.setMeterEpoch(epoch); row.setMappingVersion(0L); return row;
  }
  org.jetlinks.pro.property.facility.model.OwnerEnergyMeter view(List<ReadingEntity> rows) {
    var device=new DeviceEntity(); device.setId("meter"); device.setName("水表");
    var mapping=new MappingEntity(); mapping.setEnergyType("water"); mapping.setStaleAfterSeconds(32L*86400);
    return FacilityOwnerEnergy.view(device,mapping,month,from,to,rows,from);
  }
  @Test void missingBaselineIsUnknownAndCompleteZeroRemainsRealZero() {
    assertNull(view(List.of()).usage());
    assertNull(view(List.of(reading(from,"100",0))).usage());
    var zero=view(List.of(reading(from,"100",0),reading(to,"100",0)));
    assertEquals("0",zero.usage()); assertTrue(zero.complete());
    var result=view(List.of(reading(from,"100",0),reading(to,"112.346",0)));
    assertEquals("12.346",result.usage()); assertTrue(result.complete());
    assertEquals(31,result.days().size());
    var increment=reading(from+3600000,"0",0);increment.setMetricKind("increment");
    assertEquals("0",view(List.of(increment)).usage());
    assertEquals("0",view(List.of(increment)).days().get(0).usage());
  }
  @Test void resetAndRejectedReadingCannotBecomeUsageAndGapsStayPartial() {
    var rejected=reading(from+1000,"99999",0);rejected.setStatus("rejected");
    var result=view(List.of(reading(from,"100",0),rejected,reading(to,"99",0)));
    assertFalse(result.complete());assertEquals("0",result.usage());
    assertFalse(view(List.of(reading(from,"100",0),reading(to,"10",1))).complete());
    var partial=view(List.of(reading(from+3600000,"100",0),reading(to,"112.34",0)));
    assertFalse(partial.complete());assertEquals("12.34",partial.usage());
  }
  @Test void realCommandRegistrationRetainsProjectSpaceMonthAndDoesNotAddEmployeeMenuPermission() {
    var service=mock(FacilityOwnerEnergy.class);
    when(service.query("project","room","2026-10")).thenReturn(Mono.just(List.of(view(List.of()))));
    try(var context=new AnnotationConfigApplicationContext()) {
      context.registerBean(FacilityOwnerEnergy.class,()->service);
      context.register(CommandServiceEndpointRegister.class,FacilityOwnerEnergySupport.class);context.refresh();
      var command=new QueryOwnerFacilityEnergyCommand();command.setProjectId("project");command.setSpaceId("room");command.setMonth("2026-10");
      assertEquals("room",command.getOrNull("spaceId",String.class));
      var result=CommandSupportManagerProviders.getCommandSupport(QueryOwnerFacilityEnergyCommand.SERVICE_ID).flatMap(s->s.execute(command)).block();
      assertEquals(1,result.size());assertNull(result.get(0).usage());
      verify(service).query("project","room","2026-10");
    }
  }
}
