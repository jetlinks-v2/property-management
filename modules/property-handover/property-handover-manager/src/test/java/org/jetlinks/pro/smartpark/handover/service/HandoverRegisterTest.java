package org.jetlinks.pro.smartpark.handover.service;

import static org.jetlinks.pro.smartpark.handover.service.HandoverPersistenceFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import org.jetlinks.pro.smartpark.handover.api.*;
import org.jetlinks.pro.smartpark.handover.entity.*;
import org.hswebframework.web.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.List;
import java.util.Set;

@SpringBootTest(classes=HandoverPersistenceFixture.Config.class, webEnvironment=SpringBootTest.WebEnvironment.NONE)
class HandoverRegisterTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { HandoverPersistenceFixture.database(registry); }
 @Autowired HandoverConfigurationService service;
 @Autowired HandoverScope scope;
 @Autowired HandoverDirectory directory;
 @Autowired HandoverLedgerRepository ledgers;
 @Autowired HandoverEquipmentRepository equipment;
 @Autowired HandoverResponsibilityRepository units;
 @BeforeEach void setup() {
  ledgers.createDelete().where("tenantId","tenant").execute().block(WAIT);
  units.createDelete().where("tenantId","tenant").execute().block(WAIT);
  HandoverPersistenceFixture.directory(directory,true,List.of("external-a"));
  var unit=new HandoverResponsibilityEntity();unit.setProjectId("project");unit.setOrganizationId("external-a");
  service.save(units,unit).contextWrite(access("tester",Set.of("project"))).block(WAIT);
 }
 @Test void ledgerNeedsDescriptionButNotArtificialTitle() {
  var ledger=new HandoverLedgerEntity();ledger.setProjectId("project");ledger.setDescription("车库顶面渗水");
  var saved=service.save(ledgers,ledger).contextWrite(access("tester",Set.of("project"))).block(WAIT);
  assertEquals("tester",saved.getEntryUserId());assertNotNull(saved.getEntryTime());assertNotNull(saved.getId());assertEquals("车库顶面渗水",saved.getDescription());
 }

 @Test void supplementalPhotosPersistRealActorAndRejectReplayOrUnauthorizedUnit() {
  var row=new HandoverLedgerEntity();row.setProjectId("project");row.setDescription("补图测试");row.setOrganizationId("external-a");
  row=service.save(ledgers,row).contextWrite(access("tester",Set.of("project"))).block(WAIT);
  var input=new ActionRequest();input.setVersion(row.getVersion());var photo=new FileRef();photo.setFileId("file-1");input.setPhotos(List.of(photo));
  var updated=service.supplementLedger(row.getId(),input).contextWrite(access("photographer",Set.of("project"))).block(WAIT);
  assertEquals("photographer",updated.getSupplementUserId());assertNotNull(updated.getSupplementTime());
  assertEquals("file-1",updated.getFiles().get(0).getFileId());
  final String id=row.getId();
  assertThrows(BusinessException.class,()->service.supplementLedger(id,input).contextWrite(access("tester",Set.of("project"))).block(WAIT));
  assertEquals(1,ledgers.findById(id).block(WAIT).getFiles().size());
  var wrong=new HandoverLedgerEntity();wrong.setProjectId("project");wrong.setDescription("越权单位");wrong.setOrganizationId("foreign");
  assertThrows(BusinessException.class,()->service.save(ledgers,wrong).contextWrite(access("tester",Set.of("project"))).block(WAIT));
 }
 @Test void equipmentRoundTripPreservesTypedFieldsAndFilteredPaging() {
  equipment.createDelete().where("tenantId","tenant").execute().block(WAIT);
  var row=new HandoverEquipmentEntity();row.setProjectId("project");row.setName("消防泵");row.setCategoryName("消防设备");
  row.setQuantity(new java.math.BigDecimal("2.125"));row.setLocation("地下1层");row.setProductionDate(100L);row.setInstallationDate(200L);
  row.setControlArea("A组团");row.setSupplier("设备供应公司");row.setProperties(java.util.Map.of("extra", "原参数保留"));
  var stored=service.save(equipment,row).contextWrite(access("tester",Set.of("project"))).block(WAIT);
  var q=new HandoverQuery();q.setProjectId("project");q.setLocation("地下");q.setModifyTimeFrom(0L);
  var page=service.query(equipment,q,"name").contextWrite(access("tester",Set.of("project"))).block(WAIT);
  assertEquals(1,page.getTotal());assertEquals("消防设备",page.getData().get(0).getCategoryName());
  assertEquals(0,new java.math.BigDecimal("2.125").compareTo(page.getData().get(0).getQuantity()));
  assertEquals("原参数保留",page.getData().get(0).getProperties().get("extra"));
  q.setLocation("三层");assertEquals(0,service.query(equipment,q,"name").contextWrite(access("tester",Set.of("project"))).block(WAIT).getTotal());
 }

}
