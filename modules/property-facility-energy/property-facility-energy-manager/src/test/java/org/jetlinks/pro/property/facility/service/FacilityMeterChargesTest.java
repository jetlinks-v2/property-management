package org.jetlinks.pro.property.facility.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.command.QueryFacilityMeterUsageCommand;
import org.jetlinks.pro.property.facility.web.FacilityMeterUsageSupport;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.command.register.CommandServiceEndpointRegister;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.*;
import reactor.core.publisher.*;

class FacilityMeterChargesTest {
  final FacilityDevices devices=mock(FacilityDevices.class);
  final FacilityMappings mappings=mock(FacilityMappings.class);
  final FacilityReadings readings=mock(FacilityReadings.class);
  final FacilityMeterCharges service=spy(new FacilityMeterCharges(mock(MeterChargeStore.class),mock(FacilityScope.class),
    mock(FacilityRecords.class),mock(FacilityDirectory.class),devices,mappings,readings));
  final long from=LocalDate.of(2024,2,1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
  final long to=LocalDate.of(2024,3,1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
  DeviceEntity device; MappingEntity mapping; MeterChargeEntity rule;
  @BeforeEach void setup() {
    device=new DeviceEntity(); device.setId("meter"); device.setCloudDeviceId("cloud"); device.setName("水表");
    device.setProjectId("project"); device.setProjectConfirmed(true); device.setSpaceId("room"); device.setMeterType("water");
    device.setAllocationSince(from); device.setSyncState("active"); device.setEnergyEnabled(true);
    mapping=new MappingEntity(); mapping.setId("mapping"); mapping.setDeviceId("meter"); mapping.setEnabled(true);
    mapping.setEnergyType("water"); mapping.setMeterLevel("submeter"); mapping.setUsagePropertyId("volume"); mapping.setStaleAfterSeconds(32L*86400);
    rule=new MeterChargeEntity(); rule.setEnergyType("water"); rule.setEnabled(true); rule.setEffectiveFrom("2024-01-01");
    rule.setSubjectId("subject"); rule.setStandardId("standard"); rule.setUsageVariable("VOLUME"); rule.setFixedValues(Map.of());
    when(devices.read("meter")).thenReturn(Mono.just(device)); when(mappings.forDevice("meter")).thenReturn(Mono.just(mapping));
    doReturn(Mono.just(List.of(rule))).when(service).rules("project");
    when(readings.seriesFor(mapping,"volume",from,to)).thenReturn(Mono.just(List.of(reading(from,"100"),reading(to,"112.346"))));
    when(readings.anomalies("meter","volume",from,to)).thenReturn(Mono.just(0L));
  }
  ReadingEntity reading(long time,String value) {
    var r=new ReadingEntity(); r.setId("r"+time); r.setDeviceId("meter"); r.setPropertyId("volume"); r.setMetricKind("counter");
    r.setObservedTime(time); r.setCreateTime(time); r.setValue(new BigDecimal(value)); r.setStatus("accepted"); r.setMappingVersion(0L); r.setMeterEpoch(0L); return r;
  }
  @Test void fullMonthReadsRealCounterDifferenceAndProducesStableEvidence() {
    var evidence=service.evidence("meter","2024-02").block();
    assertEquals(new BigDecimal("12.346"),evidence.quantity()); assertEquals("room",evidence.spaceId());
    assertEquals(evidence.sourceFingerprint(),service.evidence("meter","2024-02").block().sourceFingerprint());
    rule.setVersion(rule.getVersion()+1);
    assertNotEquals(evidence.sourceFingerprint(),service.evidence("meter","2024-02").block().sourceFingerprint());
  }
  @Test void missingReadingsRollbackAndRejectedReadingsNeverBecomeZeroBills() {
    when(readings.seriesFor(mapping,"volume",from,to)).thenReturn(Mono.just(List.of(reading(from,"100"))));
    assertThrows(RuntimeException.class,()->service.evidence("meter","2024-02").block());
    when(readings.seriesFor(mapping,"volume",from,to)).thenReturn(Mono.just(List.of(reading(from,"100"),reading(to,"99"))));
    assertThrows(RuntimeException.class,()->service.evidence("meter","2024-02").block());
    when(readings.seriesFor(mapping,"volume",from,to)).thenReturn(Mono.just(List.of(reading(from,"100"),reading(to,"100"))));
    assertEquals(0,service.evidence("meter","2024-02").block().quantity().signum());
    when(readings.anomalies("meter","volume",from,to)).thenReturn(Mono.just(1L));
    assertThrows(RuntimeException.class,()->service.evidence("meter","2024-02").block());
  }
  @Test void midMonthAllocationRuleOrMasterMeterCannotBillAHousehold() {
    device.setAllocationSince(from+1); assertThrows(RuntimeException.class,()->service.evidence("meter","2024-02").block());
    device.setAllocationSince(from); rule.setEffectiveFrom("2024-02-02");
    assertThrows(RuntimeException.class,()->service.evidence("meter","2024-02").block());
    rule.setEffectiveFrom("2024-01-01"); mapping.setMeterLevel("master");
    assertThrows(RuntimeException.class,()->service.evidence("meter","2024-02").block());
  }
  @Test void publishedCommandRegistersAndRetainsParametersInTheTransportContainer() {
    try(var context=new AnnotationConfigApplicationContext()) {
      context.registerBean(FacilityMeterCharges.class,()->service);
      context.register(CommandServiceEndpointRegister.class,FacilityMeterUsageSupport.class); context.refresh();
      var c=new QueryFacilityMeterUsageCommand(); c.setDeviceId("meter"); c.setMonth("2024-02");
      assertEquals("meter",c.getOrNull("deviceId",String.class)); assertEquals("2024-02",c.getOrNull("month",String.class));
      var result=CommandSupportManagerProviders.getCommandSupport(QueryFacilityMeterUsageCommand.SERVICE_ID)
        .flatMap(s->s.execute(c)).block();
      assertEquals(new BigDecimal("12.346"),result.quantity());
    }
  }
  @Test void ruleActivationDoesNotRewriteDatesAndFutureVersionsKeepPreviousMonthBillable() {
    var changed=new MeterChargeEntity(); changed.setSubjectId(rule.getSubjectId()); changed.setStandardId(rule.getStandardId());
    changed.setUsageVariable(rule.getUsageVariable()); changed.setFixedValues(rule.getFixedValues()); changed.setEffectiveFrom(rule.getEffectiveFrom());
    changed.setEnabled(false); assertFalse(FacilityMeterCharges.ruleChanged(rule,changed));
    changed.setStandardId("next"); assertTrue(FacilityMeterCharges.ruleChanged(rule,changed));
    var history=org.jetlinks.pro.property.facility.model.MeterChargeVersion.of(rule,"2024-02-29");
    rule.setHistory(List.of(history)); rule.setStandardId("future"); rule.setEffectiveFrom("2024-03-01");
    assertEquals("standard",service.evidence("meter","2024-02").block().standardId());
    rule.setHistory(List.of()); assertThrows(RuntimeException.class,()->service.evidence("meter","2024-02").block());
  }
  @Test void associationUsesFinanceUsageAndDatesWithoutFacilityFormulaInputs() {
    var input=new FacilityMeterCharges.Association(null,null,"project","water","subject","standard");
    var standard=new org.jetlinks.pro.property.finance.meter.MeterBillingModels.Standard("standard",0L,"subject","水费","水费标准",
      "单价 × 用量",BigDecimal.ONE,"2024-01-01","2099-12-31",List.of(
        new org.jetlinks.pro.property.finance.meter.MeterBillingModels.Variable("STANDARD_PRICE","单价",true),
        new org.jetlinks.pro.property.finance.meter.MeterBillingModels.Variable("VOLUME","用量",false)));
    var associated=FacilityMeterCharges.associationRule(input,standard,null);
    assertEquals("VOLUME",associated.getUsageVariable()); assertEquals(Map.of(),associated.getFixedValues());
    assertEquals("2024-01-01",associated.getEffectiveFrom()); assertNull(associated.getEffectiveTo());
    rule.setEffectiveFrom("2024-02-01"); rule.setEnabled(false);
    associated=FacilityMeterCharges.associationRule(input,standard,rule);
    assertEquals("2024-02-01",associated.getEffectiveFrom()); assertTrue(associated.getEnabled());
  }
  @Test void associationRejectsStandardsWithoutAnUnambiguousUsageVariable() {
    var input=new FacilityMeterCharges.Association(null,null,"project","water","subject","standard");
    var price=new org.jetlinks.pro.property.finance.meter.MeterBillingModels.Variable("STANDARD_PRICE","单价",true);
    var usage=new org.jetlinks.pro.property.finance.meter.MeterBillingModels.Variable("VOLUME","用量",false);
    var extra=new org.jetlinks.pro.property.finance.meter.MeterBillingModels.Variable("EXTRA","系数",false);
    for(var variables:List.of(List.of(price),List.of(price,usage,extra))) {
      var standard=new org.jetlinks.pro.property.finance.meter.MeterBillingModels.Standard("standard",0L,"subject","水费","标准",
        "公式",BigDecimal.ONE,null,null,variables);
      assertThrows(RuntimeException.class,()->FacilityMeterCharges.associationRule(input,standard,null));
    }
  }
}
