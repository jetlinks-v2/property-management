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
  @Autowired FacilityReplacements replacementManager;
  @Autowired ReplacementStore replacements;
  String project;
  @BeforeEach void setup() {
    reset(scope, cloud, directory);
    project=UUID.randomUUID().toString();
    when(scope.current()).thenReturn(Mono.just(new FacilityContext("tenant","app","worker","测试员")));
    when(scope.projectExists(anyString())).thenReturn(Mono.just(true));
    doAnswer(call -> Mono.just(FacilityScope.applicationQuery(call.getArgument(0),
      new FacilityContext("tenant","app","worker","测试员")))).when(scope).query(any(),any(),anyString());
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
    device.setEnergyEnabled(true); devices.save(device).block(WAIT);
    when(cloud.snapshot(null)).thenReturn(Mono.just(List.of(sourceDevice(device.getCloudDeviceId()))));
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
  @Test void energyMonitoringGatesNewAndEnabledMappingsButRetainsExistingDisabledConfiguration() {
    var device=device(UUID.randomUUID().toString());
    when(cloud.snapshot(null)).thenReturn(Mono.just(List.of(sourceDevice(device.getCloudDeviceId()))));
    var metadata=mock(org.jetlinks.core.things.ThingMetadata.class);
    var property=mock(org.jetlinks.core.metadata.PropertyMetadata.class);
    when(property.getId()).thenReturn("total"); when(metadata.getProperties()).thenReturn(List.of(property));
    when(cloud.metadata(device.getCloudDeviceId())).thenReturn(Mono.just(metadata));
    var metric=new MetricMapping(); metric.setPropertyId("total"); metric.setKind("counter"); metric.setUnit("m³");
    metric.setMultiplier(BigDecimal.ONE); metric.setScale(2);
    var input=new MappingEntity(); input.setDeviceId(device.getId()); input.setEnergyType("water");
    input.setMeterLevel("master"); input.setStaleAfterSeconds(3600L); input.setMetrics(List.of(metric)); input.setEnabled(true);
    StepVerifier.create(mappingManager.save(input)).expectErrorMessage("error.facility.device_energy_disabled").verify(WAIT);
    input.setEnabled(false);
    StepVerifier.create(mappingManager.save(input)).expectErrorMessage("error.facility.device_energy_disabled").verify(WAIT);
    assertEquals(0,mappings.createQuery().where("deviceId",device.getId()).count().block(WAIT));
    assertEquals("device",devices.findById(device.getId()).block(WAIT).getMeterType());
    device.setEnergyEnabled(true); devices.save(device).block(WAIT); input.setEnabled(true);
    var saved=mappingManager.save(input).block(WAIT);
    var current=devices.findById(device.getId()).block(WAIT); current.setEnergyEnabled(false); devices.save(current).block(WAIT);
    StepVerifier.create(mappingManager.save(saved)).expectErrorMessage("error.facility.device_energy_disabled").verify(WAIT);
    assertTrue(mappings.findById(saved.getId()).block(WAIT).getEnabled());
    saved.setEnabled(false); var stopped=mappingManager.save(saved).block(WAIT);
    assertEquals(saved.getId(),stopped.getId()); assertFalse(stopped.getEnabled()); assertEquals(1,stopped.getMetrics().size());
    assertNotNull(mappingManager.forDevice(device.getId()).block(WAIT));
    assertFalse(devices.findById(device.getId()).block(WAIT).getEnergyEnabled());
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
    // 配置目录必须仍展示全部授权来源，项目下拉仅提供目标候选或显式查询条件。
    when(directory.projects()).thenReturn(Flux.just(current));
    assertEquals(2, catalog.query(new org.hswebframework.web.api.crud.entity.QueryParamEntity()).block(WAIT).getTotal());
    when(directory.projects()).thenReturn(Flux.just(current, other));
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
  @Test void applicationBindingControlsCatalogAllocationAndSyncWithoutRemovingHistoricalConfiguration() {
    var one = sourceDevice(UUID.randomUUID().toString());
    var two = sourceDevice(UUID.randomUUID().toString());
    var unassigned = sourceDevice(UUID.randomUUID().toString());
    var current = sourceProject(project);
    var other = sourceProject(UUID.randomUUID().toString());
    when(directory.projects()).thenReturn(Flux.just(current));
    when(cloud.snapshot(null)).thenReturn(Mono.just(List.of(one, two)));
    catalog.refresh().block(WAIT);
    String first = FacilityRecords.stableId("tenant", "app", one.getId());
    String second = FacilityRecords.stableId("tenant", "app", two.getId());
    String pending = FacilityRecords.stableId("tenant", "app", unassigned.getId());
    catalog.save(List.of(new FacilityDevices.Allocation(first,0L,project,null,null,false),
      new FacilityDevices.Allocation(second,0L,project,null,null,false))).block(WAIT);
    assertEquals(2, synchronizer.synchronizeConfigured().block(WAIT).inserted());
    var historical = devices.findById(first).block(WAIT);
    historical.setSpaceId("room"); historical.setMeterType("water"); historical.setEnergyEnabled(true);
    historical.setAllocationSince(123L); devices.save(historical).block(WAIT);
    var mapping = FacilityScope.initialize(new MappingEntity(),new FacilityContext("tenant","app","worker","测试员"));
    mapping.setId(UUID.randomUUID().toString()); mapping.setDeviceId(first); mapping.setProjectId(project); mapping.setEnabled(true);
    mappings.insert(mapping).block(WAIT);
    var firstConfig = catalogStore.findById(first).block(WAIT);
    var secondConfig = catalogStore.findById(second).block(WAIT);

    // 应用解绑立即裁剪查询和保存；新绑定但未分配项目的设备不能自动产生资产。
    when(cloud.snapshot(null)).thenReturn(Mono.just(List.of(two, unassigned)));
    assertEquals(2, catalog.refresh().block(WAIT));
    var page = catalog.query(new org.hswebframework.web.api.crud.entity.QueryParamEntity()).block(WAIT);
    assertEquals(Set.of(second,pending), page.getData().stream().map(DeviceAllocationEntity::getId).collect(java.util.stream.Collectors.toSet()));
    StepVerifier.create(catalog.save(List.of(
      new FacilityDevices.Allocation(second,secondConfig.getVersion(),other.getId(),null,null,false),
      new FacilityDevices.Allocation(first,firstConfig.getVersion(),other.getId(),null,null,false))))
      .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify(WAIT);
    assertEquals(project,catalogStore.findById(second).block(WAIT).getProjectId());
    assertEquals(secondConfig.getVersion(),catalogStore.findById(second).block(WAIT).getVersion());
    var synced = synchronizer.synchronizeConfigured().block(WAIT);
    assertEquals(1,synced.updated()); assertEquals(0,synced.inserted()); assertEquals(0,synced.failed());
    assertNull(devices.findById(pending).block(WAIT));
    assertEquals(historical.getVersion(),devices.findById(first).block(WAIT).getVersion());

    when(cloud.snapshot(null)).thenReturn(Mono.just(List.of()));
    assertEquals(0,catalog.query(new org.hswebframework.web.api.crud.entity.QueryParamEntity()).block(WAIT).getTotal());
    var empty = synchronizer.synchronizeConfigured().block(WAIT);
    assertEquals(0,empty.updated()); assertEquals(0,empty.failed());
    assertEquals(historical.getVersion(),devices.findById(first).block(WAIT).getVersion());
    assertTrue(mappings.findById(mapping.getId()).block(WAIT).getEnabled());

    // 重新绑定沿用原项目和历史计量配置，不重复创建资产。
    when(cloud.snapshot(null)).thenReturn(Mono.just(List.of(one,two,unassigned)));
    catalog.refresh().block(WAIT);
    assertEquals(project,catalogStore.findById(first).block(WAIT).getProjectId());
    var rebound = synchronizer.synchronizeConfigured().block(WAIT);
    assertEquals(2,rebound.updated()); assertEquals(0,rebound.inserted()); assertEquals(0,rebound.failed());
    var retained = devices.findById(first).block(WAIT);
    assertEquals("room",retained.getSpaceId()); assertEquals("water",retained.getMeterType());
    assertTrue(retained.getEnergyEnabled()); assertEquals(123L,retained.getAllocationSince());
    assertTrue(mappings.findById(mapping.getId()).block(WAIT).getEnabled());
  }
  @Test void deviceCandidatesExcludeUnboundAssetsBeforePagingAndDoNotAllowOrFiltersToBypassBinding() {
    var bound = sourceDevice(UUID.randomUUID().toString());
    String boundId = FacilityRecords.stableId("tenant","app",bound.getId());
    var current = device(boundId); current.setCloudDeviceId(bound.getId()); current.setName("当前水表"); devices.save(current).block(WAIT);
    var historical = device(UUID.randomUUID().toString()); historical.setName("旧水表"); devices.save(historical).block(WAIT);
    when(cloud.snapshot(null)).thenReturn(Mono.just(List.of(bound)));
    var query = org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
      .is("id",historical.getId()).or("id",boundId).doPaging(0,1).getParam();
    var page = allocation.query(query).block(WAIT);
    assertEquals(1,page.getTotal()); assertEquals(boundId,page.getData().get(0).getId());
    assertNotNull(allocation.read(historical.getId()).block(WAIT));
    when(cloud.snapshot(null)).thenReturn(Mono.just(List.of()));
    assertEquals(0,allocation.query(query).block(WAIT).getTotal());
    when(cloud.snapshot(null)).thenReturn(Mono.error(new IllegalStateException("source unavailable")));
    StepVerifier.create(allocation.query(query)).expectErrorMessage("source unavailable").verify(WAIT);
    assertNotNull(devices.findById(historical.getId()).block(WAIT));
  }
  @Test void mappingSaveRejectsUnboundDeviceEvenWhenItsOldAssetRemainsWritable() {
    var row=device(UUID.randomUUID().toString());
    var metadata=mock(org.jetlinks.core.things.ThingMetadata.class);
    var property=mock(org.jetlinks.core.metadata.PropertyMetadata.class);
    when(property.getId()).thenReturn("total"); when(metadata.getProperties()).thenReturn(List.of(property));
    when(cloud.metadata(row.getCloudDeviceId())).thenReturn(Mono.just(metadata));
    when(cloud.snapshot(null)).thenReturn(Mono.just(List.of()));
    var metric=new MetricMapping(); metric.setPropertyId("total"); metric.setKind("counter"); metric.setUnit("m³");
    metric.setMultiplier(BigDecimal.ONE); metric.setScale(2);
    var input=new MappingEntity(); input.setDeviceId(row.getId()); input.setEnergyType("water");
    input.setMeterLevel("submeter"); input.setStaleAfterSeconds(3600L); input.setMetrics(List.of(metric)); input.setEnabled(true);
    StepVerifier.create(mappingManager.save(input))
      .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify(WAIT);
    assertEquals("device",devices.findById(row.getId()).block(WAIT).getMeterType());
    assertTrue(mappings.createQuery().where("deviceId",row.getId()).fetch().collectList().block(WAIT).isEmpty());
  }
  @Test void meterListHidesRevokedDevicesAndRebindingRestoresSameMappingAndReadings() {
    var one=device(UUID.randomUUID().toString()); var two=device(UUID.randomUUID().toString());
    var context=new FacilityContext("tenant","app","worker","测试员");
    var first=FacilityScope.initialize(new MappingEntity(),context); first.setDeviceId(one.getId()); first.setProjectId(project);
    var second=FacilityScope.initialize(new MappingEntity(),context); second.setDeviceId(two.getId()); second.setProjectId(project);
    mappings.insert(first).block(WAIT); mappings.insert(second).block(WAIT);
    var reading=FacilityScope.initialize(new ReadingEntity(),context); reading.setDeviceId(two.getId()); reading.setProjectId(project);
    reading.setObservedTime(1000L); reading.setPropertyId("total"); reading.setValue(BigDecimal.TEN); reading.setStatus("accepted");
    readingStore.insert(reading).block(WAIT);
    var query=org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery().is("deviceId",two.getId())
      .or("deviceId",one.getId()).doPaging(0,1).getParam();
    when(cloud.snapshot(null)).thenReturn(Mono.just(List.of(sourceDevice(one.getCloudDeviceId()))));
    var active=mappingManager.query(query).block(WAIT);
    assertEquals(1,active.getTotal()); assertEquals(first.getId(),active.getData().get(0).getId());
    assertEquals(second.getId(),mappingManager.forDevice(two.getId()).block(WAIT).getId());
    when(cloud.snapshot(null)).thenReturn(Mono.just(List.of()));
    assertEquals(0,mappingManager.query(query).block(WAIT).getTotal());
    assertEquals(0,BigDecimal.TEN.compareTo(readingStore.findById(reading.getId()).block(WAIT).getValue()));
    when(cloud.snapshot(null)).thenReturn(Mono.just(List.of(sourceDevice(one.getCloudDeviceId()),sourceDevice(two.getCloudDeviceId()))));
    assertEquals(2,mappingManager.query(query).block(WAIT).getTotal());
    assertEquals(second.getId(),mappingManager.forDevice(two.getId()).block(WAIT).getId());
    assertEquals(1,readingStore.createQuery().where("deviceId",two.getId()).count().block(WAIT));
  }
  @Autowired ReadingStore readingStore;
  @Test void historicalReplacementNamesSurviveRevocationWithoutExposingUnauthorizedDevices() {
    var old=device(UUID.randomUUID().toString()); old.setName("原水表"); devices.save(old).block(WAIT);
    var next=device(UUID.randomUUID().toString()); next.setName("新水表"); devices.save(next).block(WAIT);
    var row=FacilityScope.initialize(new ReplacementEntity(),new FacilityContext("tenant","app","worker","测试员"));
    row.setProjectId(project); row.setOldDeviceId(old.getId()); row.setNewDeviceId(next.getId());
    row.setState("approved"); replacements.insert(row).block(WAIT);
    var foreign=FacilityScope.initialize(new ReplacementEntity(),new FacilityContext("tenant","other-app","worker","测试员"));
    foreign.setProjectId(project); foreign.setOldDeviceId(old.getId()); foreign.setNewDeviceId(next.getId());
    replacements.insert(foreign).block(WAIT);
    var query=org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery()
      .is("oldDeviceId",old.getId()).doPaging(0,1).getParam();
    when(cloud.snapshot(null)).thenReturn(Mono.just(List.of()));
    var page=replacementManager.page(query).block(WAIT);
    assertEquals(1,page.getTotal()); assertEquals(row.getId(),page.getData().get(0).getId());
    assertEquals("原水表",page.getData().get(0).getOldDeviceName());
    assertEquals("新水表",page.getData().get(0).getNewDeviceName());
    doAnswer(call -> {
      org.hswebframework.web.api.crud.entity.QueryParamEntity scoped=FacilityScope.applicationQuery(call.getArgument(0),
        new FacilityContext("tenant","app","worker","测试员"));
      return Mono.just("id".equals(call.getArgument(2))
        ? scoped.toNestQuery().not("id",next.getId()).getParam() : scoped);
    }).when(scope).query(any(),any(),anyString());
    var limited=replacementManager.page(query).block(WAIT).getData().get(0);
    assertEquals("原水表",limited.getOldDeviceName()); assertNull(limited.getNewDeviceName());
    assertEquals(next.getId(),limited.getNewDeviceId());
    assertEquals("approved",replacements.findById(row.getId()).block(WAIT).getState());
    verify(cloud,never()).snapshot(any());
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
    FacilitySyncDevice.class,FacilityDeviceMoves.class,MeterBillStore.class,FacilityReplacements.class})
  static class Config {}
}
