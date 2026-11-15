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
  @Autowired FacilityMappings mappingManager;
  @Autowired FacilityCloud cloud;
  @Autowired FacilityAllocations catalog;
  @Autowired DeviceAllocationStore catalogStore;
  @Autowired FacilitySync synchronizer;
  @Autowired FacilitySyncDevice syncDevice;
  @Autowired FaultStore faults;
  @Autowired MappingStore mappings;
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
  @Test void selectingOnlyFinanceAssociationsSavesAndSameDayCorrectionsDoNotCreateInvalidHistory() {
    var first=new FacilityMeterCharges.Association(null,null,project,"water","subject","standard");
    var saved=charges.associate(first).block(WAIT);
    assertEquals("VOLUME",saved.getUsageVariable()); assertEquals(Map.of(),saved.getFixedValues());
    assertEquals("2024-01-01",saved.getEffectiveFrom());
    var current=store.findById(saved.getId()).block(WAIT);
    doReturn(Mono.just(List.of(new MeterBillingModels.Standard("next",0L,"subject","水费","新标准","单价 × 用量",BigDecimal.TEN,
      null,null,List.of(new MeterBillingModels.Variable("USAGE","用量",false))),
      new MeterBillingModels.Standard("final",0L,"subject","水费","最终标准","单价 × 用量",BigDecimal.ONE,
      null,null,List.of(new MeterBillingModels.Variable("USAGE","用量",false)))))).when(charges).standards(project);
    saved=charges.associate(new FacilityMeterCharges.Association(current.getId(),current.getVersion(),project,"water","subject","next")).block(WAIT);
    assertEquals(1,saved.getHistory().size()); assertEquals("standard",saved.getHistory().get(0).standardId());
    var finalRule=charges.associate(new FacilityMeterCharges.Association(saved.getId(),saved.getVersion(),project,"water","subject","final")).block(WAIT);
    var loaded=store.findById(finalRule.getId()).block(WAIT);
    assertEquals("final",loaded.getStandardId()); assertEquals(1,loaded.getHistory().size());
    assertEquals(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(),loaded.getEffectiveFrom());
    var stale=new FacilityMeterCharges.Association(saved.getId(),saved.getVersion(),project,"water","subject","next");
    StepVerifier.create(charges.associate(stale)).expectError().verify(WAIT);
    assertEquals("final",store.findById(loaded.getId()).block(WAIT).getStandardId());
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
  @Test void projectOnlyAllocationRetainsEachMetersTypeSpaceAndEffectiveTime() {
    var one=device(UUID.randomUUID().toString()); var two=device(UUID.randomUUID().toString());
    for (var row : List.of(one,two)) {
      row.setMeterType(row==one ? "water" : "gas"); row.setSpaceId("room"); row.setAllocationSince(100L);
      devices.save(row).block(WAIT);
    }
    when(directory.space(project,"room")).thenReturn(Mono.just(new org.jetlinks.pro.property.base.space.PropertySpaceInfo()));
    var result=allocation.allocate(List.of(
      new FacilityDevices.Allocation(one.getId(),0L,project,null,null,false),
      new FacilityDevices.Allocation(two.getId(),0L,project,null,null,false))).block(WAIT);
    assertEquals(List.of("water","gas"),result.stream().map(DeviceEntity::getMeterType).toList());
    for (var row : result) { assertEquals("room",row.getSpaceId()); assertEquals(100L,row.getAllocationSince()); }
  }
  @Test void energyConfigurationOwnsMeterTypeAndStaleUpdatesCannotChangeIt() {
    var device=device(UUID.randomUUID().toString());
    var metadata=mock(org.jetlinks.core.things.ThingMetadata.class);
    var property=mock(org.jetlinks.core.metadata.PropertyMetadata.class);
    when(property.getId()).thenReturn("total"); when(metadata.getProperties()).thenReturn(List.of(property));
    when(cloud.metadata(device.getCloudDeviceId())).thenReturn(Mono.just(metadata));
    var metric=new MetricMapping(); metric.setPropertyId("total"); metric.setKind("counter"); metric.setUnit("m³");
    metric.setMultiplier(BigDecimal.ONE); metric.setScale(2);
    var input=new MappingEntity(); input.setDeviceId(device.getId()); input.setEnergyType("water");
    input.setMeterLevel("submeter"); input.setStaleAfterSeconds(3600L); input.setMetrics(List.of(metric)); input.setEnabled(true);
    var saved=mappingManager.save(input).block(WAIT);
    var water=devices.findById(device.getId()).block(WAIT);
    assertEquals("water",water.getMeterType()); assertNotNull(water.getAllocationSince());
    input.setVersion(saved.getVersion()); input.setEnergyType("gas"); saved=mappingManager.save(input).block(WAIT);
    assertEquals("gas",devices.findById(device.getId()).block(WAIT).getMeterType());
    input.setVersion(saved.getVersion()-1); input.setEnergyType("electricity");
    StepVerifier.create(mappingManager.save(input)).expectError().verify(WAIT);
    assertEquals("gas",devices.findById(device.getId()).block(WAIT).getMeterType());
  }
  @Test void sourceCatalogDoesNotCreateAssetsAndConfiguredSyncRoutesTwoProjectsAndPreservesBillingConfiguration() {
    var other = sourceProject(UUID.randomUUID().toString());
    var sourceOne = sourceDevice(UUID.randomUUID().toString());
    var sourceTwo = sourceDevice(UUID.randomUUID().toString());
    var current = sourceProject(project);
    when(directory.projects()).thenReturn(Flux.just(current, other));
    when(cloud.snapshot(null)).thenReturn(Mono.just(List.of(sourceOne, sourceTwo)));
    assertEquals(2, catalog.refresh().block(WAIT));
    String first = FacilityRecords.stableId("tenant", "app", sourceOne.getId());
    String second = FacilityRecords.stableId("tenant", "app", sourceTwo.getId());
    assertNull(devices.findById(first).block(WAIT)); assertNull(devices.findById(second).block(WAIT));
    assertNull(catalogStore.findById(first).block(WAIT).getProjectId());
    // One stale row rolls back the first allocation in the same transaction.
    StepVerifier.create(catalog.save(List.of(new FacilityDevices.Allocation(first,0L,project,null,null,false),
      new FacilityDevices.Allocation(second,9L,other.getId(),null,null,false)))).expectError().verify(WAIT);
    assertNull(catalogStore.findById(first).block(WAIT).getProjectId());
    catalog.save(List.of(new FacilityDevices.Allocation(first,0L,project,null,null,false),
      new FacilityDevices.Allocation(second,0L,other.getId(),null,null,false))).block(WAIT);
    assertNull(devices.findById(first).block(WAIT));
    var summary = synchronizer.synchronizeConfigured().block(WAIT);
    assertEquals(2, summary.inserted()); assertEquals(2, summary.projects()); assertEquals(0, summary.failed());
    assertEquals(other.getId(), devices.findById(second).block(WAIT).getProjectId());
    var asset = devices.findById(first).block(WAIT);
    asset.setName("本地水表名称"); asset.setSpaceId("room"); asset.setMeterType("water");
    asset.setAllocationSince(123L); asset.setEnergyEnabled(true); devices.save(asset).block(WAIT);
    var mapping = FacilityScope.initialize(new MappingEntity(),new FacilityContext("tenant","app","worker","测试员"));
    mapping.setDeviceId(first); mapping.setEnabled(true); mapping.setProjectId(project); mapping.setId(UUID.randomUUID().toString());
    mappings.insert(mapping).block(WAIT);
    var again = synchronizer.synchronizeConfigured().block(WAIT);
    assertEquals(0,again.inserted()); assertEquals(2,again.updated()); assertEquals(0,again.failed());
    var retained = devices.findById(first).block(WAIT);
    assertEquals("本地水表名称",retained.getName()); assertEquals("room",retained.getSpaceId());
    assertEquals("water",retained.getMeterType()); assertTrue(retained.getEnergyEnabled()); assertEquals(123L,retained.getAllocationSince());
    assertTrue(mappings.findById(mapping.getId()).block(WAIT).getEnabled());
    var stale = catalogStore.findById(first).block(WAIT);
    catalog.save(List.of(new FacilityDevices.Allocation(first,stale.getVersion(),project,null,null,false))).block(WAIT);
    StepVerifier.create(syncDevice.apply(stale,sourceOne,List.of(project),System.currentTimeMillis())).expectError().verify(WAIT);
    assertEquals("room",devices.findById(first).block(WAIT).getSpaceId());
  }
  @Test void activeFaultBlocksProjectMoveButCancelledFaultAllowsItAndHistoryKeepsOriginalProject() {
    var source = sourceDevice(UUID.randomUUID().toString());
    var next = sourceProject(UUID.randomUUID().toString());
    var current = sourceProject(project);
    when(directory.projects()).thenReturn(Flux.just(current,next));
    when(cloud.snapshot(null)).thenReturn(Mono.just(List.of(source)));
    catalog.refresh().block(WAIT);
    String id = FacilityRecords.stableId("tenant","app",source.getId());
    catalog.save(List.of(new FacilityDevices.Allocation(id,0L,project,null,null,false))).block(WAIT);
    synchronizer.synchronizeConfigured().block(WAIT);
    var fault = FacilityScope.initialize(new FaultEntity(),new FacilityContext("tenant","app","worker","测试员"));
    fault.setId(UUID.randomUUID().toString()); fault.setDeviceId(id); fault.setProjectId(project); fault.setState("processing");
    faults.insert(fault).block(WAIT);
    var config = catalogStore.findById(id).block(WAIT);
    var move = new FacilityDevices.Allocation(id,config.getVersion(),next.getId(),null,null,false);
    StepVerifier.create(catalog.save(List.of(move))).expectError().verify(WAIT);
    assertEquals(project,catalogStore.findById(id).block(WAIT).getProjectId());
    fault.setState("cancelled"); faults.save(fault).block(WAIT);
    catalog.save(List.of(move)).block(WAIT);
    assertEquals(project,devices.findById(id).block(WAIT).getProjectId());
    synchronizer.synchronizeConfigured().block(WAIT);
    assertEquals(next.getId(),devices.findById(id).block(WAIT).getProjectId());
    assertEquals(project,faults.findById(fault.getId()).block(WAIT).getProjectId());
  }
  private PropertyOrganizationInfo sourceProject(String id) {
    var p = new PropertyOrganizationInfo(); p.setId(id); p.setName(id);
    when(directory.project(id)).thenReturn(Mono.just(p)); return p;
  }
  private org.jetlinks.sdk.server.device.DeviceInfo sourceDevice(String id) {
    var row = new org.jetlinks.sdk.server.device.DeviceInfo(); row.setId(id); row.setName(id); return row;
  }
  @Import({FacilityPersistenceTest.Config.class,MeterChargeStore.class,FacilityMeterCharges.class,
    FacilityAllocations.class,DeviceAllocationStore.class,FacilitySync.class,FacilitySyncWriter.class,
    FacilitySyncDevice.class,FacilityDeviceMoves.class,MeterBillStore.class})
  static class Config {}
}
