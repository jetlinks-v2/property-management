package org.jetlinks.pro.property.facility.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.*;
import org.jetlinks.pro.property.finance.meter.MeterBillingModels;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;

/** Real JsonCodec and PostgreSQL transactions prove rule-version retention and all-or-nothing device batches. */
@SpringBootTest(classes=MeterConfigurationPersistenceTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
class MeterConfigurationPersistenceTest {
  static final Duration WAIT=Duration.ofSeconds(30);
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) { FacilityPersistenceTest.database(r); }
  @Autowired MeterChargeStore store;
  @SpyBean FacilityMeterCharges charges;
  @Autowired FacilityScope scope;
  @Autowired FacilityDirectory directory;
  @SpyBean FacilityRecords records;
  @Autowired DeviceStore devices;
  @Autowired FacilityDevices allocation;
  String project;
  @BeforeEach void setup() {
    project=UUID.randomUUID().toString();
    when(scope.current()).thenReturn(Mono.just(new FacilityContext("tenant","app","worker","测试员")));
    when(scope.assertRecord(any(),any(),anyString(),any())).thenReturn(Mono.empty());
    doReturn(Mono.empty()).when(records).assertProject(anyString(),any());
    var p=new PropertyOrganizationInfo(); p.setId(project); p.setName("测试项目"); when(directory.project(project)).thenReturn(Mono.just(p));
    doReturn(Mono.just(List.of(new MeterBillingModels.Standard("standard",0L,"subject","水费","水表用量","单价 × 用量",BigDecimal.ONE,
      "2024-01-01",null,List.of(new MeterBillingModels.Variable("VOLUME","用量",false)))))).when(charges).standards(project);
  }
  MeterChargeEntity input() {
    var row=new MeterChargeEntity(); row.setProjectId(project); row.setEnergyType("water"); row.setSubjectId("subject");
    row.setStandardId("standard"); row.setUsageVariable("VOLUME"); row.setFixedValues(Map.of()); row.setEffectiveFrom("2024-01-01"); return row;
  }
  @Test void disabledRulesRemainEditableAndHistorySurvivesActualDatabaseSerialization() {
    var saved=charges.save(input()).block(WAIT); saved.setEnabled(false);
    saved=charges.save(saved).block(WAIT); assertFalse(store.findById(saved.getId()).block(WAIT).getEnabled());
    saved.setEnabled(true); saved=charges.save(saved).block(WAIT);
    saved.setEffectiveFrom(LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString());
    saved=charges.save(saved).block(WAIT);
    var loaded=store.findById(saved.getId()).block(WAIT);
    assertEquals(1,loaded.getHistory().size());
    assertEquals("2024-01-01",loaded.getHistory().get(0).effectiveFrom());
    assertEquals("standard",loaded.getHistory().get(0).standardId());
    assertEquals(Map.of(),loaded.getHistory().get(0).fixedValues());
    assertEquals(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(),loaded.getHistory().get(0).effectiveTo());
    assertTrue(loaded.getHistory().get(0).covers(LocalDate.of(2024,2,1),LocalDate.of(2024,2,29)));
  }
  DeviceEntity device(String id) {
    var row=FacilityScope.initialize(new DeviceEntity(),new FacilityContext("tenant","app","worker","测试员"));
    row.setId(id); row.setCloudDeviceId(id); row.setProjectId(project); row.setOrganizationId(project); row.setCandidateProjectIds(List.of(project));
    row.setProjectConfirmed(true); row.setMeterType("device"); row.setSyncState("active"); row.setSourceState("online");
    devices.insert(row).block(WAIT); return row;
  }
  @Test void aStaleSecondDeviceRollsBackFirstDeviceAndAValidRetryCommitsBoth() {
    var one=device(UUID.randomUUID().toString()); var two=device(UUID.randomUUID().toString());
    var first=new FacilityDevices.Allocation(one.getId(),0L,project,"water",null,false);
    var stale=new FacilityDevices.Allocation(two.getId(),9L,project,"electricity",null,false);
    StepVerifier.create(allocation.allocate(List.of(first,stale))).expectError().verify(WAIT);
    assertEquals("device",devices.findById(one.getId()).block(WAIT).getMeterType());
    assertEquals(0L,devices.findById(one.getId()).block(WAIT).getVersion());
    var second=new FacilityDevices.Allocation(two.getId(),0L,project,"electricity",null,false);
    var result=allocation.allocate(List.of(first,second)).block(WAIT); assertEquals(2,result.size());
    assertEquals("water",devices.findById(one.getId()).block(WAIT).getMeterType());
    assertEquals("electricity",devices.findById(two.getId()).block(WAIT).getMeterType());
  }
  @Import({FacilityPersistenceTest.Config.class,MeterChargeStore.class,FacilityMeterCharges.class})
  static class Config {}
}
